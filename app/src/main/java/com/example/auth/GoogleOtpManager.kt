package com.example.auth

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.R
import com.example.data.DeviceIdentityStore
import com.example.data.OperationType
import com.example.data.handleFirestoreError
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private const val OTP_TAG = "GoogleOtpManager"
private const val OTP_COLLECTION = "otp_verifications"
private const val OTP_VALIDITY_MS = 10 * 60 * 1000L // 10 minutes
private const val MAX_OTP_ATTEMPTS = 5
private const val PLACEHOLDER_GMAIL_EMAIL = "MY_GMAIL_ADDRESS"
private const val PLACEHOLDER_GMAIL_APP_PASSWORD = "MY_GMAIL_APP_PASSWORD"
private const val PLACEHOLDER_RESEND_KEY = "MY_RESEND_API_KEY"

sealed interface OtpSendResult {
    data class Sent(val email: String, val providerLabel: String) : OtpSendResult
    data class EmailServiceNotConfigured(val email: String) : OtpSendResult
    data class Error(val message: String) : OtpSendResult
}

sealed interface OtpVerifyResult {
    data object Verified : OtpVerifyResult
    data class InvalidCode(val remainingAttempts: Int) : OtpVerifyResult
    data object Expired : OtpVerifyResult
    data object TooManyAttempts : OtpVerifyResult
    data class Error(val message: String) : OtpVerifyResult
}

/**
 * Manages real 6-digit Email OTP verification sent to the signed-in Google account's Gmail inbox.
 *
 * Delivery Priority:
 * 1. Direct Google Gmail SMTP over SSL (`smtp.gmail.com:465`) via `BuildConfig.GMAIL_SMTP_EMAIL`
 *    + `BuildConfig.GMAIL_SMTP_APP_PASSWORD` — sends real Google emails to ALL 6 family Gmail accounts
 *    without requiring any custom domain.
 * 2. Optional Resend HTTPS API fallback (`BuildConfig.RESEND_API_KEY`) if configured.
 *
 * Security design:
 * - Generates a cryptographically secure 6-digit OTP via [SecureRandom].
 * - Never stores plaintext OTPs in Firestore; only a 64-char SHA-256 hash (`uid:deviceId:otp`) is saved
 *   under `/otp_verifications/{uid}` protected by self-only Firestore rules.
 */
class GoogleOtpManager(context: Context) {

    private val appContext = context.applicationContext
    private val identityStore = DeviceIdentityStore(appContext)
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(
        appContext.getString(R.string.firestore_database_id)
    )
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Returns true ONLY if this Google UID + local Device ID has already completed OTP verification.
     */
    suspend fun isSessionOtpVerified(user: FirebaseUser): Boolean {
        val uid = user.uid
        if (uid.isBlank()) return false
        val prefs = identityStore.readSnapshot()
        val localDeviceId = identityStore.getOrCreateDeviceId()

        if (prefs.verifiedOtpUid == uid && prefs.verifiedOtpEmail.equals(user.email.orEmpty(), ignoreCase = true)) {
            return true
        }

        val docPath = "$OTP_COLLECTION/$uid"
        return try {
            val snap = db.collection(OTP_COLLECTION).document(uid).get().await()
            val verified = snap.getBoolean("verified") == true
            val storedDeviceId = snap.getString("deviceId").orEmpty()
            val storedEmail = snap.getString("email").orEmpty()
            if (verified && storedDeviceId == localDeviceId && storedEmail.equals(user.email.orEmpty(), ignoreCase = true)) {
                identityStore.markOtpVerified(uid, storedEmail)
                true
            } else {
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docPath)
            false
        }
    }

    /**
     * Generates a fresh 6-digit OTP, emails it directly via Google Gmail SMTP (or Resend fallback)
     * to the signed-in Google account's Gmail inbox, and stores only its SHA-256 hash in Firestore.
     */
    suspend fun sendOtpToGoogleEmail(user: FirebaseUser): OtpSendResult {
        val uid = user.uid
        val email = user.email?.trim().orEmpty()
        if (uid.isBlank() || email.isBlank()) {
            return OtpSendResult.Error("Google account email not available. Please sign in again.")
        }

        val deviceId = identityStore.getOrCreateDeviceId()
        val otpCode = generateSixDigitOtp()
        val otpHash = computeOtpHash(uid, deviceId, otpCode)
        val expiresAtEpochMs = System.currentTimeMillis() + OTP_VALIDITY_MS

        val gmailUser = BuildConfig.GMAIL_SMTP_EMAIL.trim()
        val gmailAppPassword = BuildConfig.GMAIL_SMTP_APP_PASSWORD.replace("\\s+".toRegex(), "")
        val isGmailSmtpConfigured = gmailUser.isNotBlank() &&
            gmailUser != PLACEHOLDER_GMAIL_EMAIL &&
            gmailUser.contains("@") &&
            gmailAppPassword.isNotBlank() &&
            gmailAppPassword != PLACEHOLDER_GMAIL_APP_PASSWORD

        val resendKey = BuildConfig.RESEND_API_KEY.trim()
        val resendFrom = BuildConfig.OTP_FROM_EMAIL.trim().ifBlank { "onboarding@resend.dev" }
        val isResendConfigured = resendKey.isNotBlank() && resendKey != PLACEHOLDER_RESEND_KEY

        val isAnyEmailProviderConfigured = isGmailSmtpConfigured || isResendConfigured

        var deliveryStatus = "PENDING"
        var providerUsed = ""
        var deliveryFailureReason: String? = null

        if (isGmailSmtpConfigured) {
            val smtpResult = deliverOtpEmailViaGmailSmtp(
                smtpUser = gmailUser,
                smtpAppPassword = gmailAppPassword,
                toEmail = email,
                otpCode = otpCode,
                displayName = user.displayName.orEmpty(),
                deviceId = deviceId
            )
            smtpResult.onSuccess {
                deliveryStatus = "SENT"
                providerUsed = "Google Gmail"
            }.onFailure { err ->
                deliveryStatus = "FAILED"
                deliveryFailureReason = err.localizedMessage ?: "Gmail SMTP delivery failed"
            }
        } else if (isResendConfigured) {
            val resendResult = deliverOtpEmailViaResend(
                apiKey = resendKey,
                fromEmail = resendFrom,
                toEmail = email,
                otpCode = otpCode,
                displayName = user.displayName.orEmpty(),
                deviceId = deviceId
            )
            resendResult.onSuccess {
                deliveryStatus = "SENT"
                providerUsed = "Resend Email"
            }.onFailure { err ->
                deliveryStatus = "FAILED"
                deliveryFailureReason = err.localizedMessage ?: "Email delivery failed"
            }
        }

        // Persist hashed OTP challenge in Firestore (strictly aligned with firestore.rules)
        val docPath = "$OTP_COLLECTION/$uid"
        try {
            val docRef = db.collection(OTP_COLLECTION).document(uid)
            val existing = docRef.get().await()
            if (!existing.exists()) {
                val createPayload = mapOf(
                    "email" to email,
                    "deviceId" to deviceId,
                    "otpHash" to otpHash,
                    "verified" to false,
                    "attempts" to 0L,
                    "expiresAtEpochMs" to expiresAtEpochMs,
                    "deliveryStatus" to deliveryStatus,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(createPayload).await()
            } else {
                val updatePayload = mapOf(
                    "deviceId" to deviceId,
                    "otpHash" to otpHash,
                    "verified" to false,
                    "attempts" to 0L,
                    "expiresAtEpochMs" to expiresAtEpochMs,
                    "deliveryStatus" to deliveryStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updatePayload).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            return OtpSendResult.Error(e.localizedMessage ?: "Failed to save OTP challenge")
        }

        return when {
            !isAnyEmailProviderConfigured -> OtpSendResult.EmailServiceNotConfigured(email)
            deliveryStatus == "SENT" -> OtpSendResult.Sent(email, providerUsed)
            else -> OtpSendResult.Error(deliveryFailureReason ?: "Unable to send OTP email to $email")
        }
    }

    /**
     * Verifies a pasted or typed 6-digit OTP against the SHA-256 hash stored in Firestore.
     */
    suspend fun verifyOtpCode(user: FirebaseUser, rawInput: String): OtpVerifyResult {
        val uid = user.uid
        val email = user.email?.trim().orEmpty()
        val digits = extractSixDigitOtp(rawInput)
        if (digits.length != 6) {
            return OtpVerifyResult.Error("Please paste or enter a valid 6-digit OTP code.")
        }

        val deviceId = identityStore.getOrCreateDeviceId()
        val docPath = "$OTP_COLLECTION/$uid"
        return try {
            val docRef = db.collection(OTP_COLLECTION).document(uid)
            val snap = docRef.get().await()
            if (!snap.exists()) {
                return OtpVerifyResult.Error("No active OTP found. Tap Resend OTP first.")
            }

            val storedHash = snap.getString("otpHash").orEmpty()
            val storedDeviceId = snap.getString("deviceId").orEmpty()
            val attempts = (snap.getLong("attempts") ?: 0L).toInt()
            val expiresAtEpochMs = snap.getLong("expiresAtEpochMs") ?: 0L

            if (System.currentTimeMillis() > expiresAtEpochMs) {
                return OtpVerifyResult.Expired
            }
            if (attempts >= MAX_OTP_ATTEMPTS) {
                return OtpVerifyResult.TooManyAttempts
            }

            val candidateHash = computeOtpHash(uid, storedDeviceId.ifBlank { deviceId }, digits)
            if (candidateHash == storedHash) {
                docRef.update(
                    mapOf(
                        "verified" to true,
                        "attempts" to (attempts + 1).toLong(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
                identityStore.markOtpVerified(uid, email)
                OtpVerifyResult.Verified
            } else {
                val nextAttempts = (attempts + 1).coerceAtMost(MAX_OTP_ATTEMPTS + 1)
                docRef.update(
                    mapOf(
                        "attempts" to nextAttempts.toLong(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
                val remaining = (MAX_OTP_ATTEMPTS - nextAttempts).coerceAtLeast(0)
                if (remaining == 0) OtpVerifyResult.TooManyAttempts else OtpVerifyResult.InvalidCode(remaining)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            OtpVerifyResult.Error(e.localizedMessage ?: "Verification failed. Please retry.")
        }
    }

    /**
     * Direct TLS SMTP delivery through Google's official Gmail server (`smtp.gmail.com:465`).
     * Uses RFC-5321 AUTH LOGIN with a Google App Password so real OTP emails arrive in ANY
     * family member's Gmail inbox immediately without third-party domains.
     */
    private suspend fun deliverOtpEmailViaGmailSmtp(
        smtpUser: String,
        smtpAppPassword: String,
        toEmail: String,
        otpCode: String,
        displayName: String,
        deviceId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var socket: SSLSocket? = null
        try {
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = sslFactory.createSocket() as SSLSocket
            socket = sslSocket
            sslSocket.soTimeout = 20_000
            sslSocket.connect(InetSocketAddress("smtp.gmail.com", 465), 15_000)
            sslSocket.startHandshake()

            val reader = BufferedReader(InputStreamReader(sslSocket.inputStream, Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(sslSocket.outputStream, Charsets.UTF_8))

            fun readResponse(): Pair<Int, String> {
                val sb = StringBuilder()
                var code = -1
                while (true) {
                    val line = reader.readLine() ?: break
                    sb.append(line).append('\n')
                    if (line.length >= 3) {
                        code = line.substring(0, 3).toIntOrNull() ?: code
                    }
                    // Multi-line SMTP responses have '-' at index 3; final line has ' ' or ends
                    if (line.length < 4 || line[3] == ' ') break
                }
                return code to sb.toString().trim()
            }

            fun sendCommand(cmd: String, expectedPrefix: Int, errorContext: String) {
                writer.write(cmd)
                writer.write("\r\n")
                writer.flush()
                val (code, resp) = readResponse()
                if (code / 100 != expectedPrefix) {
                    if (code == 535) {
                        throw IllegalStateException(
                            "Google Gmail rejected the App Password (535). Enable 2-Step Verification and generate a 16-char Google App Password at myaccount.google.com/apppasswords."
                        )
                    }
                    throw IllegalStateException("$errorContext (SMTP $code): $resp")
                }
            }

            // 1. Server greeting (220)
            val (greetCode, greetMsg) = readResponse()
            if (greetCode != 220) {
                throw IllegalStateException("Gmail SMTP greeting failed ($greetCode): $greetMsg")
            }

            // 2. EHLO & AUTH LOGIN
            sendCommand("EHLO mamun-walkie.local", 2, "EHLO failed")
            sendCommand("AUTH LOGIN", 3, "AUTH LOGIN failed")
            val userB64 = Base64.encodeToString(smtpUser.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val passB64 = Base64.encodeToString(smtpAppPassword.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            sendCommand(userB64, 3, "Gmail username rejected")
            sendCommand(passB64, 2, "Gmail authentication failed")

            // 3. Envelope
            sendCommand("MAIL FROM:<$smtpUser>", 2, "MAIL FROM rejected")
            sendCommand("RCPT TO:<$toEmail>", 2, "Recipient rejected")
            sendCommand("DATA", 3, "DATA command rejected")

            // 4. MIME multipart/alternative Email Payload
            val boundary = "mamun_otp_${UUID.randomUUID().toString().replace("-", "")}"
            val dateFmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val messageId = "<${UUID.randomUUID()}@mamunwalkietalkie.local>"
            val greeting = if (displayName.isNotBlank()) "Hi $displayName," else "Hello,"

            val plainText = buildString {
                appendLine(greeting)
                appendLine()
                appendLine("Your Mamun Walkie Talkie 6-digit OTP verification code is: $otpCode")
                appendLine()
                appendLine("Device ID: $deviceId")
                appendLine("Copy this 6-digit code and paste it inside the Mamun Walkie Talkie app to connect.")
                appendLine("This code expires in 10 minutes.")
            }

            val htmlBody = """
                <div style="font-family:Arial,sans-serif;max-width:480px;margin:0 auto;padding:24px;background:#10141A;color:#F2F6FA;border-radius:16px;border:1px solid #2A323D;">
                  <h2 style="color:#FFBE0B;margin:0 0 8px;">Mamun Walkie Talkie</h2>
                  <p style="color:#9BA8B7;margin:0 0 20px;font-size:14px;">$greeting use this 6-digit Google verification code to connect your phone ($deviceId) to the private family walkie-talkie network:</p>
                  <div style="background:#8FE84E;color:#0A1806;font-family:monospace;font-size:34px;font-weight:bold;letter-spacing:8px;text-align:center;padding:18px;border-radius:12px;">$otpCode</div>
                  <p style="color:#9BA8B7;margin:20px 0 0;font-size:12px;">Copy this code and paste it inside the Mamun Walkie Talkie app. Expires in 10 minutes.</p>
                </div>
            """.trimIndent()

            val mimeMessage = buildString {
                append("From: Mamun Walkie Talkie <$smtpUser>\r\n")
                append("To: <$toEmail>\r\n")
                append("Subject: Mamun Walkie Talkie OTP: $otpCode\r\n")
                append("Date: ${dateFmt.format(Date())}\r\n")
                append("Message-ID: $messageId\r\n")
                append("MIME-Version: 1.0\r\n")
                append("Content-Type: multipart/alternative; boundary=\"$boundary\"\r\n")
                append("\r\n")
                append("--$boundary\r\n")
                append("Content-Type: text/plain; charset=UTF-8\r\n")
                append("Content-Transfer-Encoding: 8bit\r\n\r\n")
                append(plainText.replace("\n", "\r\n"))
                append("\r\n--$boundary\r\n")
                append("Content-Type: text/html; charset=UTF-8\r\n")
                append("Content-Transfer-Encoding: 8bit\r\n\r\n")
                append(htmlBody.replace("\n", "\r\n"))
                append("\r\n--$boundary--\r\n")
                append(".\r\n")
            }

            writer.write(mimeMessage)
            writer.flush()
            val (dataCode, dataResp) = readResponse()
            if (dataCode / 100 != 2) {
                throw IllegalStateException("Gmail failed to accept OTP email ($dataCode): $dataResp")
            }

            runCatching {
                writer.write("QUIT\r\n")
                writer.flush()
            }

            Log.i(OTP_TAG, "Real Google Gmail SMTP OTP delivered successfully to $toEmail")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(OTP_TAG, "Gmail SMTP OTP delivery failed", e)
            Result.failure(e)
        } finally {
            runCatching { socket?.close() }
        }
    }

    private suspend fun deliverOtpEmailViaResend(
        apiKey: String,
        fromEmail: String,
        toEmail: String,
        otpCode: String,
        displayName: String,
        deviceId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val greeting = if (displayName.isNotBlank()) "Hi $displayName," else "Hello,"
            val htmlBody = """
                <div style="font-family:Arial,sans-serif;max-width:480px;margin:0 auto;padding:24px;background:#10141A;color:#F2F6FA;border-radius:16px;border:1px solid #2A323D;">
                  <h2 style="color:#FFBE0B;margin:0 0 8px;">Mamun Walkie Talkie</h2>
                  <p style="color:#9BA8B7;margin:0 0 20px;font-size:14px;">$greeting use this 6-digit verification code to connect your phone ($deviceId) to the private family radio network:</p>
                  <div style="background:#8FE84E;color:#0A1806;font-family:monospace;font-size:34px;font-weight:bold;letter-spacing:8px;text-align:center;padding:18px;border-radius:12px;">$otpCode</div>
                  <p style="color:#9BA8B7;margin:20px 0 0;font-size:12px;">Copy this code and paste it inside the Mamun Walkie Talkie app. This OTP expires in 10 minutes.</p>
                </div>
            """.trimIndent()

            val plainText = "$greeting\n\nYour Mamun Walkie Talkie 6-digit OTP code is: $otpCode\n\nDevice ID: $deviceId\nCopy & paste this code inside the app to verify and connect. Expires in 10 minutes."

            val json = JSONObject().apply {
                put("from", "Mamun Walkie Talkie <$fromEmail>")
                put("to", JSONArray().put(toEmail))
                put("subject", "Mamun Walkie Talkie OTP: $otpCode")
                put("html", htmlBody)
                put("text", plainText)
            }

            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://api.resend.com/emails")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(OTP_TAG, "OTP email delivered successfully via Resend to $toEmail")
                    Result.success(Unit)
                } else {
                    val errText = response.body?.string().orEmpty()
                    val parsedMsg = runCatching { JSONObject(errText).optString("message") }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: "Email API error (HTTP ${response.code})"
                    Log.w(OTP_TAG, "Resend API rejected email (${response.code}): $parsedMsg")
                    Result.failure(IllegalStateException(parsedMsg))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(OTP_TAG, "Network failure delivering OTP email", e)
            Result.failure(e)
        }
    }

    private fun generateSixDigitOtp(): String {
        val random = SecureRandom()
        val code = 100000 + random.nextInt(900000)
        return code.toString()
    }

    private fun computeOtpHash(uid: String, deviceId: String, otp: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$uid:$deviceId:$otp".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * Extracts the first 6-digit numeric OTP from clipboard text (even if the user copied
         * the entire email sentence like "Your Mamun Walkie Talkie OTP: 482913").
         */
        fun extractSixDigitOtp(raw: String): String {
            val regexMatch = Regex("\\b(\\d{6})\\b").find(raw)
            if (regexMatch != null) return regexMatch.groupValues[1]
            return raw.filter { it.isDigit() }.take(6)
        }
    }
}

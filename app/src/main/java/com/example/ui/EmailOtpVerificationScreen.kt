package com.example.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.auth.GoogleOtpManager
import com.example.auth.OtpSendResult
import com.example.auth.OtpVerifyResult
import com.example.ui.theme.BrandAmber
import com.example.ui.theme.ChassisBevelHighlight
import com.example.ui.theme.ChassisBodyDark
import com.example.ui.theme.ChassisBodyMid
import com.example.ui.theme.ChassisObsidian
import com.example.ui.theme.LcdGreenBright
import com.example.ui.theme.LcdGreenDeep
import com.example.ui.theme.LcdInkDark
import com.example.ui.theme.OrbitronFontFamily
import com.example.ui.theme.PttNeonGreen
import com.example.ui.theme.ShareTechMonoFontFamily
import com.example.ui.theme.StatusDangerRed
import com.example.ui.theme.StatusOnlineGreen
import com.example.ui.theme.TextPrimaryLight
import com.example.ui.theme.TextSecondaryLight
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun EmailOtpVerificationScreen(
    user: FirebaseUser,
    otpManager: GoogleOtpManager,
    onOtpVerified: () -> Unit,
    onSwitchGoogleAccount: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val email = user.email.orEmpty()

    var otpInput by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var isVerifying by remember { mutableStateOf(false) }
    var resendCooldownSec by remember { mutableIntStateOf(0) }
    var statusHeadline by remember { mutableStateOf("SENDING OTP TO GMAIL...") }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var needsEmailApiSetup by remember { mutableStateOf(false) }

    fun triggerSendOtp() {
        if (isSending) return
        scope.launch {
            isSending = true
            errorMessage = null
            infoMessage = null
            needsEmailApiSetup = false
            statusHeadline = "SENDING 6-DIGIT OTP..."

            when (val res = otpManager.sendOtpToGoogleEmail(user)) {
                is OtpSendResult.Sent -> {
                    statusHeadline = "OTP SENT VIA ${res.providerLabel.uppercase()}"
                    infoMessage = "Real 6-digit OTP sent via ${res.providerLabel} to ${res.email}. Open Gmail, copy the code, and paste it below."
                    resendCooldownSec = 60
                }

                is OtpSendResult.EmailServiceNotConfigured -> {
                    statusHeadline = "GOOGLE GMAIL SMTP SETUP REQUIRED"
                    needsEmailApiSetup = true
                    errorMessage = "To receive real OTP emails from Google Gmail at ${res.email}, add GMAIL_SMTP_EMAIL and a 16-char GMAIL_SMTP_APP_PASSWORD (from myaccount.google.com/apppasswords) in the AI Studio Secrets panel, then rebuild & tap Resend OTP."
                }

                is OtpSendResult.Error -> {
                    statusHeadline = "OTP SEND FAILED"
                    errorMessage = res.message
                }
            }
            isSending = false
        }
    }

    // Auto-send OTP immediately after Google account selection
    LaunchedEffect(user.uid) {
        triggerSendOtp()
    }

    // Cooldown ticker for Resend OTP button
    LaunchedEffect(resendCooldownSec) {
        if (resendCooldownSec > 0) {
            delay(1000L)
            resendCooldownSec -= 1
        }
    }

    Scaffold(
        containerColor = ChassisObsidian
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(ChassisBodyMid, ChassisBodyDark, ChassisObsidian)
                    )
                )
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.verticalGradient(listOf(ChassisBodyMid, ChassisBodyDark)))
                    .border(1.5.dp, ChassisBevelHighlight, RoundedCornerShape(28.dp))
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Shield / Email Icon Badge
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(PttNeonGreen.copy(alpha = 0.25f), ChassisObsidian)
                            )
                        )
                        .border(2.dp, PttNeonGreen, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = BrandAmber,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = stringResource(R.string.otp_screen_title),
                    fontFamily = OrbitronFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = BrandAmber,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(6.dp))

                // Signed-in Google Account pill
                Text(
                    text = email,
                    fontFamily = ShareTechMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = TextPrimaryLight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(14.dp))

                // Mini Backlit LCD Status Display
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.verticalGradient(listOf(LcdGreenBright, LcdGreenDeep)))
                        .border(2.dp, ChassisObsidian, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = statusHeadline,
                            fontFamily = ShareTechMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = LcdInkDark,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "STEP 2 OF 2 • VERIFY OTP TO CONNECT",
                            fontFamily = ShareTechMonoFontFamily,
                            fontSize = 10.sp,
                            color = LcdInkDark.copy(alpha = 0.85f)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                if (infoMessage != null) {
                    Text(
                        text = infoMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusOnlineGreen,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                }

                if (errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(StatusDangerRed.copy(alpha = 0.14f))
                            .border(1.dp, StatusDangerRed.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimaryLight,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // 6-Digit OTP Input Box
                OutlinedTextField(
                    value = otpInput,
                    onValueChange = { incoming ->
                        otpInput = incoming.filter { it.isDigit() }.take(6)
                        if (errorMessage != null && !needsEmailApiSetup) errorMessage = null
                    },
                    label = { Text(stringResource(R.string.otp_input_label)) },
                    placeholder = {
                        Text(
                            text = "• • • • • •",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = ShareTechMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        letterSpacing = 10.sp,
                        textAlign = TextAlign.Center,
                        color = TextPrimaryLight
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PttNeonGreen,
                        unfocusedBorderColor = ChassisBevelHighlight,
                        focusedLabelColor = PttNeonGreen,
                        cursorColor = PttNeonGreen
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("otp_code_input")
                )

                Spacer(Modifier.height(12.dp))

                // Quick Action Row: Open Gmail + 1-Tap Paste OTP
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { openGmailApp(context) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("open_gmail_button")
                    ) {
                        Icon(Icons.Default.MarkEmailRead, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.otp_open_gmail), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = {
                            val pasted = readClipboardText(context)
                            val extracted = GoogleOtpManager.extractSixDigitOtp(pasted)
                            if (extracted.isNotBlank()) {
                                otpInput = extracted
                                errorMessage = null
                                infoMessage = "OTP pasted ($extracted). Tap Verify & Connect."
                            } else {
                                errorMessage = "No 6-digit OTP found in clipboard. Copy the code from Gmail first."
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("paste_otp_button")
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.otp_paste_code), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Primary Verify & Connect Button
                Button(
                    onClick = {
                        if (isVerifying) return@Button
                        scope.launch {
                            isVerifying = true
                            errorMessage = null
                            statusHeadline = "VERIFYING OTP..."
                            when (val res = otpManager.verifyOtpCode(user, otpInput)) {
                                is OtpVerifyResult.Verified -> {
                                    statusHeadline = "VERIFIED • CONNECTING!"
                                    onOtpVerified()
                                }

                                is OtpVerifyResult.InvalidCode -> {
                                    statusHeadline = "WRONG OTP CODE"
                                    errorMessage = "Incorrect OTP. ${res.remainingAttempts} attempt(s) remaining."
                                }

                                is OtpVerifyResult.Expired -> {
                                    statusHeadline = "OTP EXPIRED"
                                    errorMessage = "This OTP has expired. Tap Resend OTP to get a fresh code."
                                }

                                is OtpVerifyResult.TooManyAttempts -> {
                                    statusHeadline = "TOO MANY ATTEMPTS"
                                    errorMessage = "Maximum attempts reached. Tap Resend OTP for a new code."
                                }

                                is OtpVerifyResult.Error -> {
                                    statusHeadline = "VERIFICATION ERROR"
                                    errorMessage = res.message
                                }
                            }
                            isVerifying = false
                        }
                    },
                    enabled = otpInput.length == 6 && !isVerifying && !isSending,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PttNeonGreen,
                        contentColor = LcdInkDark,
                        disabledContainerColor = PttNeonGreen.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("verify_otp_button")
                ) {
                    if (isVerifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = LcdInkDark,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Verifying...", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.otp_verify_and_connect),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Resend OTP + Switch Account Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { triggerSendOtp() },
                        enabled = !isSending && resendCooldownSec == 0,
                        modifier = Modifier.testTag("resend_otp_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (resendCooldownSec > 0) "Resend (${resendCooldownSec}s)" else stringResource(R.string.otp_resend),
                            color = if (resendCooldownSec == 0 && !isSending) BrandAmber else TextSecondaryLight,
                            fontSize = 12.sp
                        )
                    }

                    TextButton(
                        onClick = onSwitchGoogleAccount,
                        enabled = !isVerifying,
                        modifier = Modifier.testTag("switch_google_account_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            tint = Color(0xFF9BA8B7),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.otp_switch_account),
                            color = TextSecondaryLight,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

private fun openGmailApp(context: Context) {
    val gmailIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.gm")
    if (gmailIntent != null) {
        gmailIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(gmailIntent) }
        return
    }
    val emailAppIntent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_APP_EMAIL)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(emailAppIntent) }
}

private fun readClipboardText(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    val clip = cm.primaryClip ?: return ""
    if (clip.itemCount <= 0) return ""
    return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
}

package com.example

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.auth.GoogleOtpManager
import com.example.auth.authStateFlow
import com.example.auth.signOutFromWalkie
import com.example.ui.AuthScreen
import com.example.ui.EmailOtpVerificationScreen
import com.example.ui.WalkieTalkieScreen
import com.example.ui.theme.ChassisObsidian
import com.example.ui.theme.MamunWalkieTalkieTheme
import com.example.ui.theme.PttNeonGreen
import com.example.ui.theme.ShareTechMonoFontFamily
import com.example.ui.theme.TextSecondaryLight
import com.example.viewmodel.WalkieViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MamunWalkieTalkieTheme {
                AppNavigation()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            (application as? WalkieApplication)?.walkieRuntime?.syncSystemVolume()
        }
        return handled
    }
}

private enum class OtpGateState {
    CHECKING,
    REQUIRED,
    VERIFIED
}

@Composable
fun AppNavigation(auth: FirebaseAuth = Firebase.auth) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            OtpGatedWalkieHost(user = user)
        }
    }
}

@Composable
private fun OtpGatedWalkieHost(user: FirebaseUser) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val otpManager = remember(context) { GoogleOtpManager(context) }

    var gateState by remember(user.uid) { mutableStateOf(OtpGateState.CHECKING) }

    LaunchedEffect(user.uid) {
        gateState = OtpGateState.CHECKING
        val verified = otpManager.isSessionOtpVerified(user)
        gateState = if (verified) OtpGateState.VERIFIED else OtpGateState.REQUIRED
    }

    when (gateState) {
        OtpGateState.CHECKING -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ChassisObsidian),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = PttNeonGreen)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "VERIFYING RADIO CREDENTIALS...",
                        fontFamily = ShareTechMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = TextSecondaryLight
                    )
                }
            }
        }

        OtpGateState.REQUIRED -> {
            EmailOtpVerificationScreen(
                user = user,
                otpManager = otpManager,
                onOtpVerified = { gateState = OtpGateState.VERIFIED },
                onSwitchGoogleAccount = {
                    val walkieApp = context.applicationContext as WalkieApplication
                    signOutFromWalkie(
                        credentialManager = credentialManager,
                        scope = scope,
                        beforeSignOut = { walkieApp.walkieRuntime.prepareForSignOut() }
                    )
                }
            )
        }

        OtpGateState.VERIFIED -> {
            AuthenticatedWalkieHost(currentUserId = user.uid)
        }
    }
}

@Composable
private fun AuthenticatedWalkieHost(currentUserId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: WalkieViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val walkieApp = app as WalkieApplication
                WalkieViewModel(
                    runtime = walkieApp.walkieRuntime,
                    currentUserId = currentUserId
                )
            }
        }
    )

    WalkieTalkieScreen(
        viewModel = viewModel,
        onRequestSignOut = {
            signOutFromWalkie(
                credentialManager = credentialManager,
                scope = scope,
                beforeSignOut = { viewModel.prepareForSignOut() }
            )
        }
    )
}

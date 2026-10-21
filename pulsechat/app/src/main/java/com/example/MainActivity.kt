package com.example

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.repository.SessionState
import com.example.notifications.NotificationHelper
import com.example.ui.auth.AuthRoute
import com.example.ui.common.LoadingBox
import com.example.ui.navigation.PulseNavHost
import com.example.ui.security.LockScreen
import com.example.ui.theme.PulseChatTheme
import com.example.util.LocaleHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val pendingIntent = MutableStateFlow<Intent?>(null)
    private lateinit var legacyUnlock: ActivityResultLauncher<Intent>
    private val container get() = (application as PulseChatApplication).container

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        legacyUnlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) container.appLock.unlock()
        }
        applyCallWindowFlags(intent)
        if (savedInstanceState == null) pendingIntent.value = intent
        setContent {
            val settings by container.prefs.settings.collectAsState()
            PulseChatTheme(settings.themeMode) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (!container.firebaseAvailable) ConfigErrorScreen()
                    else AppRoot()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyCallWindowFlags(intent)
        pendingIntent.value = intent
    }

    // Screens PulseChat opens itself (photo/file pickers, camera, share sheet, "open with") must not
    // trigger the app lock when the user comes back from them.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        container.appLock.onExternalActivityLaunched()
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        container.appLock.onExternalActivityLaunched()
        super.startActivity(intent, options)
    }

    /** Lets the incoming-call screen show over the lock screen. */
    private fun applyCallWindowFlags(intent: Intent?) {
        if (intent?.hasExtra(NotificationHelper.EXTRA_CALL_ID) == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun promptBiometric() {
        container.appLock.authenticateBiometric(
            this, getString(R.string.app_lock_title), getString(R.string.app_lock_subtitle), getString(R.string.app_lock_use_pin),
        ) { ok -> if (ok) container.appLock.unlock() }
    }

    private fun promptDeviceCredential() {
        container.appLock.authenticateWithDeviceCredential(
            this, getString(R.string.app_lock_title), getString(R.string.app_lock_subtitle),
            legacyLauncher = { legacyUnlock.launch(it) },
        ) { ok -> if (ok) container.appLock.unlock() }
    }

    @Composable
    private fun AppRoot() {
        val session by container.authRepository.session.collectAsState()
        val locked by container.appLock.locked.collectAsState()
        val settings by container.prefs.settings.collectAsState()
        val call by container.callManager.ui.collectAsState()
        val scope = rememberCoroutineScope()
        val showLock = locked && settings.appLockEnabled && session is SessionState.SignedIn && !call.isLive
        Box(Modifier.fillMaxSize()) {
            // While locked, the app underneath is hidden from accessibility services too.
            Box(if (showLock) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) {
                when (val s = session) {
                    SessionState.Loading -> LoadingBox()
                    is SessionState.SignedIn -> PulseNavHost(container, pendingIntent) { pendingIntent.value = null }
                    else -> AuthRoute(s)
                }
            }
            if (showLock) {
                val biometric = settings.appLockBiometric && container.appLock.biometricAvailable(this@MainActivity)
                LockScreen(
                    hasPin = settings.appLockHasPin,
                    biometric = biometric,
                    onCheckPin = { container.appLock.checkPin(it) },
                    initialWaitSeconds = container.appLock.waitSeconds(),
                    onUnlocked = { container.appLock.unlock() },
                    onBiometric = ::promptBiometric,
                    onDeviceCredential = ::promptDeviceCredential,
                    onForgotPin = { scope.launch { container.authRepository.signOut() } },
                )
            }
        }
    }
}

@Composable
private fun ConfigErrorScreen() {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.config_error_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.config_error_body), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

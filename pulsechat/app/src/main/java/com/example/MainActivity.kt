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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.repository.SessionState
import com.example.notifications.NotificationHelper
import com.example.ui.auth.AuthRoute
import com.example.ui.common.LoadingBox
import com.example.ui.navigation.PulseNavHost
import com.example.ui.theme.PulseChatTheme
import com.example.util.LocaleHelper
import kotlinx.coroutines.flow.MutableStateFlow

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
                    else AppRoot(onUnlock = ::authenticate)
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

    /** Lets the incoming-call screen show over the lock screen. */
    private fun applyCallWindowFlags(intent: Intent?) {
        if (intent?.hasExtra(NotificationHelper.EXTRA_CALL_ID) == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    private fun authenticate() {
        container.appLock.authenticate(
            this, getString(R.string.app_lock_title), getString(R.string.app_lock_subtitle),
            legacyLauncher = { legacyUnlock.launch(it) },
        ) { ok -> if (ok) container.appLock.unlock() }
    }

    @Composable
    private fun AppRoot(onUnlock: () -> Unit) {
        val session by container.authRepository.session.collectAsState()
        val locked by container.appLock.locked.collectAsState()
        val settings by container.prefs.settings.collectAsState()
        Box(Modifier.fillMaxSize()) {
            when (val s = session) {
                SessionState.Loading -> LoadingBox()
                is SessionState.SignedIn -> PulseNavHost(container, pendingIntent) { pendingIntent.value = null }
                else -> AuthRoute(s)
            }
            if (locked && settings.appLockEnabled && session is SessionState.SignedIn) LockOverlay(onUnlock)
        }
    }
}

@Composable
private fun LockOverlay(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
            Text(stringResource(R.string.app_lock_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.app_lock_locked_body), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onUnlock) { Text(stringResource(R.string.app_lock_unlock)) }
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

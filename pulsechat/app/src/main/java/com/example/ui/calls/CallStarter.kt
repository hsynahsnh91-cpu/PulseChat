package com.example.ui.calls

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.R
import com.example.ui.common.appContainer
import kotlinx.coroutines.launch

/**
 * Returns a function that starts a real call after the needed runtime permissions are granted.
 * [onStarted] is invoked once the call is dialling (navigate to the call screen).
 */
@Composable
fun rememberCallStarter(onStarted: () -> Unit): (peerId: String, video: Boolean) -> Unit {
    val context = LocalContext.current
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    fun launchCall(peerId: String, video: Boolean) {
        scope.launch {
            if (container.callManager.startCall(peerId, video)) onStarted()
            else Toast.makeText(context, R.string.call_could_not_start, Toast.LENGTH_LONG).show()
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val request = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (result.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }.values.all { it }) launchCall(request.first, request.second)
        else Toast.makeText(context, R.string.call_permission_needed, Toast.LENGTH_LONG).show()
    }

    return { peerId, video ->
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) launchCall(peerId, video)
        else {
            pending = peerId to video
            launcher.launch(needed.toTypedArray())
        }
    }
}

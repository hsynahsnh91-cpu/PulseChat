package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.ChatViewModel
import com.example.ui.screens.CallOverlay
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.PulseChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PulseChatTheme {
                PulseChatApp()
            }
        }
    }
}

@Composable
fun PulseChatApp(viewModel: ChatViewModel = viewModel()) {
    val selectedConvId by viewModel.selectedConversationId.collectAsState()
    val activeCall by viewModel.activeCall.collectAsState()
    val callDuration by viewModel.callDuration.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val isSpeakerOn by viewModel.isSpeakerOn.collectAsState()
    val isVideoEnabled by viewModel.isVideoEnabled.collectAsState()

    val currentUser by viewModel.currentUser.collectAsState()

    if (currentUser == null) {
        com.example.ui.screens.AuthScreen(viewModel = viewModel)
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            if (selectedConvId != null) {
                ChatDetailScreen(
                    viewModel = viewModel,
                    onBack = { viewModel.closeConversation() }
                )
            } else {
                HomeScreen(
                    viewModel = viewModel,
                    onOpenConversation = { id -> viewModel.selectConversation(id) }
                )
            }

        // Overlay Call Screen whenever an incoming or active call is in progress
        activeCall?.let { call ->
            CallOverlay(
                activeCall = call,
                durationSeconds = callDuration,
                isMuted = isMuted,
                isSpeakerOn = isSpeakerOn,
                isVideoEnabled = isVideoEnabled,
                onAccept = { viewModel.acceptCall() },
                onReject = { viewModel.rejectCall() },
                onEndCall = { viewModel.endCall() },
                onToggleMute = { viewModel.toggleMute() },
                onToggleSpeaker = { viewModel.toggleSpeaker() },
                onToggleVideo = { viewModel.toggleVideo() }
            )
        }
    }
}
}


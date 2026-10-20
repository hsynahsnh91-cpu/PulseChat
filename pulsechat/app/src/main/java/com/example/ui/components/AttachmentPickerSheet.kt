package com.example.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.PrimaryIndigo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPickerSheet(
    onDismiss: () -> Unit,
    onSendPhoto: (uriString: String) -> Unit,
    onSendVideo: (uriString: String) -> Unit,
    onSendDocument: (fileName: String, uriString: String) -> Unit,
    onSendLocation: (lat: Double, lng: Double, address: String) -> Unit,
    onSendPoll: (question: String, options: List<String>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    var showPollDialog by remember { mutableStateOf(false) }

    // Android Photo Picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onSendPhoto(uri.toString())
            onDismiss()
        }
    }

    // Video Picker
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onSendVideo(uri.toString())
            onDismiss()
        }
    }

    // Document Picker
    val docPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast("/") ?: "Document.pdf"
            onSendDocument(name, uri.toString())
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "مشاركة محتوى",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                AttachmentOptionItem(
                    title = "معرض الصور",
                    icon = Icons.Default.Image,
                    color = Color(0xFF8B5CF6),
                    onClick = { photoPickerLauncher.launch("image/*") }
                )
                AttachmentOptionItem(
                    title = "فيديو",
                    icon = Icons.Default.Videocam,
                    color = Color(0xFFEF4444),
                    onClick = { videoPickerLauncher.launch("video/*") }
                )
                AttachmentOptionItem(
                    title = "مستند",
                    icon = Icons.Default.Description,
                    color = Color(0xFF3B82F6),
                    onClick = { docPickerLauncher.launch("*/*") }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                AttachmentOptionItem(
                    title = "الموقع",
                    icon = Icons.Default.LocationOn,
                    color = Color(0xFF10B981),
                    onClick = {
                        // Send mock/real geolocation
                        onSendLocation(33.5138, 36.2765, "دمشق، سوريا")
                        onDismiss()
                    }
                )
                AttachmentOptionItem(
                    title = "استطلاع رأي",
                    icon = Icons.Default.Poll,
                    color = Color(0xFFF59E0B),
                    onClick = {
                        showPollDialog = true
                    }
                )
                // Spacer for symmetry
                Box(modifier = Modifier.size(60.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showPollDialog) {
        CreatePollDialog(
            onDismiss = { showPollDialog = false },
            onCreate = { question, options ->
                onSendPoll(question, options)
                showPollDialog = false
                onDismiss()
            }
        )
    }
}

@Composable
fun AttachmentOptionItem(
    title: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun CreatePollDialog(
    onDismiss: () -> Unit,
    onCreate: (question: String, options: List<String>) -> Unit
) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إنشاء استطلاع رأي", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text("اطرح سؤالاً") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                options.forEachIndexed { index, opt ->
                    OutlinedTextField(
                        value = opt,
                        onValueChange = { options[index] = it },
                        label = { Text("خيار ${index + 1}") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
                if (options.size < 4) {
                    TextButton(onClick = { options.add("") }) {
                        Text("+ إضافة خيار آخر")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val validOpts = options.filter { it.isNotBlank() }
                    if (question.isNotBlank() && validOpts.size >= 2) {
                        onCreate(question, validOpts)
                    }
                },
                enabled = question.isNotBlank() && options.count { it.isNotBlank() } >= 2,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
            ) {
                Text("إرسال")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

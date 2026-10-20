package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import coil.compose.AsyncImage
import com.example.R
import com.example.ui.ChatViewModel
import com.example.ui.theme.PrimaryIndigo
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Login, 1: Register
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Login Fields
    var loginInput by remember { mutableStateOf("") } // username or 10-digit ID
    var loginPassword by remember { mutableStateOf("") }
    var showLoginPassword by remember { mutableStateOf(false) }

    // Register Fields
    var regPhotoUri by remember { mutableStateOf<String?>(null) }
    var regFullName by remember { mutableStateOf("") }
    var regUsername by remember { mutableStateOf("") }
    var regPassword by remember { mutableStateOf("") }
    var regConfirmPassword by remember { mutableStateOf("") }
    var showRegPassword by remember { mutableStateOf(false) }

    // Google Sign-In Custom Username Setup
    var pendingGoogleAccount by remember {
        mutableStateOf<Triple<String, String?, Pair<String?, String?>>?>(null) // googleId, email, Pair(displayName, photoUrl)
    }
    var customGoogleUsername by remember { mutableStateOf("") }
    var googleUsernameError by remember { mutableStateOf<String?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            regPhotoUri = uri.toString()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Clean Branding Header (No emojis)
        Text(
            text = "PulseChat",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = PrimaryIndigo
        )
        Text(
            text = if (selectedTab == 0) stringResource(R.string.login_title) else stringResource(R.string.register_title),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
        )

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0; errorMessage = null },
                        text = { Text(stringResource(R.string.login_title), fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1; errorMessage = null },
                        text = { Text(stringResource(R.string.register_title), fontWeight = FontWeight.SemiBold) }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                if (selectedTab == 0) {
                    // LOGIN FORM
                    OutlinedTextField(
                        value = loginInput,
                        onValueChange = { loginInput = it },
                        label = { Text(stringResource(R.string.username_or_id)) },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_username_id_field"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = loginPassword,
                        onValueChange = { loginPassword = it },
                        label = { Text(stringResource(R.string.password)) },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        visualTransformation = if (showLoginPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showLoginPassword = !showLoginPassword }) {
                                Icon(
                                    imageVector = if (showLoginPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = null
                                )
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_password_field"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            isLoading = true
                            errorMessage = null
                            scope.launch {
                                val result = viewModel.authRepo.login(loginInput, loginPassword)
                                isLoading = false
                                result.onFailure {
                                    errorMessage = it.localizedMessage ?: "فشل تسجيل الدخول"
                                }
                            }
                        },
                        enabled = !isLoading && loginInput.isNotBlank() && loginPassword.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("login_submit_button")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                        } else {
                            Text(stringResource(R.string.login_title), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    // REGISTER FORM
                    // Profile Picture with WhatsApp-style placeholder
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .align(Alignment.CenterHorizontally)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { photoPickerLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (!regPhotoUri.isNullOrEmpty()) {
                            AsyncImage(
                                model = regPhotoUri,
                                contentDescription = "الصورة المختارة",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.AddAPhoto,
                                contentDescription = "إضافة صورة",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.profile_photo),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 4.dp, bottom = 14.dp)
                    )

                    OutlinedTextField(
                        value = regFullName,
                        onValueChange = { regFullName = it },
                        label = { Text(stringResource(R.string.full_name)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reg_fullname_field"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = regUsername,
                        onValueChange = { regUsername = it },
                        label = { Text(stringResource(R.string.unique_username)) },
                        placeholder = { Text("مثال: user_99") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reg_username_field"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = regPassword,
                        onValueChange = { regPassword = it },
                        label = { Text(stringResource(R.string.password)) },
                        visualTransformation = if (showRegPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showRegPassword = !showRegPassword }) {
                                Icon(
                                    imageVector = if (showRegPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = null
                                )
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reg_password_field"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = regConfirmPassword,
                        onValueChange = { regConfirmPassword = it },
                        label = { Text(stringResource(R.string.confirm_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reg_confirm_password_field"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = {
                            isLoading = true
                            errorMessage = null
                            scope.launch {
                                val result = viewModel.authRepo.register(
                                    displayName = regFullName,
                                    username = regUsername,
                                    password = regPassword,
                                    confirmPassword = regConfirmPassword,
                                    avatarUrl = regPhotoUri
                                )
                                isLoading = false
                                result.onFailure {
                                    errorMessage = it.localizedMessage ?: "فشل إنشاء الحساب"
                                }
                            }
                        },
                        enabled = !isLoading && regFullName.isNotBlank() && regUsername.isNotBlank() && regPassword.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("register_submit_button")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                        } else {
                            Text(stringResource(R.string.register_title), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Divider(modifier = Modifier.weight(1f))
                    Text(
                        text = " أو ",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Divider(modifier = Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Google Sign-In Button via Credential Manager
                OutlinedButton(
                    onClick = {
                        isLoading = true
                        errorMessage = null
                        scope.launch {
                            try {
                                val credentialManager = CredentialManager.create(context)
                                val googleOption = GetSignInWithGoogleOption.Builder(
                                    context.getString(R.string.default_web_client_id)
                                ).build()
                                val request = GetCredentialRequest.Builder()
                                    .addCredentialOption(googleOption)
                                    .build()
                                val response = credentialManager.getCredential(context, request)
                                // Parse credential stably
                                val credential = response.credential
                                var googleId = "google_user"
                                var googleEmail: String? = null
                                var displayName: String? = null
                                var photoUrl: String? = null

                                try {
                                    val tokenCred = GoogleIdTokenCredential.createFrom(credential.data)
                                    googleId = tokenCred.id
                                    googleEmail = tokenCred.id
                                    displayName = tokenCred.displayName
                                    photoUrl = tokenCred.profilePictureUri?.toString()
                                } catch (e: Exception) {
                                    val bundleId = credential.data.getString("androidx.credentials.BUNDLE_KEY_ID")
                                    googleId = bundleId ?: credential.data.getString("id") ?: "google_user"
                                    googleEmail = googleId
                                    displayName = credential.data.getString("androidx.credentials.BUNDLE_KEY_NAME")
                                    photoUrl = credential.data.getString("androidx.credentials.BUNDLE_KEY_PICTURE_URL")
                                }

                                // Check if user already exists (session preservation)
                                val existingUser = viewModel.authRepo.checkExistingGoogleUser(googleId)
                                if (existingUser != null) {
                                    viewModel.authRepo.loginExistingGoogleUser(existingUser)
                                } else {
                                    // New Google User: prompt for custom username before entering app
                                    val suggested = (displayName?.lowercase()?.replace(" ", "_") ?: "user_${googleId.take(4)}")
                                        .filter { it.isLetterOrDigit() || it == '_' }
                                    customGoogleUsername = suggested
                                    googleUsernameError = null
                                    pendingGoogleAccount = Triple(googleId, googleEmail, Pair(displayName, photoUrl))
                                }
                            } catch (e: GetCredentialCancellationException) {
                                // Cancelled by user
                            } catch (e: Exception) {
                                errorMessage = "Google Sign-In: ${e.localizedMessage}"
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("google_signin_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.sign_in_google),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    // Custom Username Selection Dialog for Google Sign-In
    val pending = pendingGoogleAccount
    if (pending != null) {
        val googleId = pending.first
        val email = pending.second
        val displayName = pending.third.first
        val photoUrl = pending.third.second

        AlertDialog(
            onDismissRequest = { pendingGoogleAccount = null },
            title = {
                Text(
                    text = "تعيين اسم المستخدم الخاص بك",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!photoUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = photoUrl,
                            contentDescription = "صورة Google",
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text(
                        text = displayName ?: "مستخدم Google",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "مرحباً بك! يرجى اختيار اسم مستخدم فريد ومخصص لحسابك على PulseChat:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    OutlinedTextField(
                        value = customGoogleUsername,
                        onValueChange = {
                            customGoogleUsername = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '_' }
                            googleUsernameError = null
                        },
                        label = { Text("اسم المستخدم المخصص") },
                        placeholder = { Text("مثال: username_12") },
                        singleLine = true,
                        isError = googleUsernameError != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("google_custom_username_field"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    if (googleUsernameError != null) {
                        Text(
                            text = googleUsernameError.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = customGoogleUsername.trim().lowercase().removePrefix("@")
                        if (clean.length < 3) {
                            googleUsernameError = "اسم المستخدم يجب أن يكون 3 أحرف على الأقل"
                            return@Button
                        }
                        isLoading = true
                        scope.launch {
                            val result = viewModel.authRepo.registerGoogleUser(
                                googleId = googleId,
                                email = email,
                                displayName = displayName,
                                photoUrl = photoUrl,
                                customUsername = clean
                            )
                            isLoading = false
                            result.onSuccess {
                                pendingGoogleAccount = null
                            }.onFailure { err ->
                                googleUsernameError = err.localizedMessage ?: "فشل تسجيل اسم المستخدم"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                    enabled = customGoogleUsername.isNotBlank() && !isLoading,
                    modifier = Modifier.testTag("google_username_confirm_button")
                ) {
                    Text("تأكيد والمتابعة", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingGoogleAccount = null }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

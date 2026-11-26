package com.mychattingpro.features.profile

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.mychattingpro.core.network.NetworkModule
import com.mychattingpro.features.common.ProfileAvatarPreviewDialog
import com.mychattingpro.features.common.UserAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    networkModule: NetworkModule,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler {
        if (state.isEditMode) {
            viewModel.cancelEditMode()
        } else {
            onBack()
        }
    }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.uploadNewAvatar(it) }
    }

    LaunchedEffect(state.savedSuccess) {
        if (state.savedSuccess) {
            Toast.makeText(
                context,
                context.getString(R.string.profile_updated_success),
                Toast.LENGTH_SHORT
            ).show()
            viewModel.consumeSavedNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (state.isEditMode) {
                            stringResource(R.string.action_edit_profile)
                        } else {
                            stringResource(R.string.profile_title)
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                if (state.isEditMode) {
                                    viewModel.cancelEditMode()
                                } else {
                                    onBack()
                                }
                            },
                            modifier = Modifier.testTag("profile_back_button")
                        ) {
                            Icon(
                                imageVector = if (state.isEditMode) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                        // Profile Edit Toggle placed EXCLUSIVELY at Top-Left of ProfileScreen
                        if (!state.isEditMode && !state.isInitialLoading && !state.loadFailed) {
                            IconButton(
                                onClick = viewModel::enterEditMode,
                                modifier = Modifier.testTag("profile_edit_toggle_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = stringResource(R.string.action_edit_profile),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (state.isInitialLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.testTag("profile_loading_indicator"))
            }
            return@Scaffold
        }

        if (state.loadFailed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = state.error?.toLocalizedMessage(context)
                            ?: stringResource(R.string.profile_load_failed),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(
                        onClick = viewModel::loadProfile,
                        modifier = Modifier.testTag("profile_retry_button")
                    ) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 520.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                var showOwnAvatarPreview by remember { mutableStateOf(false) }
                val launchAvatarPicker = {
                    if (!state.isUploadingPhoto) {
                        avatarPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                }
                Box(contentAlignment = Alignment.BottomEnd) {
                    UserAvatar(
                        displayName = state.displayName.ifBlank { "M" },
                        avatarMediaId = state.avatarMediaId,
                        networkModule = networkModule,
                        size = 112.dp,
                        onClick = {
                            if (!state.avatarMediaId.isNullOrBlank()) {
                                showOwnAvatarPreview = true
                            } else {
                                launchAvatarPicker()
                            }
                        }
                    )
                    if (showOwnAvatarPreview) {
                        ProfileAvatarPreviewDialog(
                            displayName = state.displayName.ifBlank { stringResource(R.string.app_name) },
                            avatarMediaId = state.avatarMediaId,
                            networkModule = networkModule,
                            onDismiss = { showOwnAvatarPreview = false }
                        )
                    }
                    if (state.isUploadingPhoto) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    } else {
                        FilledIconButton(
                            onClick = launchAvatarPicker,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("profile_change_photo_button")
                        ) {
                            Icon(
                                Icons.Rounded.CameraAlt,
                                contentDescription = stringResource(R.string.action_change_photo),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (!state.isEditMode) {
                    ElevatedCard(
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile_readonly_card")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            ProfileReadOnlyRow(
                                label = stringResource(R.string.label_display_name),
                                value = state.displayName.ifBlank { stringResource(R.string.app_name) }
                            )
                            HorizontalDivider()
                            ProfileReadOnlyRow(
                                label = stringResource(R.string.label_about),
                                value = state.about.ifBlank { stringResource(R.string.hint_about) }
                            )
                            HorizontalDivider()
                            ProfileReadOnlyRow(
                                label = stringResource(R.string.label_phone_number),
                                value = state.phoneE164.ifBlank { "-" }
                            )
                        }
                    }
                } else {
                    // Phone number is strictly read-only per specification
                    OutlinedTextField(
                        value = state.phoneE164,
                        onValueChange = {},
                        readOnly = true,
                        enabled = false,
                        label = { Text(stringResource(R.string.label_phone_number)) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = state.displayName,
                        onValueChange = viewModel::updateDisplayName,
                        label = { Text(stringResource(R.string.label_display_name)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile_display_name_input")
                    )

                    OutlinedTextField(
                        value = state.about,
                        onValueChange = viewModel::updateAbout,
                        label = { Text(stringResource(R.string.label_about)) },
                        placeholder = { Text(stringResource(R.string.hint_about)) },
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile_about_input")
                    )

                    Button(
                        onClick = viewModel::saveProfile,
                        enabled = !state.isLoading && state.displayName.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("profile_save_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(stringResource(R.string.action_save))
                        }
                    }
                }

                state.error?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = err.toLocalizedMessage(context),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            TextButton(
                                onClick = viewModel::loadProfile,
                                modifier = Modifier.testTag("profile_retry_button")
                            ) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileReadOnlyRow(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

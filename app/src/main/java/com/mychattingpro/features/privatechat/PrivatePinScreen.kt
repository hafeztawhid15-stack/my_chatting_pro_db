package com.mychattingpro.features.privatechat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockReset
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.mychattingpro.features.common.FiveDigitPinField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivatePinScreen(
    viewModel: PrivateChatViewModel,
    onBack: () -> Unit,
    onUnlocked: () -> Unit
) {
    val state by viewModel.pinState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        viewModel.preparePinEntry()
    }

    LaunchedEffect(state.unlockedSuccessfully) {
        if (state.unlockedSuccessfully) {
            viewModel.consumeUnlockedNavigation()
            onUnlocked()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.private_chat_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("private_pin_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when (state.mode) {
                                PinGateMode.RESET -> Icons.Rounded.LockReset
                                PinGateMode.SETUP -> Icons.Rounded.Shield
                                else -> Icons.Rounded.Lock
                            },
                            contentDescription = stringResource(R.string.private_chat_title),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                when (state.mode) {
                    PinGateMode.LOADING -> {
                        if (state.statusLoadFailed) {
                            Text(
                                text = state.error?.toLocalizedMessage(context)
                                    ?: stringResource(R.string.err_server),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = { viewModel.loadPinStatus() },
                                modifier = Modifier.testTag("private_pin_retry_button")
                            ) {
                                Text(stringResource(R.string.action_retry))
                            }
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        }
                    }

                    PinGateMode.SETUP -> {
                        Text(
                            text = stringResource(R.string.private_pin_setup_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.private_pin_setup_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = stringResource(R.string.label_create_pin),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FiveDigitPinField(
                            pin = state.primaryPinInput,
                            onPinChange = viewModel::updatePrimaryPin,
                            testTag = "private_pin_input"
                        )

                        Text(
                            text = stringResource(R.string.label_confirm_pin),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FiveDigitPinField(
                            pin = state.confirmPinInput,
                            onPinChange = viewModel::updateConfirmPin,
                            testTag = "private_pin_confirm_input"
                        )

                        PrivatePinErrorBanner(errorMessage = state.error?.toLocalizedMessage(context))

                        Button(
                            onClick = { viewModel.submitSetup() },
                            enabled = !state.isSubmitting,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("private_pin_submit_button")
                        ) {
                            if (state.isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(stringResource(R.string.action_confirm))
                            }
                        }
                    }

                    PinGateMode.UNLOCK -> {
                        Text(
                            text = stringResource(R.string.private_pin_unlock_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.private_pin_unlock_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        FiveDigitPinField(
                            pin = state.primaryPinInput,
                            onPinChange = viewModel::updatePrimaryPin,
                            testTag = "private_pin_input"
                        )

                        val activeErrorText = if (state.lockoutRemainingSeconds > 0) {
                            stringResource(R.string.err_private_pin_locked, state.lockoutRemainingSeconds)
                        } else {
                            state.error?.toLocalizedMessage(context)
                        }
                        PrivatePinErrorBanner(errorMessage = activeErrorText)

                        Button(
                            onClick = { viewModel.submitUnlock() },
                            enabled = !state.isSubmitting && state.lockoutRemainingSeconds <= 0,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("private_pin_submit_button")
                        ) {
                            if (state.isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(stringResource(R.string.action_continue))
                            }
                        }

                        TextButton(
                            enabled = !state.isSubmitting,
                            onClick = { viewModel.switchToResetMode() },
                            modifier = Modifier.testTag("private_pin_forgot_button")
                        ) {
                            Text(stringResource(R.string.private_pin_forgot_btn))
                        }
                    }

                    PinGateMode.RESET -> {
                        Text(
                            text = stringResource(R.string.private_pin_reset_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.private_pin_reset_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = stringResource(R.string.private_pin_enter_account_login_pin),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FiveDigitPinField(
                            pin = state.loginPinInput,
                            onPinChange = viewModel::updateLoginPin,
                            testTag = "private_pin_login_input"
                        )

                        Text(
                            text = stringResource(R.string.private_pin_new_label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FiveDigitPinField(
                            pin = state.primaryPinInput,
                            onPinChange = viewModel::updatePrimaryPin,
                            testTag = "private_pin_new_input"
                        )

                        Text(
                            text = stringResource(R.string.private_pin_confirm_label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FiveDigitPinField(
                            pin = state.confirmPinInput,
                            onPinChange = viewModel::updateConfirmPin,
                            testTag = "private_pin_confirm_input"
                        )

                        PrivatePinErrorBanner(errorMessage = state.error?.toLocalizedMessage(context))

                        Button(
                            onClick = { viewModel.submitReset() },
                            enabled = !state.isSubmitting,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("private_pin_reset_submit_button")
                        ) {
                            if (state.isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(stringResource(R.string.action_save))
                            }
                        }

                        TextButton(
                            enabled = !state.isSubmitting,
                            onClick = { viewModel.switchBackToUnlockMode() },
                            modifier = Modifier.testTag("private_pin_back_to_unlock_button")
                        ) {
                            Text(stringResource(R.string.action_back))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivatePinErrorBanner(errorMessage: String?) {
    if (errorMessage.isNullOrBlank()) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("private_pin_error_banner")
    ) {
        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

package com.mychattingpro.features.auth

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.mychattingpro.core.common.AppError
import com.mychattingpro.features.common.FiveDigitPinField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit,
    onSwitchToCreateAccount: () -> Unit = {},
    onCreateAccountWithPhone: () -> Unit = onSwitchToCreateAccount,
    onForgotPin: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val recentIsos by viewModel.recentCountryIsos.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCountryPicker by remember { mutableStateOf(false) }

    BackHandler {
        if (!viewModel.previousLoginStep()) {
            onBack()
        }
    }

    if (showCountryPicker) {
        CountryPickerSheet(
            recentIsoCodes = recentIsos,
            onSelect = viewModel::selectCountry,
            onDismiss = { showCountryPicker = false }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = { Text(stringResource(R.string.action_login), fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (!viewModel.previousLoginStep()) onBack()
                            },
                            modifier = Modifier.testTag("login_back_button")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    AnimatedContent(
                        targetState = state.loginStep,
                        transitionSpec = {
                            if (targetState > initialState) {
                                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                                    (slideOutHorizontally { -it / 3 } + fadeOut())
                            } else {
                                (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                                    (slideOutHorizontally { it / 3 } + fadeOut())
                            }
                        },
                        label = "login_step_transition"
                    ) { step ->
                        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            when (step) {
                                1 -> {
                                    AuthStepProgressHeader(
                                        currentStep = 1,
                                        totalSteps = 2,
                                        title = stringResource(R.string.auth_login_step1_title),
                                        subtitle = stringResource(R.string.auth_login_step1_subtitle),
                                        icon = Icons.Rounded.PhoneAndroid
                                    )

                                    AuthPhoneEntryBlock(
                                        selectedCountry = state.selectedCountry,
                                        phoneInput = state.phoneInput,
                                        onOpenCountryPicker = { showCountryPicker = true },
                                        onPhoneChange = viewModel::updatePhone,
                                        onSubmitIme = viewModel::advanceLoginStep,
                                        countryTestTag = "login_country_selector",
                                        phoneTestTag = "login_phone_input"
                                    )

                                    AuthErrorBanner(state.error?.toLocalizedMessage(context))

                                    if (state.error is AppError.AccountNotFound) {
                                        FilledTonalButton(
                                            onClick = onCreateAccountWithPhone,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(50.dp)
                                                .testTag("login_missing_create_account_btn"),
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.action_create_account),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = viewModel::advanceLoginStep,
                                        enabled = !state.isLoading,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(54.dp)
                                            .testTag("login_step1_next"),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        if (state.isLoading) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(22.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        } else {
                                            Text(
                                                text = stringResource(R.string.action_continue),
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                        }
                                    }
                                }

                                else -> {
                                    AuthStepProgressHeader(
                                        currentStep = 2,
                                        totalSteps = 2,
                                        title = stringResource(R.string.auth_login_step2_title),
                                        subtitle = stringResource(R.string.auth_login_step2_subtitle),
                                        icon = Icons.Rounded.Lock
                                    )

                                    AuthSummaryPill(
                                        flagEmoji = state.selectedCountry.flagEmoji,
                                        formattedPhone = viewModel.formattedPhonePreview(),
                                        onChangePhone = { viewModel.previousLoginStep() },
                                        changeTestTag = "login_change_phone_button"
                                    )

                                    FiveDigitPinField(
                                        pin = state.pin,
                                        onPinChange = viewModel::updatePin,
                                        testTag = "login_pin_input",
                                        imeAction = ImeAction.Done,
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                if (!state.isLoading) viewModel.login(onLoginSuccess)
                                            }
                                        )
                                    )

                                    AuthErrorBanner(state.error?.toLocalizedMessage(context))

                                    Button(
                                        onClick = { viewModel.login(onLoginSuccess) },
                                        enabled = !state.isLoading,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(54.dp)
                                            .testTag("login_submit_button"),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        if (state.isLoading) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(22.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        } else {
                                            Text(
                                                text = stringResource(R.string.action_login),
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                        }
                                    }

                                    TextButton(
                                        onClick = onForgotPin,
                                        modifier = Modifier
                                            .align(Alignment.CenterHorizontally)
                                            .testTag("login_forgot_pin_button")
                                    ) {
                                        Text(stringResource(R.string.login_forgot_pin_link))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    TextButton(
                        onClick = onSwitchToCreateAccount,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .testTag("login_switch_to_create")
                    ) {
                        Text(stringResource(R.string.auth_switch_to_create))
                    }

                    AuthNoOtpNotice()
                }
            }
        }
    }
}

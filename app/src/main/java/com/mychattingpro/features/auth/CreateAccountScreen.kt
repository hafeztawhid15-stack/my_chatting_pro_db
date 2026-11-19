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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.mychattingpro.features.common.FiveDigitPinField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAccountScreen(
    viewModel: AuthViewModel,
    onBack: () -> Unit,
    onAccountCreated: () -> Unit,
    onSwitchToLogin: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val recentIsos by viewModel.recentCountryIsos.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCountryPicker by remember { mutableStateOf(false) }

    BackHandler {
        if (!viewModel.previousCreateStep()) {
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
                    title = {
                        Text(
                            stringResource(R.string.action_create_account),
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (!viewModel.previousCreateStep()) onBack()
                            },
                            modifier = Modifier.testTag("create_back_button")
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
                        targetState = state.createStep,
                        transitionSpec = {
                            if (targetState > initialState) {
                                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                                    (slideOutHorizontally { -it / 3 } + fadeOut())
                            } else {
                                (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                                    (slideOutHorizontally { it / 3 } + fadeOut())
                            }
                        },
                        label = "create_step_transition"
                    ) { step ->
                        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            when (step) {
                                1 -> {
                                    AuthStepProgressHeader(
                                        currentStep = 1,
                                        totalSteps = 4,
                                        title = stringResource(R.string.auth_create_step1_title),
                                        subtitle = stringResource(R.string.auth_create_step1_subtitle),
                                        icon = Icons.Rounded.PhoneAndroid
                                    )

                                    AuthPhoneEntryBlock(
                                        selectedCountry = state.selectedCountry,
                                        phoneInput = state.phoneInput,
                                        onOpenCountryPicker = { showCountryPicker = true },
                                        onPhoneChange = viewModel::updatePhone,
                                        onSubmitIme = viewModel::advanceCreateStep,
                                        countryTestTag = "create_country_selector",
                                        phoneTestTag = "create_phone_input"
                                    )

                                    AuthErrorBanner(state.error?.toLocalizedMessage(context))

                                    Button(
                                        onClick = viewModel::advanceCreateStep,
                                        enabled = !state.isLoading,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(54.dp)
                                            .testTag("create_step1_next"),
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

                                2 -> {
                                    AuthStepProgressHeader(
                                        currentStep = 2,
                                        totalSteps = 4,
                                        title = stringResource(R.string.auth_create_step2_title),
                                        subtitle = stringResource(R.string.auth_create_step2_subtitle),
                                        icon = Icons.Rounded.Person
                                    )

                                    AuthSummaryPill(
                                        flagEmoji = state.selectedCountry.flagEmoji,
                                        formattedPhone = viewModel.formattedPhonePreview(),
                                        onChangePhone = { viewModel.jumpCreateToPhoneStep() },
                                        changeTestTag = "create_change_phone_button"
                                    )

                                    OutlinedTextField(
                                        value = state.displayName,
                                        onValueChange = viewModel::updateDisplayName,
                                        label = { Text(stringResource(R.string.label_display_name)) },
                                        placeholder = { Text(stringResource(R.string.hint_display_name)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Rounded.Person,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        keyboardOptions = KeyboardOptions(
                                            capitalization = KeyboardCapitalization.Words,
                                            imeAction = ImeAction.Next
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onNext = { viewModel.advanceCreateStep() }
                                        ),
                                        singleLine = true,
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("create_display_name_input")
                                    )

                                    AuthErrorBanner(state.error?.toLocalizedMessage(context))

                                    Button(
                                        onClick = viewModel::advanceCreateStep,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(54.dp)
                                            .testTag("create_step2_next"),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.action_continue),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                }

                                3 -> {
                                    AuthStepProgressHeader(
                                        currentStep = 3,
                                        totalSteps = 4,
                                        title = stringResource(R.string.auth_create_step3_title),
                                        subtitle = stringResource(R.string.auth_create_step3_subtitle),
                                        icon = Icons.Rounded.Lock
                                    )

                                    AuthSummaryPill(
                                        flagEmoji = state.selectedCountry.flagEmoji,
                                        formattedPhone = viewModel.formattedPhonePreview(),
                                        displayName = state.displayName.takeIf { it.isNotBlank() }
                                    )

                                    FiveDigitPinField(
                                        pin = state.pin,
                                        onPinChange = viewModel::updatePin,
                                        testTag = "create_pin_input",
                                        imeAction = ImeAction.Next,
                                        keyboardActions = KeyboardActions(
                                            onNext = { viewModel.advanceCreateStep() }
                                        )
                                    )

                                    AuthErrorBanner(state.error?.toLocalizedMessage(context))

                                    Button(
                                        onClick = viewModel::advanceCreateStep,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(54.dp)
                                            .testTag("create_step3_next"),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.action_continue),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                }

                                else -> {
                                    AuthStepProgressHeader(
                                        currentStep = 4,
                                        totalSteps = 4,
                                        title = stringResource(R.string.auth_create_step4_title),
                                        subtitle = stringResource(R.string.auth_create_step4_subtitle),
                                        icon = Icons.Rounded.VerifiedUser
                                    )

                                    AuthSummaryPill(
                                        flagEmoji = state.selectedCountry.flagEmoji,
                                        formattedPhone = viewModel.formattedPhonePreview(),
                                        displayName = state.displayName.takeIf { it.isNotBlank() },
                                        onChangePhone = { viewModel.jumpCreateToPhoneStep() },
                                        changeTestTag = "create_change_phone_button"
                                    )

                                    FiveDigitPinField(
                                        pin = state.confirmPin,
                                        onPinChange = viewModel::updateConfirmPin,
                                        testTag = "create_confirm_pin_input",
                                        imeAction = ImeAction.Done,
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                if (!state.isLoading) {
                                                    viewModel.createAccount(onAccountCreated)
                                                }
                                            }
                                        )
                                    )

                                    AuthErrorBanner(state.error?.toLocalizedMessage(context))

                                    Button(
                                        onClick = { viewModel.createAccount(onAccountCreated) },
                                        enabled = !state.isLoading,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(54.dp)
                                            .testTag("create_submit_button"),
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
                                                text = stringResource(R.string.action_create_account),
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    TextButton(
                        onClick = onSwitchToLogin,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .testTag("create_switch_to_login")
                    ) {
                        Text(stringResource(R.string.auth_switch_to_login))
                    }

                    AuthNoOtpNotice()
                }
            }
        }
    }
}

package com.mychattingpro.features.auth

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.SimCardAlert
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.mychattingpro.core.security.SimCheckResult
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

    val simPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshSimVerification(autoFillIfBlank = true)
    }

    LaunchedEffect(Unit) {
        viewModel.refreshSimVerification(autoFillIfBlank = true)
    }

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

                                    SimVerificationStatusCard(
                                        simStatus = state.simStatus,
                                        onRequestPermission = {
                                            simPermissionLauncher.launch(viewModel.simRequiredPermissions())
                                        },
                                        onRefreshSim = {
                                            viewModel.refreshSimVerification(autoFillIfBlank = true)
                                        },
                                        onSelectVerifiedNumber = viewModel::selectVerifiedSimNumber
                                    )

                                    AuthPhoneEntryBlock(
                                        selectedCountry = state.selectedCountry,
                                        phoneInput = state.phoneInput,
                                        onOpenCountryPicker = { showCountryPicker = true },
                                        onPhoneChange = viewModel::updatePhone,
                                        onSubmitIme = {
                                            if (state.simStatus is SimCheckResult.PermissionRequired) {
                                                simPermissionLauncher.launch(viewModel.simRequiredPermissions())
                                            } else {
                                                viewModel.advanceCreateStep()
                                            }
                                        },
                                        countryTestTag = "create_country_selector",
                                        phoneTestTag = "create_phone_input"
                                    )

                                    AuthErrorBanner(state.error?.toLocalizedMessage(context))

                                    Button(
                                        onClick = {
                                            if (state.simStatus is SimCheckResult.PermissionRequired) {
                                                simPermissionLauncher.launch(viewModel.simRequiredPermissions())
                                            } else {
                                                viewModel.advanceCreateStep()
                                            }
                                        },
                                        enabled = !state.isLoading && state.simStatus !is SimCheckResult.NoSim,
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
                                                text = if (state.simStatus is SimCheckResult.PermissionRequired) {
                                                    stringResource(R.string.sim_verify_grant_btn)
                                                } else {
                                                    stringResource(R.string.action_continue)
                                                },
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

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.sim_verify_no_otp_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimVerificationStatusCard(
    simStatus: SimCheckResult,
    onRequestPermission: () -> Unit,
    onRefreshSim: () -> Unit,
    onSelectVerifiedNumber: (String) -> Unit
) {
    val containerColor = when (simStatus) {
        is SimCheckResult.VerifiedWithNumbers,
        is SimCheckResult.VerifiedCarrierOnly -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        SimCheckResult.NoSim -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f)
        SimCheckResult.PermissionRequired -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
    }
    val contentColor = when (simStatus) {
        is SimCheckResult.VerifiedWithNumbers,
        is SimCheckResult.VerifiedCarrierOnly -> MaterialTheme.colorScheme.onPrimaryContainer
        SimCheckResult.NoSim -> MaterialTheme.colorScheme.onErrorContainer
        SimCheckResult.PermissionRequired -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("create_sim_status_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (simStatus is SimCheckResult.NoSim) Icons.Rounded.SimCardAlert else Icons.Rounded.SimCard,
                    contentDescription = null,
                    tint = contentColor
                )
                Text(
                    text = stringResource(R.string.sim_verify_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    modifier = Modifier.weight(1f)
                )
            }

            when (simStatus) {
                is SimCheckResult.PermissionRequired -> {
                    Text(
                        text = stringResource(R.string.sim_verify_required_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor
                    )
                    FilledTonalButton(
                        onClick = onRequestPermission,
                        modifier = Modifier.testTag("create_verify_sim_button")
                    ) {
                        Icon(Icons.Rounded.SimCard, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.sim_verify_grant_btn))
                    }
                }
                is SimCheckResult.NoSim -> {
                    Text(
                        text = stringResource(R.string.sim_verify_no_sim),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor
                    )
                    TextButton(
                        onClick = onRefreshSim,
                        modifier = Modifier.testTag("create_refresh_sim_button")
                    ) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
                is SimCheckResult.VerifiedWithNumbers -> {
                    Text(
                        text = stringResource(
                            R.string.sim_verify_detected_number,
                            simStatus.numbersE164.joinToString(", ")
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        simStatus.numbersE164.forEach { num ->
                            AssistChip(
                                onClick = { onSelectVerifiedNumber(num) },
                                label = { Text(stringResource(R.string.sim_verify_use_number, num)) },
                                modifier = Modifier.testTag("create_use_sim_chip_$num")
                            )
                        }
                    }
                }
                is SimCheckResult.VerifiedCarrierOnly -> {
                    val carrier = simStatus.carriers.firstOrNull().orEmpty().ifBlank { "SIM" }
                    val iso = simStatus.countryIsos.firstOrNull().orEmpty().uppercase().ifBlank { "SIM" }
                    Text(
                        text = stringResource(R.string.sim_verify_carrier_only, carrier, iso),
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor
                    )
                }
            }
        }
    }
}

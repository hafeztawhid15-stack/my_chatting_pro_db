package com.mychattingpro.features.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LockReset
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.mychattingpro.core.common.CountryCatalog
import com.mychattingpro.features.auth.CountryPickerSheet
import com.mychattingpro.features.common.FiveDigitPinField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeLoginPinScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        viewModel.clearPinSecurityFeedback()
    }

    LaunchedEffect(uiState.pinSecurityNoticeResId) {
        val noticeId = uiState.pinSecurityNoticeResId ?: return@LaunchedEffect
        Toast.makeText(context, context.getString(noticeId), Toast.LENGTH_LONG).show()
        viewModel.clearPinSecurityFeedback()
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.change_login_pin_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("change_login_pin_back")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.change_login_pin_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(stringResource(R.string.change_login_pin_current), fontWeight = FontWeight.SemiBold)
                FiveDigitPinField(
                    pin = currentPin,
                    onPinChange = {
                        currentPin = it
                        viewModel.clearPinSecurityFeedback()
                    },
                    testTag = "change_login_pin_current"
                )

                Text(stringResource(R.string.change_login_pin_new), fontWeight = FontWeight.SemiBold)
                FiveDigitPinField(
                    pin = newPin,
                    onPinChange = {
                        newPin = it
                        viewModel.clearPinSecurityFeedback()
                    },
                    testTag = "change_login_pin_new"
                )

                Text(stringResource(R.string.change_login_pin_confirm), fontWeight = FontWeight.SemiBold)
                FiveDigitPinField(
                    pin = confirmPin,
                    onPinChange = {
                        confirmPin = it
                        viewModel.clearPinSecurityFeedback()
                    },
                    testTag = "change_login_pin_confirm"
                )

                uiState.pinSecurityError?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = err.toLocalizedMessage(context),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Button(
                    onClick = { viewModel.changeLoginPin(currentPin, newPin, confirmPin) },
                    enabled = !uiState.isPinSecurityBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("change_login_pin_submit"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (uiState.isPinSecurityBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryCodeScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var currentPin by remember { mutableStateOf("") }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        viewModel.clearPinSecurityFeedback()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recovery_code_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("recovery_code_back")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (prefs.recoveryCodeConfigured) {
                                    stringResource(R.string.recovery_code_status_active)
                                } else {
                                    stringResource(R.string.recovery_code_status_missing)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = stringResource(R.string.recovery_code_warning_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                uiState.generatedRecoveryCode?.let { code ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.recovery_code_your_code),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = code,
                                style = MaterialTheme.typography.headlineSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("generated_recovery_code_text")
                            )
                            OutlinedButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Recovery Code", code))
                                    Toast.makeText(context, context.getString(R.string.recovery_code_copied), Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.testTag("copy_recovery_code_button")
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.recovery_code_copy_btn))
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.recovery_code_enter_pin_label),
                    fontWeight = FontWeight.SemiBold
                )
                FiveDigitPinField(
                    pin = currentPin,
                    onPinChange = {
                        currentPin = it
                        viewModel.clearPinSecurityFeedback()
                    },
                    testTag = "recovery_code_pin_input"
                )

                uiState.pinSecurityError?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = err.toLocalizedMessage(context),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Button(
                    onClick = { viewModel.generateRecoveryCode(currentPin) },
                    enabled = !uiState.isPinSecurityBusy && currentPin.length == 5,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("generate_recovery_code_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (uiState.isPinSecurityBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Rounded.Key, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (prefs.recoveryCodeConfigured) stringResource(R.string.recovery_code_regenerate_btn)
                            else stringResource(R.string.recovery_code_generate_btn)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotLoginPinScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onResetSuccess: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val recentIsos by viewModel.recentCountryIsos.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedCountry by remember { mutableStateOf(CountryCatalog.findByIso("BD")) }
    var showCountryPicker by remember { mutableStateOf(false) }
    var phoneInput by remember { mutableStateOf("") }
    var recoveryCode by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        viewModel.clearPinSecurityFeedback()
    }

    LaunchedEffect(uiState.resetPinSuccess) {
        if (uiState.resetPinSuccess) {
            Toast.makeText(
                context,
                context.getString(R.string.login_pin_reset_success),
                Toast.LENGTH_LONG
            ).show()
            viewModel.clearPinSecurityFeedback()
            onResetSuccess()
        }
    }

    if (showCountryPicker) {
        CountryPickerSheet(
            recentIsoCodes = recentIsos,
            onSelect = {
                selectedCountry = it
                viewModel.recordRecentCountry(it.iso2)
            },
            onDismiss = { showCountryPicker = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.forgot_login_pin_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("forgot_login_pin_back")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.forgot_login_pin_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCountryPicker = true }
                        .testTag("forgot_pin_country_selector"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(selectedCountry.flagEmoji, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "${selectedCountry.name} (${selectedCountry.callingCode})",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                    }
                }

                OutlinedTextField(
                    value = phoneInput,
                    onValueChange = {
                        phoneInput = it
                        viewModel.clearPinSecurityFeedback()
                    },
                    label = { Text(stringResource(R.string.label_phone_number)) },
                    placeholder = { Text(stringResource(R.string.hint_phone_number)) },
                    prefix = { Text("${selectedCountry.callingCode} ", fontWeight = FontWeight.SemiBold) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("forgot_pin_phone_input")
                )

                OutlinedTextField(
                    value = recoveryCode,
                    onValueChange = {
                        recoveryCode = it.uppercase()
                        viewModel.clearPinSecurityFeedback()
                    },
                    label = { Text(stringResource(R.string.recovery_code_input_label)) },
                    placeholder = { Text("XXXX-XXXX-XXXX") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("forgot_pin_recovery_code_input")
                )

                Text(stringResource(R.string.change_login_pin_new), fontWeight = FontWeight.SemiBold)
                FiveDigitPinField(
                    pin = newPin,
                    onPinChange = {
                        newPin = it
                        viewModel.clearPinSecurityFeedback()
                    },
                    testTag = "forgot_pin_new_pin_input"
                )

                Text(stringResource(R.string.change_login_pin_confirm), fontWeight = FontWeight.SemiBold)
                FiveDigitPinField(
                    pin = confirmPin,
                    onPinChange = {
                        confirmPin = it
                        viewModel.clearPinSecurityFeedback()
                    },
                    testTag = "forgot_pin_confirm_pin_input"
                )

                uiState.pinSecurityError?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = err.toLocalizedMessage(context),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Button(
                    onClick = {
                        viewModel.resetLoginPinWithRecoveryCode(
                            country = selectedCountry,
                            phoneInput = phoneInput,
                            recoveryCode = recoveryCode,
                            newPin = newPin,
                            confirmNewPin = confirmPin
                        )
                    },
                    enabled = !uiState.isPinSecurityBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("forgot_pin_reset_submit"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (uiState.isPinSecurityBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Rounded.LockReset, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.forgot_login_pin_submit))
                    }
                }
            }
        }
    }
}

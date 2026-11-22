package com.mychattingpro.features.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.mychattingpro.core.localization.DateTimeFormatters
import com.mychattingpro.core.network.NetworkModule
import com.mychattingpro.domain.models.Conversation
import com.mychattingpro.features.auth.CountryPickerSheet
import com.mychattingpro.features.common.UserAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddChatScreen(
    viewModel: ChatsViewModel,
    networkModule: NetworkModule,
    onConversationReady: (Conversation) -> Unit
) {
    val state by viewModel.addChatState.collectAsStateWithLifecycle()
    val recentIsos by viewModel.recentCountryIsos.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCountryPicker by remember { mutableStateOf(false) }

    if (showCountryPicker) {
        CountryPickerSheet(
            recentIsoCodes = recentIsos,
            onSelect = viewModel::selectAddChatCountry,
            onDismiss = { showCountryPicker = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.nav_add_chat)) }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.add_chat_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCountryPicker = true }
                        .testTag("add_chat_country_selector"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(state.selectedCountry.flagEmoji, style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "${state.selectedCountry.name} (${state.selectedCountry.callingCode})",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                    }
                }

                OutlinedTextField(
                    value = state.nameFilter,
                    onValueChange = viewModel::updateAddChatNameFilter,
                    label = { Text(stringResource(R.string.add_chat_name_filter)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_chat_name_input")
                )

                OutlinedTextField(
                    value = state.phoneInput,
                    onValueChange = viewModel::updateAddChatPhone,
                    label = { Text(stringResource(R.string.label_phone_number)) },
                    placeholder = { Text(stringResource(R.string.hint_phone_number)) },
                    prefix = { Text("${state.selectedCountry.callingCode} ", fontWeight = FontWeight.SemiBold) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = { viewModel.searchUser() }
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_chat_phone_input")
                )

                Button(
                    onClick = viewModel::searchUser,
                    enabled = !state.isSearching && state.rateLimitSecondsRemaining == 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("add_chat_search_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (state.isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else if (state.rateLimitSecondsRemaining > 0) {
                        Text(stringResource(R.string.add_chat_search_wait, state.rateLimitSecondsRemaining))
                    } else {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.add_chat_search_btn))
                    }
                }

                if (state.error != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = state.error!!.toLocalizedMessage(context),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                if (state.hasSearched && state.foundUser == null && state.error == null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.PersonSearch,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.add_chat_not_found),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }

                state.foundUser?.let { user ->
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_chat_found_profile_card"),
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.VerifiedUser,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.add_chat_profile_verified),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                UserAvatar(
                                    displayName = user.displayName,
                                    avatarMediaId = user.avatarMediaId,
                                    networkModule = networkModule,
                                    size = 64.dp,
                                    isOnline = user.isOnline,
                                    showOfflineDot = true
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = user.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val statusLabel = when {
                                        user.isOnline -> stringResource(R.string.status_online)
                                        user.lastSeenMs != null -> stringResource(
                                            R.string.status_user_offline_last_seen,
                                            DateTimeFormatters.formatChatListTime(
                                                context,
                                                user.lastSeenMs,
                                                language
                                            )
                                        )
                                        else -> stringResource(R.string.status_user_offline)
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = statusLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (user.isOnline) {
                                            com.mychattingpro.core.theme.OnlineGreen
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    if (user.about.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = user.about,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Button(
                                onClick = { viewModel.startChatWithFoundUser(onConversationReady) },
                                enabled = !state.isStartingChat,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("add_chat_start_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.add_chat_start_btn))
                            }
                        }
                    }
                }
            }
        }
    }
}

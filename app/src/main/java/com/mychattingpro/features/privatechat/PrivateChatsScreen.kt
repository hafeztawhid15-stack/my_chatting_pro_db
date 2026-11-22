package com.mychattingpro.features.privatechat

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.localization.DateTimeFormatters
import com.mychattingpro.core.network.NetworkModule
import com.mychattingpro.domain.models.Conversation
import com.mychattingpro.domain.models.MessageType
import com.mychattingpro.features.auth.CountryPickerSheet
import com.mychattingpro.features.common.ConversationActionModals
import com.mychattingpro.features.common.ConversationActionsDropdownMenu
import com.mychattingpro.features.common.ConversationMenuCallbacks
import com.mychattingpro.features.common.NetworkStatusBanner
import com.mychattingpro.features.common.PresenceBadge
import com.mychattingpro.features.common.UserAvatar
import com.mychattingpro.features.common.rememberConversationMenuController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateChatsScreen(
    viewModel: PrivateChatViewModel,
    networkModule: NetworkModule,
    onBackAndLock: () -> Unit,
    onLockedOut: () -> Unit,
    onOpenPrivateConversation: (Conversation) -> Unit
) {
    val isUnlocked by viewModel.isUnlocked.collectAsStateWithLifecycle()
    val conversations by viewModel.privateConversations.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val socketState by viewModel.socketState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshingChats.collectAsStateWithLifecycle()
    val chatsError by viewModel.chatsError.collectAsStateWithLifecycle()
    val peerProfileModal by viewModel.peerProfileModal.collectAsStateWithLifecycle()
    val actionNotice by viewModel.actionNotice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val menuController = rememberConversationMenuController()

    var showNewPrivateChatDialog by remember { mutableStateOf(false) }
    var showArchivedSection by remember { mutableStateOf(false) }

    BackHandler(onBack = onBackAndLock)

    LaunchedEffect(isUnlocked) {
        if (!isUnlocked) {
            onLockedOut()
        } else {
            viewModel.refreshPrivateConversations()
        }
    }

    LaunchedEffect(actionNotice) {
        actionNotice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearActionFeedback()
        }
    }

    val conversationCallbacks = remember(viewModel, context) {
        ConversationMenuCallbacks(
            onViewPeerProfile = { conv -> viewModel.viewPeerProfile(context, conv) },
            onSetMute = { conv, untilMs -> viewModel.setMutedUntil(conv, untilMs) },
            onSetCustomSound = { conv, uri, label -> viewModel.setCustomSound(conv, uri, label) },
            onTogglePin = { conv -> viewModel.togglePin(conv) },
            onToggleMarkUnread = { conv -> viewModel.toggleMarkUnread(conv) },
            onToggleArchive = { conv -> viewModel.toggleArchive(conv) },
            onClearChat = { conv -> viewModel.clearChat(conv) },
            onToggleBlock = { conv -> viewModel.toggleBlockPeer(conv) },
            onReportUser = { conv, reason, details -> viewModel.reportPeer(conv, reason, details) },
            onDeleteChat = { conv -> viewModel.deleteChat(conv) },
            onMoveToRegularChat = { conv -> viewModel.moveToRegularChat(context, conv) }
        )
    }

    ConversationActionModals(
        controller = menuController,
        callbacks = conversationCallbacks,
        peerProfileState = peerProfileModal,
        onDismissPeerProfile = viewModel::dismissPeerProfile,
        networkModule = networkModule,
        language = language
    )

    if (showNewPrivateChatDialog) {
        NewPrivateChatDialog(
            viewModel = viewModel,
            networkModule = networkModule,
            onDismiss = { showNewPrivateChatDialog = false },
            onConversationReady = { conv ->
                showNewPrivateChatDialog = false
                onOpenPrivateConversation(conv)
            }
        )
    }

    val activeConversations = remember(conversations) { conversations.filterNot { it.isArchived } }
    val archivedConversations = remember(conversations) { conversations.filter { it.isArchived } }

    Scaffold(
        modifier = Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent()
                    viewModel.touchActivity()
                }
            }
        },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBackAndLock,
                        modifier = Modifier.testTag("private_chats_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = stringResource(R.string.private_chat_title),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.lockNow() },
                        modifier = Modifier.testTag("private_chat_lock_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.private_chat_lock_now))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.resetAddChatModal()
                    showNewPrivateChatDialog = true
                },
                modifier = Modifier.testTag("private_chat_add_fab")
            ) {
                Icon(
                    imageVector = Icons.Rounded.PersonAdd,
                    contentDescription = stringResource(R.string.private_chat_add_btn)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            NetworkStatusBanner(isOnline = isOnline, socketState = socketState)

            if (chatsError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = chatsError!!.toLocalizedMessage(context),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.refreshPrivateConversations() }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }

            if (isRefreshing && conversations.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (conversations.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.size(96.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.private_chat_empty_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.private_chat_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = {
                            viewModel.resetAddChatModal()
                            showNewPrivateChatDialog = true
                        },
                        modifier = Modifier.testTag("private_chat_empty_add_button")
                    ) {
                        Icon(Icons.Rounded.PersonAdd, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.private_chat_add_btn))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    if (archivedConversations.isNotEmpty()) {
                        item(key = "private_archived_header") {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showArchivedSection = !showArchivedSection }
                                    .testTag("private_archived_chats_toggle")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Archive,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Text(
                                        text = stringResource(R.string.archived_chats_header, archivedConversations.size),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = if (showArchivedSection) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                        contentDescription = null
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                        if (showArchivedSection) {
                            items(archivedConversations, key = { "priv_arch_${it.id}" }) { conv ->
                                PrivateConversationRow(
                                    conversation = conv,
                                    myUserId = session?.userId,
                                    language = language,
                                    networkModule = networkModule,
                                    menuController = menuController,
                                    menuCallbacks = conversationCallbacks,
                                    onClick = {
                                        viewModel.touchActivity()
                                        onOpenPrivateConversation(conv)
                                    }
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 82.dp, end = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                )
                            }
                        }
                    }

                    items(activeConversations, key = { it.id }) { conv ->
                        PrivateConversationRow(
                            conversation = conv,
                            myUserId = session?.userId,
                            language = language,
                            networkModule = networkModule,
                            menuController = menuController,
                            menuCallbacks = conversationCallbacks,
                            onClick = {
                                viewModel.touchActivity()
                                onOpenPrivateConversation(conv)
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 82.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivateConversationRow(
    conversation: Conversation,
    myUserId: String?,
    language: AppLanguage,
    networkModule: NetworkModule,
    menuController: com.mychattingpro.features.common.ConversationMenuController,
    menuCallbacks: ConversationMenuCallbacks,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var rowMenuExpanded by remember { mutableStateOf(false) }
    val timeText = remember(conversation.lastMessageAtMs, language) {
        conversation.lastMessageAtMs?.let {
            DateTimeFormatters.formatChatListTime(context, it, language)
        }.orEmpty()
    }
    val hasUnread = conversation.unreadCount > 0 || conversation.markedUnread

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)
            .testTag("private_chat_item_${conversation.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(
            displayName = conversation.peerDisplayName,
            avatarMediaId = conversation.peerAvatarMediaId,
            networkModule = networkModule,
            size = 54.dp,
            isOnline = conversation.peerIsOnline,
            showOfflineDot = true
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.peerDisplayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    PresenceBadge(isOnline = conversation.peerIsOnline)
                }
                Spacer(modifier = Modifier.width(6.dp))
                if (conversation.isPinned) {
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = stringResource(R.string.chat_menu_pin),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (conversation.isMutedNow()) {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsOff,
                        contentDescription = stringResource(R.string.chat_menu_mute),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasUnread) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (conversation.lastMessageSenderId != null && conversation.lastMessageSenderId == myUserId) {
                    Icon(
                        imageVector = Icons.Rounded.DoneAll,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                when (conversation.lastMessageType) {
                    MessageType.IMAGE -> {
                        Icon(Icons.Rounded.Image, null, Modifier.size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    MessageType.VIDEO -> {
                        Icon(Icons.Rounded.Videocam, null, Modifier.size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    MessageType.VOICE, MessageType.AUDIO -> {
                        Icon(Icons.Rounded.Mic, null, Modifier.size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    MessageType.FILE -> {
                        Icon(Icons.Rounded.InsertDriveFile, null, Modifier.size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    else -> Unit
                }
                Text(
                    text = conversation.lastMessagePreview ?: conversation.peerAbout,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (conversation.unreadCount > 0 || conversation.markedUnread) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        val badgeLabel = if (conversation.unreadCount > 0) {
                            conversation.unreadCount.coerceAtMost(99).toString()
                        } else {
                            " "
                        }
                        Text(
                            text = badgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(
                                horizontal = if (conversation.unreadCount > 0) 8.dp else 5.dp,
                                vertical = 3.dp
                            )
                        )
                    }
                }
            }
        }

        Box {
            IconButton(
                onClick = { rowMenuExpanded = true },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("private_chat_row_menu_${conversation.id}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Private Chat Options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ConversationActionsDropdownMenu(
                expanded = rowMenuExpanded,
                conversation = conversation,
                controller = menuController,
                callbacks = menuCallbacks,
                onDismiss = { rowMenuExpanded = false },
                isPrivate = true
            )
        }
    }
}

@Composable
private fun NewPrivateChatDialog(
    viewModel: PrivateChatViewModel,
    networkModule: NetworkModule,
    onDismiss: () -> Unit,
    onConversationReady: (Conversation) -> Unit
) {
    val state by viewModel.addChatState.collectAsStateWithLifecycle()
    val recentIsos by viewModel.recentCountryIsos.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCountryPicker by remember { mutableStateOf(false) }

    if (showCountryPicker) {
        CountryPickerSheet(
            recentIsoCodes = recentIsos,
            onSelect = viewModel::selectAddChatCountry,
            onDismiss = { showCountryPicker = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.private_chat_add_btn),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCountryPicker = true }
                        .testTag("private_add_chat_country_selector"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(state.selectedCountry.flagEmoji, style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "${state.selectedCountry.name} (${state.selectedCountry.callingCode})",
                            style = MaterialTheme.typography.bodyMedium,
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
                        .testTag("private_add_chat_name_input")
                )

                OutlinedTextField(
                    value = state.phoneInput,
                    onValueChange = viewModel::updateAddChatPhone,
                    label = { Text(stringResource(R.string.label_phone_number)) },
                    placeholder = { Text(stringResource(R.string.hint_phone_number)) },
                    prefix = { Text("${state.selectedCountry.callingCode} ", fontWeight = FontWeight.SemiBold) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("private_add_chat_phone_input")
                )

                Button(
                    onClick = { viewModel.searchUserForPrivateChat() },
                    enabled = !state.isSearching,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("private_add_chat_search_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (state.isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.add_chat_search_btn))
                    }
                }

                state.error?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = err.toLocalizedMessage(context),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                if (state.hasSearched && state.foundUser == null && state.error == null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.PersonSearch, contentDescription = null)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.add_chat_not_found),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                state.foundUser?.let { user ->
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                UserAvatar(
                                    displayName = user.displayName,
                                    avatarMediaId = user.avatarMediaId,
                                    networkModule = networkModule,
                                    size = 50.dp,
                                    isOnline = user.isOnline
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = user.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = user.about,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Button(
                                onClick = { viewModel.startPrivateChatWithFoundUser(onConversationReady) },
                                enabled = !state.isStartingChat,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("private_add_chat_start_button"),
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
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

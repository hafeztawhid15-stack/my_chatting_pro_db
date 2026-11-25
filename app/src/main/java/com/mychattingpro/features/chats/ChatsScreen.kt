package com.mychattingpro.features.chats

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.mychattingpro.core.common.CacheManager
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.localization.DateTimeFormatters
import com.mychattingpro.core.network.NetworkModule
import com.mychattingpro.domain.models.Conversation
import com.mychattingpro.domain.models.MessageType
import com.mychattingpro.features.common.ConversationActionModals
import com.mychattingpro.features.common.ConversationActionsDropdownMenu
import com.mychattingpro.features.common.ConversationMenuCallbacks
import com.mychattingpro.features.common.NetworkStatusBanner
import com.mychattingpro.features.common.PresenceBadge
import com.mychattingpro.features.common.UserAvatar
import com.mychattingpro.features.common.rememberConversationMenuController
import kotlinx.coroutines.launch

private const val WEBSITE_URL = "https://sites.google.com/view/you-pr/%E0%A6%B9%E0%A6%AE"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    viewModel: ChatsViewModel,
    networkModule: NetworkModule,
    onOpenProfile: () -> Unit,
    onOpenConversation: (Conversation) -> Unit,
    onOpenPrivateChat: () -> Unit = {},
    onOpenOwnerMonitor: () -> Unit = {},
    onOpenNotifications: () -> Unit = {}
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val unreadAlertsCount by viewModel.unreadSecurityAlertsCount.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val socketState by viewModel.socketState.collectAsStateWithLifecycle()
    val peerProfileModal by viewModel.peerProfileModal.collectAsStateWithLifecycle()
    val actionNotice by viewModel.actionNotice.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val menuController = rememberConversationMenuController()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var menuExpanded by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showArchivedSection by remember { mutableStateOf(false) }

    LaunchedEffect(actionNotice) {
        actionNotice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearActionFeedback()
        }
    }

    LaunchedEffect(actionError) {
        actionError?.let {
            Toast.makeText(context, it.toLocalizedMessage(context), Toast.LENGTH_LONG).show()
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
            onMoveToPrivateChat = { conv -> viewModel.moveToPrivateChat(context, conv) }
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

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text(stringResource(R.string.clear_cache_confirm_title)) },
            text = { Text(stringResource(R.string.clear_cache_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearCacheDialog = false
                        scope.launch {
                            CacheManager.clearTemporaryCache(context)
                            Toast.makeText(
                                context,
                                context.getString(R.string.clear_cache_success),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier.testTag("confirm_clear_cache_button")
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    val activeConversations = remember(conversations) { conversations.filterNot { it.isArchived } }
    val archivedConversations = remember(conversations) { conversations.filter { it.isArchived } }

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            navigationIcon = {
                Box(modifier = Modifier.padding(start = 12.dp)) {
                    UserAvatar(
                        displayName = session?.displayName ?: "M",
                        avatarMediaId = session?.avatarMediaId,
                        networkModule = networkModule,
                        size = 40.dp,
                        onClick = onOpenProfile,
                        modifier = Modifier.testTag("chats_profile_avatar")
                    )
                }
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_art_clean),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = onOpenNotifications,
                    modifier = Modifier.testTag("chats_notifications_button")
                ) {
                    BadgedBox(
                        badge = {
                            if (unreadAlertsCount > 0) {
                                Badge {
                                    Text(
                                        text = if (unreadAlertsCount > 99) "99+" else unreadAlertsCount.toString()
                                    )
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Notifications,
                            contentDescription = stringResource(R.string.notif_center_title)
                        )
                    }
                }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag("chats_three_dot_menu")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Menu"
                        )
                    }
                    // Top-right 3-dot Menu: Clear Cache, Details, Private Chat, App Usage Monitor (Owner)
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_clear_cache)) },
                            onClick = {
                                menuExpanded = false
                                showClearCacheDialog = true
                            },
                            modifier = Modifier.testTag("menu_clear_cache")
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_website)) },
                            onClick = {
                                menuExpanded = false
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                runCatching { context.startActivity(intent) }.onFailure {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.err_network),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier.testTag("menu_website")
                        )
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            text = { Text(stringResource(R.string.menu_private_chat)) },
                            onClick = {
                                menuExpanded = false
                                onOpenPrivateChat()
                            },
                            modifier = Modifier.testTag("menu_private_chat")
                        )
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Shield,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            text = { Text(stringResource(R.string.settings_owner_monitor)) },
                            onClick = {
                                menuExpanded = false
                                onOpenOwnerMonitor()
                            },
                            modifier = Modifier.testTag("menu_owner_monitor")
                        )
                    }
                }
            }
        )

        NetworkStatusBanner(isOnline = isOnline, socketState = socketState)

        if (conversations.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_empty_chats),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(180.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.height(22.dp))
                Text(
                    text = stringResource(R.string.empty_chats_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.empty_chats_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                if (archivedConversations.isNotEmpty()) {
                    item(key = "archived_header") {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showArchivedSection = !showArchivedSection }
                                .testTag("archived_chats_toggle")
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
                        items(archivedConversations, key = { "arch_${it.id}" }) { conv ->
                            ConversationRow(
                                conversation = conv,
                                myUserId = session?.userId,
                                language = language,
                                networkModule = networkModule,
                                menuController = menuController,
                                menuCallbacks = conversationCallbacks,
                                onClick = { onOpenConversation(conv) }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 82.dp, end = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            )
                        }
                    }
                }

                items(activeConversations, key = { it.id }) { conv ->
                    ConversationRow(
                        conversation = conv,
                        myUserId = session?.userId,
                        language = language,
                        networkModule = networkModule,
                        menuController = menuController,
                        menuCallbacks = conversationCallbacks,
                        onClick = { onOpenConversation(conv) }
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

@Composable
private fun ConversationRow(
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
            .testTag("chat_item_${conversation.id}"),
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
                        Icon(
                            Icons.Rounded.Image,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    MessageType.VIDEO -> {
                        Icon(
                            Icons.Rounded.Videocam,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    MessageType.VOICE, MessageType.AUDIO -> {
                        Icon(
                            Icons.Rounded.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    MessageType.FILE -> {
                        Icon(
                            Icons.Rounded.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                    .testTag("chat_row_menu_${conversation.id}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Chat Options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ConversationActionsDropdownMenu(
                expanded = rowMenuExpanded,
                conversation = conversation,
                controller = menuController,
                callbacks = menuCallbacks,
                onDismiss = { rowMenuExpanded = false },
                isPrivate = false
            )
        }
    }
}

package com.mychattingpro.features.common

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.example.R
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.localization.DateTimeFormatters
import com.mychattingpro.core.network.NetworkModule
import com.mychattingpro.domain.models.Conversation
import com.mychattingpro.domain.models.ConversationPeerProfile

data class ConversationMenuCallbacks(
    val onViewPeerProfile: (Conversation) -> Unit,
    val onSetMute: (Conversation, Long?) -> Unit,
    val onSetCustomSound: (Conversation, String?, String?) -> Unit,
    val onTogglePin: (Conversation) -> Unit,
    val onToggleMarkUnread: (Conversation) -> Unit,
    val onToggleArchive: (Conversation) -> Unit,
    val onClearChat: (Conversation) -> Unit,
    val onToggleBlock: (Conversation) -> Unit,
    val onReportUser: (Conversation, String, String?) -> Unit,
    val onDeleteChat: (Conversation) -> Unit
)

class ConversationMenuController {
    var activeMuteTarget by mutableStateOf<Conversation?>(null)
    var activeSoundTarget by mutableStateOf<Conversation?>(null)
    var activeClearTarget by mutableStateOf<Conversation?>(null)
    var activeDeleteTarget by mutableStateOf<Conversation?>(null)
    var activeReportTarget by mutableStateOf<Conversation?>(null)
}

@Composable
fun rememberConversationMenuController(): ConversationMenuController {
    return remember { ConversationMenuController() }
}

@Composable
fun ConversationActionsDropdownMenu(
    expanded: Boolean,
    conversation: Conversation,
    controller: ConversationMenuController,
    callbacks: ConversationMenuCallbacks,
    onDismiss: () -> Unit,
    isPrivate: Boolean = conversation.isPrivate
) {
    val isMuted = conversation.isMutedNow()
    val hasUnread = conversation.unreadCount > 0 || conversation.markedUnread

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        // 1. View Profile
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
            text = { Text(stringResource(R.string.chat_menu_view_profile)) },
            onClick = {
                onDismiss()
                callbacks.onViewPeerProfile(conversation)
            },
            modifier = Modifier.testTag("chat_action_view_profile")
        )
        // 2. Mute / Unmute Notifications
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    if (isMuted) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff,
                    contentDescription = null
                )
            },
            text = {
                Text(
                    if (isMuted) stringResource(R.string.chat_menu_unmute)
                    else stringResource(R.string.chat_menu_mute)
                )
            },
            onClick = {
                onDismiss()
                if (isMuted) {
                    callbacks.onSetMute(conversation, null)
                } else {
                    controller.activeMuteTarget = conversation
                }
            },
            modifier = Modifier.testTag("chat_action_mute")
        )
        // 3. Custom Notification Sound
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Outlined.MusicNote, contentDescription = null) },
            text = {
                Column {
                    Text(stringResource(R.string.chat_menu_custom_sound))
                    if (!conversation.customSoundLabel.isNullOrBlank()) {
                        Text(
                            text = conversation.customSoundLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            onClick = {
                onDismiss()
                controller.activeSoundTarget = conversation
            },
            modifier = Modifier.testTag("chat_action_custom_sound")
        )
        // 4. Pin / Unpin Chat
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
            text = {
                Text(
                    if (conversation.isPinned) stringResource(R.string.chat_menu_unpin)
                    else stringResource(R.string.chat_menu_pin)
                )
            },
            onClick = {
                onDismiss()
                callbacks.onTogglePin(conversation)
            },
            modifier = Modifier.testTag("chat_action_pin")
        )
        // 5. Mark as Unread / Read
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    if (hasUnread) Icons.Outlined.MarkChatRead else Icons.Outlined.MarkChatUnread,
                    contentDescription = null
                )
            },
            text = {
                Text(
                    if (hasUnread) stringResource(R.string.chat_menu_mark_read)
                    else stringResource(R.string.chat_menu_mark_unread)
                )
            },
            onClick = {
                onDismiss()
                callbacks.onToggleMarkUnread(conversation)
            },
            modifier = Modifier.testTag("chat_action_mark_unread")
        )
        // 6. Archive / Unarchive Chat
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    if (conversation.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                    contentDescription = null
                )
            },
            text = {
                Text(
                    if (conversation.isArchived) stringResource(R.string.chat_menu_unarchive)
                    else stringResource(R.string.chat_menu_archive)
                )
            },
            onClick = {
                onDismiss()
                callbacks.onToggleArchive(conversation)
            },
            modifier = Modifier.testTag("chat_action_archive")
        )
        HorizontalDivider()
        // 7. Clear Chat
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Outlined.CleaningServices, contentDescription = null) },
            text = { Text(stringResource(R.string.chat_menu_clear_chat)) },
            onClick = {
                onDismiss()
                controller.activeClearTarget = conversation
            },
            modifier = Modifier.testTag("chat_action_clear")
        )
        // 8. Block / Unblock User
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    Icons.Outlined.Block,
                    contentDescription = null,
                    tint = if (conversation.isBlockedByMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    if (conversation.isBlockedByMe) stringResource(R.string.action_unblock_user)
                    else stringResource(R.string.action_block_user)
                )
            },
            onClick = {
                onDismiss()
                callbacks.onToggleBlock(conversation)
            },
            modifier = Modifier.testTag("chat_action_block")
        )
        // 9. Report User
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Outlined.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            text = { Text(stringResource(R.string.action_report_user)) },
            onClick = {
                onDismiss()
                controller.activeReportTarget = conversation
            },
            modifier = Modifier.testTag("chat_action_report")
        )
        // 10. Delete Chat
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            text = {
                Text(
                    text = if (isPrivate) stringResource(R.string.chat_menu_delete_private_chat)
                    else stringResource(R.string.chat_menu_delete_chat),
                    color = MaterialTheme.colorScheme.error
                )
            },
            onClick = {
                onDismiss()
                controller.activeDeleteTarget = conversation
            },
            modifier = Modifier.testTag("chat_action_delete")
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConversationActionModals(
    controller: ConversationMenuController,
    callbacks: ConversationMenuCallbacks,
    peerProfileState: PeerProfileModalState?,
    onDismissPeerProfile: () -> Unit,
    networkModule: NetworkModule,
    language: AppLanguage
) {
    val context = LocalContext.current

    var soundTargetPendingPicker by remember { mutableStateOf<Conversation?>(null) }
    val ringtoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val target = soundTargetPendingPicker
        soundTargetPendingPicker = null
        if (result.resultCode == Activity.RESULT_OK && target != null) {
            val pickedUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            if (pickedUri != null) {
                val title = runCatching {
                    RingtoneManager.getRingtone(context, pickedUri)?.getTitle(context)
                }.getOrNull() ?: context.getString(R.string.chat_menu_custom_sound)
                callbacks.onSetCustomSound(target, pickedUri.toString(), title)
            } else {
                callbacks.onSetCustomSound(target, null, null)
            }
        }
    }

    controller.activeMuteTarget?.let { target ->
        val now = System.currentTimeMillis()
        val options = listOf(
            stringResource(R.string.mute_1_hour) to (now + 3_600_000L),
            stringResource(R.string.mute_8_hours) to (now + 8L * 3_600_000L),
            stringResource(R.string.mute_1_week) to (now + 7L * 24L * 3_600_000L),
            stringResource(R.string.mute_always) to (now + 100L * 365L * 24L * 3_600_000L)
        )
        AlertDialog(
            onDismissRequest = { controller.activeMuteTarget = null },
            title = { Text(stringResource(R.string.chat_menu_mute_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    options.forEach { (label, untilMs) ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    controller.activeMuteTarget = null
                                    callbacks.onSetMute(target, untilMs)
                                }
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { controller.activeMuteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    controller.activeSoundTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { controller.activeSoundTarget = null },
            title = { Text(stringResource(R.string.chat_menu_custom_sound)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = target.customSoundLabel?.let {
                            stringResource(R.string.custom_sound_current, it)
                        } ?: stringResource(R.string.custom_sound_default),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    soundTargetPendingPicker = target
                    controller.activeSoundTarget = null
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, context.getString(R.string.chat_menu_custom_sound))
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        target.customSoundUri?.let {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it))
                        }
                    }
                    ringtoneLauncher.launch(intent)
                }) {
                    Text(stringResource(R.string.custom_sound_choose))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!target.customSoundUri.isNullOrBlank()) {
                        TextButton(onClick = {
                            controller.activeSoundTarget = null
                            callbacks.onSetCustomSound(target, null, null)
                        }) {
                            Text(stringResource(R.string.custom_sound_reset))
                        }
                    }
                    TextButton(onClick = { controller.activeSoundTarget = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        )
    }

    controller.activeClearTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { controller.activeClearTarget = null },
            title = { Text(stringResource(R.string.chat_menu_clear_chat)) },
            text = { Text(stringResource(R.string.chat_clear_confirm_message, target.peerDisplayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.activeClearTarget = null
                        callbacks.onClearChat(target)
                    }
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { controller.activeClearTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    controller.activeDeleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { controller.activeDeleteTarget = null },
            title = {
                Text(
                    text = stringResource(R.string.chat_menu_delete_chat),
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = { Text(stringResource(R.string.chat_delete_confirm_message, target.peerDisplayName)) },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        controller.activeDeleteTarget = null
                        callbacks.onDeleteChat(target)
                    }
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { controller.activeDeleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    controller.activeReportTarget?.let { target ->
        val reasons = listOf("Spam", "Abuse", "Harassment", "Other")
        var selectedReason by remember(target.id) { mutableStateOf("Spam") }
        var details by remember(target.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { controller.activeReportTarget = null },
            title = { Text(stringResource(R.string.action_report_user)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.report_reason_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        reasons.forEach { reason ->
                            FilterChip(
                                selected = selectedReason == reason,
                                onClick = { selectedReason = reason },
                                label = { Text(reason) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = details,
                        onValueChange = { details = it },
                        label = { Text(stringResource(R.string.report_details_hint)) },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        controller.activeReportTarget = null
                        callbacks.onReportUser(target, selectedReason, details.trim().takeIf { it.isNotEmpty() })
                    }
                ) {
                    Text(stringResource(R.string.report_submit))
                }
            },
            dismissButton = {
                TextButton(onClick = { controller.activeReportTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    peerProfileState?.let { modal ->
        AlertDialog(
            onDismissRequest = onDismissPeerProfile,
            title = { Text(stringResource(R.string.chat_menu_view_profile)) },
            text = {
                when {
                    modal.isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    }
                    modal.profile != null -> {
                        val user = modal.profile.user
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            UserAvatar(
                                displayName = user.displayName,
                                avatarMediaId = user.avatarMediaId,
                                networkModule = networkModule,
                                size = 76.dp,
                                isOnline = user.isOnline
                            )
                            Text(
                                text = user.displayName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (user.isOnline) {
                                    stringResource(R.string.status_online)
                                } else if (user.lastSeenMs != null) {
                                    stringResource(
                                        R.string.status_last_seen,
                                        DateTimeFormatters.formatChatListTime(context, user.lastSeenMs, language)
                                    )
                                } else {
                                    stringResource(R.string.status_offline)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (user.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (user.about.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = stringResource(R.string.label_about),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = user.about,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                            if (modal.profile.isBlockedByMe) {
                                Text(
                                    text = stringResource(R.string.blocked_banner),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    modal.errorMessage != null -> {
                        Text(
                            text = modal.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissPeerProfile) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        )
    }
}

data class PeerProfileModalState(
    val isLoading: Boolean = false,
    val profile: ConversationPeerProfile? = null,
    val errorMessage: String? = null
)

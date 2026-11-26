package com.mychattingpro.features.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.localization.DateTimeFormatters
import com.mychattingpro.domain.models.Message
import com.mychattingpro.domain.models.MessageStatus
import com.mychattingpro.domain.models.MessageType

private enum class MessageDeleteScope {
    FOR_ME,
    FOR_EVERYONE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailsSheet(
    message: Message,
    isMine: Boolean,
    language: AppLanguage,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSaveMedia: (() -> Unit)?,
    onRetry: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onReportClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val notAvailable = stringResource(R.string.msg_details_not_available)
    val isDeleted = message.deletedForAllAtMs != null
    val isPending = message.id.startsWith("pending_")
    val isFailed = message.status == MessageStatus.FAILED
    val canCopy = !isDeleted && !message.bodyText.isNullOrBlank()
    val canReply = !isDeleted && !isPending
    val canShare = !isDeleted && !isPending &&
        (!message.bodyText.isNullOrBlank() || message.media != null || !message.localUri.isNullOrBlank())
    val canDeleteForMe = !isPending || isFailed
    val canDeleteForEveryone = isMine && !isDeleted && !isPending

    var pendingConfirmScope by remember(message.id) { mutableStateOf<MessageDeleteScope?>(null) }

    pendingConfirmScope?.let { scope ->
        AlertDialog(
            onDismissRequest = { pendingConfirmScope = null },
            title = { Text(stringResource(R.string.delete_msg_confirm_title)) },
            text = {
                Text(
                    if (scope == MessageDeleteScope.FOR_EVERYONE) {
                        stringResource(R.string.delete_for_everyone_confirm_body)
                    } else {
                        stringResource(R.string.delete_for_me_confirm_body)
                    }
                )
            },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        pendingConfirmScope = null
                        if (scope == MessageDeleteScope.FOR_EVERYONE) {
                            onDeleteForEveryone()
                        } else {
                            onDeleteForMe()
                        }
                        onDismiss()
                    },
                    modifier = Modifier.testTag("confirm_delete_message_button")
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmScope = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.msg_details_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isDeleted) {
                        stringResource(R.string.msg_deleted_placeholder)
                    } else {
                        message.bodyText?.takeIf { it.isNotBlank() }
                            ?: message.media?.originalFilename
                            ?: when (message.type) {
                                MessageType.IMAGE -> stringResource(R.string.reply_quoted_photo)
                                MessageType.VIDEO -> stringResource(R.string.reply_quoted_video)
                                MessageType.VOICE -> stringResource(R.string.reply_quoted_voice)
                                MessageType.AUDIO -> stringResource(R.string.reply_quoted_audio)
                                MessageType.FILE -> stringResource(R.string.reply_quoted_file)
                                else -> message.type.name
                            }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(12.dp)
                )
            }

            if (canReply || canCopy || canShare || (onSaveMedia != null && !isDeleted && !isPending)) {
                FlowRowSpacerGrid(
                    canReply = canReply,
                    canCopy = canCopy,
                    canShare = canShare,
                    saveLabel = if (message.type == MessageType.IMAGE) {
                        stringResource(R.string.action_save_image)
                    } else {
                        stringResource(R.string.action_download_open)
                    },
                    showSave = onSaveMedia != null && !isDeleted && !isPending,
                    onReply = {
                        onReply()
                        onDismiss()
                    },
                    onCopy = {
                        onCopy()
                        onDismiss()
                    },
                    onShare = {
                        onShare()
                        onDismiss()
                    },
                    onSave = {
                        onSaveMedia?.invoke()
                        onDismiss()
                    }
                )
            }

            if (canDeleteForMe || canDeleteForEveryone) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (canDeleteForMe) {
                        SheetActionRow(
                            icon = Icons.Rounded.DeleteOutline,
                            label = stringResource(R.string.action_delete_for_me),
                            onClick = { pendingConfirmScope = MessageDeleteScope.FOR_ME },
                            tint = MaterialTheme.colorScheme.error,
                            iconTint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("sheet_delete_for_me")
                        )
                    }
                    if (canDeleteForEveryone) {
                        SheetActionRow(
                            icon = Icons.Rounded.DeleteForever,
                            label = stringResource(R.string.action_delete_message),
                            onClick = { pendingConfirmScope = MessageDeleteScope.FOR_EVERYONE },
                            tint = MaterialTheme.colorScheme.error,
                            iconTint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("sheet_delete_for_everyone")
                        )
                    }
                }
            }

            HorizontalDivider()

            DetailRow(
                label = stringResource(R.string.msg_details_sent),
                value = DateTimeFormatters.formatFullDateTime(context, message.createdAtMs, language)
            )
            DetailRow(
                label = stringResource(R.string.msg_details_delivered),
                value = message.deliveredAtMs?.let {
                    DateTimeFormatters.formatFullDateTime(context, it, language)
                } ?: notAvailable
            )
            DetailRow(
                label = stringResource(R.string.msg_details_read),
                value = message.readAtMs?.let {
                    DateTimeFormatters.formatFullDateTime(context, it, language)
                } ?: notAvailable
            )

            if ((isFailed && isMine) || !isMine) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isFailed && isMine) {
                        OutlinedButton(
                            onClick = {
                                onRetry()
                                onDismiss()
                            },
                            modifier = Modifier.testTag("sheet_retry_message")
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.action_retry))
                        }
                    }

                    if (!isMine) {
                        TextButton(
                            onClick = {
                                onDismiss()
                                onReportClick()
                            },
                            modifier = Modifier.testTag("sheet_report_message")
                        ) {
                            Icon(
                                Icons.Rounded.Flag,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.action_report_message),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FlowRowSpacerGrid(
    canReply: Boolean,
    canCopy: Boolean,
    canShare: Boolean,
    showSave: Boolean,
    saveLabel: String,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (canReply) {
            SheetActionRow(
                icon = Icons.AutoMirrored.Rounded.Reply,
                label = stringResource(R.string.action_reply),
                onClick = onReply,
                modifier = Modifier.testTag("sheet_reply_message")
            )
        }
        if (canCopy) {
            SheetActionRow(
                icon = Icons.Rounded.ContentCopy,
                label = stringResource(R.string.action_copy),
                onClick = onCopy,
                modifier = Modifier.testTag("sheet_copy_message")
            )
        }
        if (canShare) {
            SheetActionRow(
                icon = Icons.Rounded.Share,
                label = stringResource(R.string.action_share),
                onClick = onShare,
                modifier = Modifier.testTag("sheet_share_message")
            )
        }
        if (showSave) {
            SheetActionRow(
                icon = Icons.Rounded.Download,
                label = saveLabel,
                onClick = onSave,
                modifier = Modifier.testTag("sheet_save_message")
            )
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = tint
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportMessageDialog(
    messageId: String,
    onDismiss: () -> Unit,
    onSubmit: (reason: String, details: String?) -> Unit
) {
    val reasons = listOf("Spam", "Abuse", "Harassment", "Other")
    var selectedReason by remember(messageId) { mutableStateOf("Spam") }
    var details by remember(messageId) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_report_message)) },
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
                            label = { Text(reason) },
                            modifier = Modifier.testTag("report_reason_${reason.lowercase()}")
                        )
                    }
                }
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text(stringResource(R.string.report_details_hint)) },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("report_details_input")
                )
            }
        },
        confirmButton = {
            TextButton(
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = {
                    onSubmit(selectedReason, details.trim().takeIf { it.isNotEmpty() })
                },
                modifier = Modifier.testTag("report_submit_button")
            ) {
                Text(stringResource(R.string.report_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

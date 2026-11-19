package com.mychattingpro.features.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.mychattingpro.domain.models.Message
import com.mychattingpro.domain.models.MessageStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailsSheet(
    message: Message,
    isMine: Boolean,
    language: AppLanguage,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val notAvailable = stringResource(R.string.msg_details_not_available)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.msg_details_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.msg_details_message),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = message.bodyText ?: message.media?.originalFilename ?: message.type.name,
                    style = MaterialTheme.typography.bodyLarge
                )
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

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (message.status == MessageStatus.FAILED && isMine) {
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

                if (isMine && message.deletedForAllAtMs == null && !message.id.startsWith("pending_")) {
                    TextButton(
                        onClick = {
                            onDelete()
                            onDismiss()
                        },
                        modifier = Modifier.testTag("sheet_delete_message")
                    ) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.action_delete_message),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (!isMine) {
                    TextButton(
                        onClick = {
                            onReport()
                            onDismiss()
                        },
                        modifier = Modifier.testTag("sheet_report_message")
                    ) {
                        Icon(Icons.Rounded.Flag, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_report_message))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
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

package com.mychattingpro.features.messages

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.localization.DateTimeFormatters
import com.mychattingpro.core.network.NetworkModule
import com.mychattingpro.core.theme.IncomingBubbleDark
import com.mychattingpro.core.theme.IncomingBubbleLight
import com.mychattingpro.core.theme.OnlineGreen
import com.mychattingpro.core.theme.OutgoingBubbleDark
import com.mychattingpro.core.theme.OutgoingBubbleLight
import com.mychattingpro.core.theme.ReadTickCyan
import com.mychattingpro.domain.models.Message
import com.mychattingpro.domain.models.MessageStatus
import com.mychattingpro.domain.models.MessageType
import com.mychattingpro.features.common.ConversationActionModals
import com.mychattingpro.features.common.ConversationActionsDropdownMenu
import com.mychattingpro.features.common.ConversationMenuCallbacks
import com.mychattingpro.features.common.NetworkStatusBanner
import com.mychattingpro.features.common.UserAvatar
import com.mychattingpro.features.common.rememberConversationMenuController
import com.mychattingpro.features.media.MediaUploadHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    viewModel: ChatDetailViewModel,
    initialPeerName: String,
    networkModule: NetworkModule,
    onBack: () -> Unit,
    onStartAudioCall: () -> Unit,
    onStartVideoCall: () -> Unit,
    onOpenMediaViewer: (mediaId: String, mimeType: String, filename: String, isVideo: Boolean) -> Unit,
    onPrivateLockedOut: () -> Unit = {}
) {
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val socketState by viewModel.socketState.collectAsStateWithLifecycle()
    val isPrivateUnlocked by viewModel.isPrivateUnlocked.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    LaunchedEffect(uiState.conversationDeleted) {
        if (uiState.conversationDeleted) {
            onBack()
        }
    }

    if (viewModel.isPrivate) {
        LaunchedEffect(isPrivateUnlocked) {
            if (!isPrivateUnlocked) {
                onPrivateLockedOut()
            }
        }
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val menuController = rememberConversationMenuController()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshConversationState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var selectedMessageForDetails by remember { mutableStateOf<Message?>(null) }
    var messagePendingReport by remember { mutableStateOf<Message?>(null) }
    var dangerousFileConfirmMessage by remember { mutableStateOf<Message?>(null) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var showConversationMenu by remember { mutableStateOf(false) }
    var showMicRationale by remember { mutableStateOf(false) }
    var previewCaption by remember { mutableStateOf("") }
    val messagesById = remember(messages) { messages.associateBy { it.id } }
    val messageIndexById = remember(messages) { messages.mapIndexed { index, m -> m.id to index }.toMap() }

    val menuCallbacks = remember(viewModel) {
        ConversationMenuCallbacks(
            onViewPeerProfile = { viewModel.viewPeerProfile() },
            onSetMute = { _, untilMs -> viewModel.setMutedUntil(untilMs) },
            onSetCustomSound = { _, uri, label -> viewModel.setCustomSound(uri, label) },
            onTogglePin = { conv -> viewModel.togglePin(conv.isPinned) },
            onToggleMarkUnread = { conv -> viewModel.toggleMarkUnread(conv.unreadCount > 0 || conv.markedUnread) },
            onToggleArchive = { conv -> viewModel.toggleArchive(conv.isArchived) },
            onClearChat = { viewModel.clearChat() },
            onToggleBlock = { conv -> viewModel.toggleBlockPeer(conv.isBlockedByMe) },
            onReportUser = { _, reason, details -> viewModel.report(null, reason, details) },
            onDeleteChat = { viewModel.deleteChat() },
            onMoveToPrivateChat = if (!viewModel.isPrivate) {
                { viewModel.moveToPrivateChat() }
            } else null,
            onMoveToRegularChat = if (viewModel.isPrivate) {
                { viewModel.moveToRegularChat() }
            } else null
        )
    }

    ConversationActionModals(
        controller = menuController,
        callbacks = menuCallbacks,
        peerProfileState = uiState.peerProfileModal,
        onDismissPeerProfile = viewModel::dismissPeerProfile,
        networkModule = networkModule,
        language = language
    )

    // Zero-permission Android Photo Picker for Images & Videos
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.showMediaPreview(it, isVideo = false) }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.showMediaPreview(it, isVideo = true) }
    }

    // Zero-permission Storage Access Framework document/file picker (PDF, ZIP, APK, DOC/DOCX, Audio)
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { selectedUri ->
            val mime = context.contentResolver.getType(selectedUri).orEmpty()
            val isAudio = mime.startsWith("audio/")
            viewModel.sendMediaUri(
                uri = selectedUri,
                messageType = if (isAudio) MessageType.AUDIO else MessageType.FILE,
                category = if (isAudio) "audio" else "file"
            )
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.voiceRecorder.startRecording()
        } else {
            Toast.makeText(context, context.getString(R.string.perm_denied), Toast.LENGTH_LONG).show()
        }
    }

    // Scroll to bottom on initial load or when a new message arrives while near bottom
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && !uiState.isLoadingOlder) {
            val lastIndex = messages.lastIndex
            val visibleLast = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (visibleLast >= (lastIndex - 4).coerceAtLeast(0) || listState.firstVisibleItemIndex == 0) {
                listState.scrollToItem(lastIndex)
            }
        }
    }

    // Trigger cursor pagination when user scrolls near top
    val shouldLoadOlder by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex <= 2 &&
                messages.isNotEmpty() &&
                uiState.hasMoreOlder &&
                !uiState.isLoadingOlder
        }
    }
    LaunchedEffect(shouldLoadOlder) {
        if (shouldLoadOlder) {
            viewModel.loadOlderMessages()
        }
    }

    LaunchedEffect(uiState.infoToast) {
        uiState.infoToast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    if (showMicRationale) {
        AlertDialog(
            onDismissRequest = { showMicRationale = false },
            title = { Text(stringResource(R.string.attach_voice)) },
            text = { Text(stringResource(R.string.perm_mic_rationale)) },
            confirmButton = {
                TextButton(onClick = {
                    showMicRationale = false
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMicRationale = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    uiState.pendingMediaPreviewUri?.let { previewUri ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissMediaPreview() },
            title = {
                Text(
                    if (uiState.pendingMediaIsVideo) stringResource(R.string.attach_video)
                    else stringResource(R.string.attach_image)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AsyncImage(
                        model = previewUri,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 280.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                    OutlinedTextField(
                        value = previewCaption,
                        onValueChange = { previewCaption = it },
                        placeholder = { Text(stringResource(R.string.hint_type_message)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cap = previewCaption
                    previewCaption = ""
                    viewModel.confirmAndSendPendingMedia(cap)
                }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    previewCaption = ""
                    viewModel.dismissMediaPreview()
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    dangerousFileConfirmMessage?.let { msg ->
        val filename = msg.media?.originalFilename ?: "file"
        val ext = filename.substringAfterLast('.', "").uppercase()
        AlertDialog(
            onDismissRequest = { dangerousFileConfirmMessage = null },
            title = { Text(stringResource(R.string.dangerous_file_title)) },
            text = { Text(stringResource(R.string.dangerous_file_warning, filename, ext)) },
            confirmButton = {
                TextButton(onClick = {
                    dangerousFileConfirmMessage = null
                    viewModel.downloadAndOpenFile(msg)
                }) {
                    Text(stringResource(R.string.action_download_open))
                }
            },
            dismissButton = {
                TextButton(onClick = { dangerousFileConfirmMessage = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    selectedMessageForDetails?.let { msg ->
        val saveHandler: (() -> Unit)? = when {
            msg.type == MessageType.IMAGE && msg.deletedForAllAtMs == null -> {
                { viewModel.saveImageToGallery(msg) }
            }
            msg.media != null && msg.type != MessageType.VOICE && msg.type != MessageType.AUDIO && msg.deletedForAllAtMs == null -> {
                {
                    if (MediaUploadHelper.isDangerousFile(msg.media.originalFilename)) {
                        dangerousFileConfirmMessage = msg
                    } else {
                        viewModel.downloadAndOpenFile(msg)
                    }
                }
            }
            else -> null
        }
        MessageDetailsSheet(
            message = msg,
            isMine = msg.senderId == viewModel.myUserId,
            language = language,
            onReply = { viewModel.startReply(msg) },
            onCopy = { viewModel.copyMessage(msg) },
            onShare = { viewModel.shareMessage(msg) },
            onSaveMedia = saveHandler,
            onRetry = { viewModel.retryMessage(msg) },
            onDelete = { viewModel.deleteMessage(msg.id) },
            onReportClick = { messagePendingReport = msg },
            onDismiss = { selectedMessageForDetails = null }
        )
    }

    messagePendingReport?.let { msg ->
        ReportMessageDialog(
            messageId = msg.id,
            onDismiss = { messagePendingReport = null },
            onSubmit = { reason, details ->
                messagePendingReport = null
                viewModel.report(msg.id, reason, details)
            }
        )
    }

    val peerName = conversation?.peerDisplayName ?: initialPeerName
    val isBlockedByMe = conversation?.isBlockedByMe == true
    val hasBlockedMe = conversation?.hasBlockedMe == true

    Scaffold(
        modifier = Modifier.pointerInput(viewModel.isPrivate) {
            if (viewModel.isPrivate) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        viewModel.touchPrivateActivity()
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("chat_detail_back")) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UserAvatar(
                            displayName = peerName,
                            avatarMediaId = conversation?.peerAvatarMediaId,
                            networkModule = networkModule,
                            size = 38.dp,
                            isOnline = conversation?.peerIsOnline == true,
                            showOfflineDot = conversation != null
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (viewModel.isPrivate) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = stringResource(R.string.private_chat_title),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                Text(
                                    text = peerName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            val peerIsOnline = conversation?.peerIsOnline == true
                            val subtitle = when {
                                uiState.isPeerTyping -> stringResource(R.string.status_typing)
                                peerIsOnline -> stringResource(R.string.status_online)
                                conversation?.peerLastSeenMs != null -> stringResource(
                                    R.string.status_user_offline_last_seen,
                                    DateTimeFormatters.formatChatListTime(
                                        context,
                                        conversation!!.peerLastSeenMs!!,
                                        language
                                    )
                                )
                                conversation != null -> stringResource(R.string.status_user_offline)
                                else -> ""
                            }
                            if (subtitle.isNotBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    if (!uiState.isPeerTyping && conversation != null) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (peerIsOnline) OnlineGreen
                                                    else MaterialTheme.colorScheme.outline
                                                )
                                        )
                                    }
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (uiState.isPeerTyping || peerIsOnline) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Normal
                                        },
                                        color = when {
                                            uiState.isPeerTyping -> MaterialTheme.colorScheme.primary
                                            peerIsOnline -> OnlineGreen
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    if (!viewModel.isPrivate) {
                        IconButton(
                            onClick = onStartAudioCall,
                            enabled = !isBlockedByMe && !hasBlockedMe,
                            modifier = Modifier.testTag("chat_audio_call_button")
                        ) {
                            Icon(Icons.Rounded.Call, contentDescription = stringResource(R.string.call_audio))
                        }
                        IconButton(
                            onClick = onStartVideoCall,
                            enabled = !isBlockedByMe && !hasBlockedMe,
                            modifier = Modifier.testTag("chat_video_call_button")
                        ) {
                            Icon(Icons.Rounded.Videocam, contentDescription = stringResource(R.string.call_video))
                        }
                    }
                    Box {
                        IconButton(
                            onClick = { showConversationMenu = true },
                            modifier = Modifier.testTag("chat_safety_menu_button")
                        ) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Options")
                        }
                        val activeConv = conversation ?: com.mychattingpro.domain.models.Conversation(
                            id = viewModel.conversationId,
                            type = "direct",
                            peerUserId = viewModel.peerUserId,
                            peerDisplayName = peerName,
                            peerAvatarMediaId = null,
                            peerAbout = "",
                            peerIsOnline = false,
                            peerLastSeenMs = null,
                            isBlockedByMe = false,
                            hasBlockedMe = false,
                            lastMessageId = null,
                            lastMessagePreview = null,
                            lastMessageType = null,
                            lastMessageSenderId = null,
                            lastMessageAtMs = null,
                            unreadCount = 0,
                            updatedAtMs = 0L,
                            isPrivate = viewModel.isPrivate
                        )
                        ConversationActionsDropdownMenu(
                            expanded = showConversationMenu,
                            conversation = activeConv,
                            controller = menuController,
                            callbacks = menuCallbacks,
                            onDismiss = { showConversationMenu = false },
                            isPrivate = viewModel.isPrivate
                        )
                    }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                val activeErrorMessage = uiState.error?.toLocalizedMessage(context) ?: voiceState.error
                if (activeErrorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = activeErrorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = viewModel::clearError, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                if (isBlockedByMe || hasBlockedMe) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isBlockedByMe) {
                                stringResource(R.string.blocked_banner)
                            } else {
                                stringResource(R.string.blocked_interaction_unavailable)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                } else {
                    uiState.replyingToMessage?.let { replyTarget ->
                        ReplyComposerPreviewBar(
                            target = replyTarget,
                            isMineTarget = replyTarget.senderId == viewModel.myUserId,
                            peerName = peerName,
                            onCancel = viewModel::cancelReply
                        )
                    }
                    if (voiceState.isRecording || voiceState.recordedFile != null) {
                        VoiceRecordingBar(
                            isRecording = voiceState.isRecording,
                            elapsedMs = voiceState.elapsedMs,
                            isPlayingPreview = voiceState.isPreviewPlaying,
                            onStop = { viewModel.voiceRecorder.stopRecording() },
                            onCancel = { viewModel.voiceRecorder.cancelRecording() },
                            onTogglePreview = { viewModel.voiceRecorder.togglePreviewPlayback() },
                            onSend = { viewModel.sendRecordedVoiceNote() }
                        )
                    } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Box {
                            IconButton(
                                onClick = { showAttachmentMenu = true },
                                modifier = Modifier.testTag("chat_attach_button")
                            ) {
                                Icon(Icons.Rounded.AttachFile, contentDescription = stringResource(R.string.attach_file))
                            }
                            DropdownMenu(
                                expanded = showAttachmentMenu,
                                onDismissRequest = { showAttachmentMenu = false }
                            ) {
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                                    text = { Text(stringResource(R.string.attach_image)) },
                                    onClick = {
                                        showAttachmentMenu = false
                                        imagePicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Rounded.Videocam, contentDescription = null) },
                                    text = { Text(stringResource(R.string.attach_video)) },
                                    onClick = {
                                        showAttachmentMenu = false
                                        videoPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Rounded.InsertDriveFile, contentDescription = null) },
                                    text = { Text(stringResource(R.string.attach_file)) },
                                    onClick = {
                                        showAttachmentMenu = false
                                        filePicker.launch(arrayOf("*/*"))
                                    }
                                )
                            }
                        }

                        OutlinedTextField(
                            value = uiState.composerText,
                            onValueChange = viewModel::updateComposerText,
                            placeholder = { Text(stringResource(R.string.hint_type_message)) },
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_message_input")
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        if (uiState.composerText.isNotBlank()) {
                            FilledIconButton(
                                onClick = viewModel::sendTextMessage,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("chat_send_button")
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
                            }
                        } else {
                            FilledIconButton(
                                onClick = {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        viewModel.voiceRecorder.startRecording()
                                    } else {
                                        showMicRationale = true
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("chat_voice_record_button")
                            ) {
                                Icon(Icons.Rounded.Mic, contentDescription = stringResource(R.string.attach_voice))
                            }
                        }
                    }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            NetworkStatusBanner(isOnline = isOnline, socketState = socketState)

            if (uiState.isLoadingInitial && messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (uiState.isLoadingOlder) {
                        item(key = "loading_older") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                        }
                    }

                    itemsIndexed(messages, key = { _, item -> item.id }) { index, msg ->
                        val prevDay = if (index > 0) {
                            DateTimeFormatters.dayBucketKey(messages[index - 1].createdAtMs)
                        } else null
                        val currentDay = DateTimeFormatters.dayBucketKey(msg.createdAtMs)

                        if (prevDay != currentDay) {
                            DateSeparatorPill(
                                label = DateTimeFormatters.formatDateSeparator(
                                    context,
                                    msg.createdAtMs,
                                    language
                                )
                            )
                        }

                        val isMine = msg.senderId == viewModel.myUserId
                        val uploadProgress = uiState.uploadProgressByClientId[msg.clientMessageId]
                        val repliedMessage = msg.replyToMessageId?.let { messagesById[it] }
                        val isAudioActive = uiState.activeAudioMessageId == msg.id
                        val isAudioPlaying = isAudioActive && uiState.isAudioPlaying
                        val isAudioLoading = isAudioActive && uiState.isAudioLoading
                        val audioPositionMs = if (isAudioActive) uiState.audioPositionMs else 0L
                        val audioDurationMs = if (isAudioActive && uiState.audioDurationMs > 0L) {
                            uiState.audioDurationMs
                        } else {
                            msg.media?.durationMs ?: 0L
                        }
                        val hasAudioError = uiState.audioErrorMessageId == msg.id
                        MessageBubble(
                            message = msg,
                            repliedMessage = repliedMessage,
                            myUserId = viewModel.myUserId,
                            peerName = peerName,
                            isMine = isMine,
                            language = language,
                            networkModule = networkModule,
                            uploadProgress = uploadProgress,
                            isAudioActive = isAudioActive,
                            isAudioPlaying = isAudioPlaying,
                            isAudioLoading = isAudioLoading,
                            audioPositionMs = audioPositionMs,
                            audioDurationMs = audioDurationMs,
                            hasAudioError = hasAudioError,
                            isDownloading = uiState.downloadingMessageId == msg.id,
                            onLongPress = { selectedMessageForDetails = msg },
                            onClickQuotedReply = {
                                val targetIdx = msg.replyToMessageId?.let { messageIndexById[it] }
                                if (targetIdx != null) {
                                    scrollScope.launch { listState.animateScrollToItem(targetIdx) }
                                }
                            },
                            onRetry = { viewModel.retryMessage(msg) },
                            onCancelUpload = { viewModel.cancelUpload(msg.clientMessageId) },
                            onSeekAudio = { posMs -> viewModel.seekVoiceMessage(msg.id, posMs) },
                            onOpenMedia = {
                                if (msg.type == MessageType.VOICE || msg.type == MessageType.AUDIO) {
                                    viewModel.toggleVoiceMessagePlayback(msg)
                                    return@MessageBubble
                                }
                                val media = msg.media ?: return@MessageBubble
                                if (msg.type == MessageType.IMAGE || msg.type == MessageType.VIDEO) {
                                    onOpenMediaViewer(
                                        media.id,
                                        media.mimeType,
                                        media.originalFilename,
                                        msg.type == MessageType.VIDEO
                                    )
                                } else {
                                    if (MediaUploadHelper.isDangerousFile(media.originalFilename)) {
                                        dangerousFileConfirmMessage = msg
                                    } else {
                                        viewModel.downloadAndOpenFile(msg)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DateSeparatorPill(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
            shape = RoundedCornerShape(50)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    repliedMessage: Message?,
    myUserId: String?,
    peerName: String,
    isMine: Boolean,
    language: AppLanguage,
    networkModule: NetworkModule,
    uploadProgress: Float?,
    isAudioActive: Boolean,
    isAudioPlaying: Boolean,
    isAudioLoading: Boolean,
    audioPositionMs: Long,
    audioDurationMs: Long,
    hasAudioError: Boolean,
    isDownloading: Boolean,
    onLongPress: () -> Unit,
    onClickQuotedReply: () -> Unit,
    onRetry: () -> Unit,
    onCancelUpload: () -> Unit,
    onSeekAudio: (Long) -> Unit,
    onOpenMedia: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bubbleColor = when {
        isMine -> if (isDark) OutgoingBubbleDark else OutgoingBubbleLight
        else -> if (isDark) IncomingBubbleDark else IncomingBubbleLight
    }
    val contentColor = if (isMine) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isMine) 18.dp else 4.dp,
                bottomEnd = if (isMine) 4.dp else 18.dp
            ),
            tonalElevation = if (isMine) 0.dp else 2.dp,
            shadowElevation = 1.dp,
            modifier = Modifier
                .widthIn(max = 310.dp)
                .combinedClickable(
                    onClick = {
                        val canPlayLocalAudio =
                            (message.type == MessageType.VOICE || message.type == MessageType.AUDIO) &&
                                !message.localUri.isNullOrBlank()
                        if ((message.media != null || canPlayLocalAudio) && message.deletedForAllAtMs == null) {
                            onOpenMedia()
                        }
                    },
                    onLongClick = onLongPress
                )
                .testTag("message_bubble_${message.id}")
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (message.deletedForAllAtMs != null) {
                    Text(
                        text = stringResource(R.string.msg_deleted_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                } else {
                    if (message.replyToMessageId != null) {
                        val quotedSender = when {
                            repliedMessage == null -> peerName
                            repliedMessage.senderId == myUserId -> stringResource(R.string.reply_to_you)
                            else -> peerName
                        }
                        val quotedPreview = when {
                            repliedMessage == null -> stringResource(R.string.reply_original_unavailable)
                            repliedMessage.deletedForAllAtMs != null -> stringResource(R.string.msg_deleted_placeholder)
                            !repliedMessage.bodyText.isNullOrBlank() -> repliedMessage.bodyText
                            repliedMessage.type == MessageType.IMAGE -> stringResource(R.string.reply_quoted_photo)
                            repliedMessage.type == MessageType.VIDEO -> stringResource(R.string.reply_quoted_video)
                            repliedMessage.type == MessageType.VOICE -> stringResource(R.string.reply_quoted_voice)
                            repliedMessage.type == MessageType.AUDIO -> stringResource(R.string.reply_quoted_audio)
                            repliedMessage.type == MessageType.FILE -> repliedMessage.media?.originalFilename ?: stringResource(R.string.reply_quoted_file)
                            else -> stringResource(R.string.reply_original_unavailable)
                        }
                        Surface(
                            color = if (isMine) {
                                androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.22f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                                .clickable(enabled = repliedMessage != null, onClick = onClickQuotedReply)
                                .testTag("quoted_reply_${message.id}")
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(32.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(
                                            if (isMine) ReadTickCyan else MaterialTheme.colorScheme.primary
                                        )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = quotedSender,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isMine) ReadTickCyan else MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = quotedPreview,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = contentColor.copy(alpha = 0.85f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    when (message.type) {
                        MessageType.IMAGE -> {
                            val imageModel: Any? = message.media?.id?.let { networkModule.mediaContentUrl(it) }
                                ?: message.localUri
                            val aspectRatio = MediaUploadHelper.computeBubbleAspectRatio(
                                message.media?.width,
                                message.media?.height
                            )
                            val imageModifier = if (aspectRatio != null) {
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(aspectRatio)
                                    .clip(RoundedCornerShape(12.dp))
                            } else {
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 120.dp, max = 320.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            }
                            if (imageModel != null) {
                                SubcomposeAsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(imageModel)
                                        .crossfade(true)
                                        .build(),
                                    imageLoader = networkModule.imageLoader,
                                    contentDescription = message.media?.originalFilename ?: "Image",
                                    contentScale = ContentScale.Fit,
                                    loading = {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(160.dp)
                                                .background(Color.Black.copy(alpha = 0.14f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                color = contentColor,
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    },
                                    error = {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(130.dp)
                                                .background(Color.Black.copy(alpha = 0.18f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(
                                                    imageVector = Icons.Rounded.ErrorOutline,
                                                    contentDescription = null,
                                                    tint = contentColor.copy(alpha = 0.85f)
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = stringResource(R.string.media_load_error),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = contentColor.copy(alpha = 0.85f)
                                                )
                                            }
                                        }
                                    },
                                    modifier = imageModifier
                                )
                            }
                            if (!message.bodyText.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(message.bodyText, color = contentColor, style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        MessageType.VIDEO -> {
                            Surface(
                                color = Color.Black.copy(alpha = 0.28f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(contentColor.copy(alpha = 0.22f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = contentColor)
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = message.media?.originalFilename ?: stringResource(R.string.attach_video),
                                            color = contentColor,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val durationLabel = message.media?.durationMs
                                            ?.takeIf { it > 0L }
                                            ?.let { DateTimeFormatters.formatDuration(it / 1000L) }
                                        val sizeLabel = message.media?.sizeBytes?.let { MediaUploadHelper.formatFileSize(it) }
                                        val metaText = listOfNotNull(durationLabel, sizeLabel).joinToString(" • ")
                                        if (metaText.isNotBlank()) {
                                            Text(
                                                text = metaText,
                                                color = contentColor.copy(alpha = 0.78f),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                            }
                            if (!message.bodyText.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(message.bodyText, color = contentColor, style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        MessageType.VOICE, MessageType.AUDIO -> {
                            val totalMs = audioDurationMs.coerceAtLeast(0L)
                            val posMs = if (isAudioActive) audioPositionMs.coerceIn(0L, totalMs.coerceAtLeast(1L)) else 0L
                            val sliderMax = totalMs.coerceAtLeast(1L).toFloat()
                            val sliderValue = posMs.toFloat().coerceIn(0f, sliderMax)
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    IconButton(
                                        onClick = onOpenMedia,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .testTag("voice_play_button_${message.id}")
                                    ) {
                                        if (isAudioLoading) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = contentColor
                                            )
                                        } else {
                                            Icon(
                                                imageVector = if (isAudioPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                                contentDescription = stringResource(R.string.attach_voice),
                                                tint = contentColor
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (message.type == MessageType.VOICE) stringResource(R.string.attach_voice)
                                            else (message.media?.originalFilename ?: "Audio"),
                                            color = contentColor,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Slider(
                                            value = sliderValue,
                                            onValueChange = { nextPos -> onSeekAudio(nextPos.toLong()) },
                                            valueRange = 0f..sliderMax,
                                            enabled = isAudioActive && !isAudioLoading && totalMs > 0L,
                                            colors = SliderDefaults.colors(
                                                thumbColor = contentColor,
                                                activeTrackColor = contentColor,
                                                inactiveTrackColor = contentColor.copy(alpha = 0.28f),
                                                disabledThumbColor = contentColor.copy(alpha = 0.65f),
                                                disabledActiveTrackColor = contentColor.copy(alpha = 0.55f),
                                                disabledInactiveTrackColor = contentColor.copy(alpha = 0.22f)
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(26.dp)
                                                .testTag("voice_seek_slider_${message.id}")
                                        )
                                        val timeLabel = when {
                                            isAudioLoading -> stringResource(R.string.voice_loading)
                                            totalMs > 0L -> "${DateTimeFormatters.formatDuration(posMs / 1000L)} / ${DateTimeFormatters.formatDuration(totalMs / 1000L)}"
                                            else -> DateTimeFormatters.formatDuration(posMs / 1000L)
                                        }
                                        Text(
                                            text = if (hasAudioError) stringResource(R.string.voice_play_error) else timeLabel,
                                            color = if (hasAudioError) MaterialTheme.colorScheme.errorContainer else contentColor.copy(alpha = 0.8f),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }

                        MessageType.FILE -> {
                            val media = message.media
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.InsertDriveFile,
                                    contentDescription = null,
                                    tint = contentColor,
                                    modifier = Modifier.size(34.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = media?.originalFilename ?: "File",
                                        color = contentColor,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sizeLabel = media?.sizeBytes?.let { MediaUploadHelper.formatFileSize(it) }.orEmpty()
                                    val extLabel = media?.originalFilename?.substringAfterLast('.', "")?.uppercase().orEmpty()
                                    Text(
                                        text = listOf(extLabel, sizeLabel).filter { it.isNotBlank() }.joinToString(" • "),
                                        color = contentColor.copy(alpha = 0.75f),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (isDownloading) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = contentColor)
                                } else {
                                    Icon(Icons.Rounded.Download, contentDescription = null, tint = contentColor)
                                }
                            }
                        }

                        else -> {
                            Text(
                                text = message.bodyText.orEmpty(),
                                color = contentColor,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }

                    if (uploadProgress != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                progress = { uploadProgress },
                                modifier = Modifier.weight(1f),
                                color = contentColor
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = onCancelUpload, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel), tint = contentColor, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = DateTimeFormatters.formatTimeOnly(message.createdAtMs, language),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.75f)
                    )
                    if (isMine) {
                        when (message.status) {
                            MessageStatus.SENDING -> Icon(
                                Icons.Rounded.Schedule,
                                contentDescription = stringResource(R.string.msg_status_sending),
                                modifier = Modifier.size(14.dp),
                                tint = contentColor.copy(alpha = 0.75f)
                            )
                            MessageStatus.SENT -> Icon(
                                Icons.Rounded.Done,
                                contentDescription = stringResource(R.string.msg_status_sent),
                                modifier = Modifier.size(15.dp),
                                tint = contentColor.copy(alpha = 0.85f)
                            )
                            MessageStatus.DELIVERED -> Icon(
                                Icons.Rounded.DoneAll,
                                contentDescription = stringResource(R.string.msg_status_delivered),
                                modifier = Modifier.size(15.dp),
                                tint = contentColor.copy(alpha = 0.85f)
                            )
                            MessageStatus.READ -> Icon(
                                Icons.Rounded.DoneAll,
                                contentDescription = stringResource(R.string.msg_status_read),
                                modifier = Modifier.size(15.dp),
                                tint = ReadTickCyan
                            )
                            MessageStatus.FAILED -> Icon(
                                Icons.Rounded.ErrorOutline,
                                contentDescription = stringResource(R.string.msg_status_failed),
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.errorContainer
                            )
                        }
                    }
                }
            }
        }

        if (message.status == MessageStatus.FAILED && isMine) {
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.testTag("retry_message_${message.id}")
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.action_retry), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun VoiceRecordingBar(
    isRecording: Boolean,
    elapsedMs: Long,
    isPlayingPreview: Boolean,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onTogglePreview: () -> Unit,
    onSend: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel))
            }
            Text(
                text = if (isRecording) {
                    "${stringResource(R.string.recording_voice)} ${DateTimeFormatters.formatDuration(elapsedMs / 1000L)}"
                } else {
                    "${stringResource(R.string.attach_voice)} • ${DateTimeFormatters.formatDuration(elapsedMs / 1000L)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            if (isRecording) {
                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.testTag("voice_stop_button")
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = "Stop")
                }
                Spacer(modifier = Modifier.width(8.dp))
                FilledIconButton(
                    onClick = onSend,
                    modifier = Modifier.testTag("voice_send_now_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
                }
            } else {
                IconButton(
                    onClick = onTogglePreview,
                    modifier = Modifier.testTag("voice_preview_button")
                ) {
                    Icon(
                        if (isPlayingPreview) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Preview"
                    )
                }
                FilledIconButton(
                    onClick = onSend,
                    modifier = Modifier.testTag("voice_send_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun ReplyComposerPreviewBar(
    target: Message,
    isMineTarget: Boolean,
    peerName: String,
    onCancel: () -> Unit
) {
    val senderLabel = if (isMineTarget) stringResource(R.string.reply_to_you) else peerName
    val previewText = when {
        !target.bodyText.isNullOrBlank() -> target.bodyText
        target.type == MessageType.IMAGE -> stringResource(R.string.reply_quoted_photo)
        target.type == MessageType.VIDEO -> stringResource(R.string.reply_quoted_video)
        target.type == MessageType.VOICE -> stringResource(R.string.reply_quoted_voice)
        target.type == MessageType.AUDIO -> stringResource(R.string.reply_quoted_audio)
        target.type == MessageType.FILE -> target.media?.originalFilename ?: stringResource(R.string.reply_quoted_file)
        else -> ""
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("reply_composer_preview")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.msg_replying_to, senderLabel),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("cancel_reply_button")
            ) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel))
            }
        }
    }
}

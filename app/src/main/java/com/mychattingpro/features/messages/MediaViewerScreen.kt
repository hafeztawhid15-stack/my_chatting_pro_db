package com.mychattingpro.features.messages

import android.net.Uri
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.mychattingpro.core.common.AppError
import com.mychattingpro.core.network.NetworkModule
import com.mychattingpro.data.repository.AppException
import com.mychattingpro.domain.repository.MessageRepository
import com.mychattingpro.domain.repository.PrivateChatRepository
import com.mychattingpro.features.media.MediaUploadHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

private enum class ViewerDeleteStep {
    NONE,
    CHOOSE_SCOPE,
    CONFIRM_FOR_ME,
    CONFIRM_FOR_EVERYONE
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    mediaId: String,
    filename: String,
    isVideo: Boolean,
    messageId: String? = null,
    isMine: Boolean = false,
    isPrivate: Boolean = false,
    networkModule: NetworkModule,
    messageRepository: MessageRepository,
    privateChatRepository: PrivateChatRepository? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var videoPlaybackUri by remember(mediaId) { mutableStateOf<Uri?>(null) }
    var isResolvingVideo by remember(mediaId) { mutableStateOf(isVideo) }
    var errorMessage by remember(mediaId) { mutableStateOf<String?>(null) }
    var retryTrigger by remember(mediaId) { mutableIntStateOf(0) }
    var isSavingImage by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var deleteStep by remember { mutableStateOf(ViewerDeleteStep.NONE) }

    fun executeDelete(forEveryone: Boolean) {
        val targetMsgId = messageId?.takeIf { it.isNotBlank() } ?: return
        isDeleting = true
        scope.launch {
            val result = when {
                forEveryone && isPrivate && privateChatRepository != null ->
                    privateChatRepository.deleteOwnPrivateMessage(targetMsgId)
                forEveryone ->
                    messageRepository.deleteOwnMessage(targetMsgId)
                isPrivate && privateChatRepository != null ->
                    privateChatRepository.deletePrivateMessageForMe(targetMsgId)
                else ->
                    messageRepository.deleteMessageForMe(targetMsgId)
            }
            isDeleting = false
            result.onSuccess {
                Toast.makeText(
                    context,
                    context.getString(
                        if (forEveryone) R.string.msg_deleted_for_everyone_notice
                        else R.string.msg_deleted_for_me_notice
                    ),
                    Toast.LENGTH_SHORT
                ).show()
                onBack()
            }.onFailure { err ->
                val appErr = (err as? AppException)?.appError ?: AppError.ServerError()
                Toast.makeText(context, appErr.toLocalizedMessage(context), Toast.LENGTH_LONG).show()
            }
        }
    }

    when (deleteStep) {
        ViewerDeleteStep.CHOOSE_SCOPE -> {
            AlertDialog(
                onDismissRequest = { if (!isDeleting) deleteStep = ViewerDeleteStep.NONE },
                title = { Text(stringResource(R.string.delete_msg_confirm_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { deleteStep = ViewerDeleteStep.CONFIRM_FOR_ME },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.testTag("viewer_delete_for_me")
                        ) {
                            Text(stringResource(R.string.action_delete_for_me))
                        }
                        if (isMine && messageId?.startsWith("pending_") != true) {
                            TextButton(
                                onClick = { deleteStep = ViewerDeleteStep.CONFIRM_FOR_EVERYONE },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.testTag("viewer_delete_for_everyone")
                            ) {
                                Text(stringResource(R.string.action_delete_message))
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { deleteStep = ViewerDeleteStep.NONE }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
        ViewerDeleteStep.CONFIRM_FOR_ME,
        ViewerDeleteStep.CONFIRM_FOR_EVERYONE -> {
            val isEveryone = deleteStep == ViewerDeleteStep.CONFIRM_FOR_EVERYONE
            AlertDialog(
                onDismissRequest = { if (!isDeleting) deleteStep = ViewerDeleteStep.NONE },
                title = { Text(stringResource(R.string.delete_msg_confirm_title)) },
                text = {
                    Text(
                        if (isEveryone) {
                            stringResource(R.string.delete_for_everyone_confirm_body)
                        } else {
                            stringResource(R.string.delete_for_me_confirm_body)
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !isDeleting,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            deleteStep = ViewerDeleteStep.NONE
                            executeDelete(forEveryone = isEveryone)
                        },
                        modifier = Modifier.testTag("viewer_confirm_delete_button")
                    ) {
                        Text(stringResource(R.string.action_delete))
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !isDeleting,
                        onClick = { deleteStep = ViewerDeleteStep.NONE }
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
        ViewerDeleteStep.NONE -> Unit
    }

    LaunchedEffect(mediaId, retryTrigger) {
        if (!isVideo) return@LaunchedEffect
        isResolvingVideo = true
        errorMessage = null

        val cachedVideoFile = File(File(context.cacheDir, "video_cache").apply { mkdirs() }, "$mediaId.mp4")
        if (cachedVideoFile.exists() && cachedVideoFile.length() > 0L && retryTrigger == 0) {
            videoPlaybackUri = Uri.fromFile(cachedVideoFile)
            isResolvingVideo = false
            return@LaunchedEffect
        }

        val signedRes = messageRepository.getSignedMediaUrl(mediaId)
        val signed = signedRes.getOrNull()
        if (!signed.isNullOrBlank() && retryTrigger == 0) {
            videoPlaybackUri = Uri.parse(signed)
            isResolvingVideo = false
            return@LaunchedEffect
        }

        // Fallback: stream/download via authenticated OkHttp client (carries JWT & Private unlock token)
        val downloadedUri = withContext(Dispatchers.IO) {
            runCatching {
                val url = signed ?: networkModule.mediaContentUrl(mediaId)
                val temp = File(cachedVideoFile.parentFile, "$mediaId.tmp")
                networkModule.okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    resp.body?.byteStream()?.use { input ->
                        FileOutputStream(temp).use { output -> input.copyTo(output) }
                    } ?: error("Empty body")
                }
                if (cachedVideoFile.exists()) cachedVideoFile.delete()
                if (!temp.renameTo(cachedVideoFile)) {
                    temp.copyTo(cachedVideoFile, overwrite = true)
                    temp.delete()
                }
                Uri.fromFile(cachedVideoFile)
            }.getOrNull()
        }

        isResolvingVideo = false
        if (downloadedUri != null) {
            videoPlaybackUri = downloadedUri
        } else {
            errorMessage = context.getString(R.string.video_playback_error)
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                title = {
                    Text(
                        text = filename,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("media_viewer_back")) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    if (!messageId.isNullOrBlank()) {
                        IconButton(
                            enabled = !isDeleting,
                            onClick = {
                                deleteStep = if (isMine && !messageId.startsWith("pending_")) {
                                    ViewerDeleteStep.CHOOSE_SCOPE
                                } else {
                                    ViewerDeleteStep.CONFIRM_FOR_ME
                                }
                            },
                            modifier = Modifier.testTag("media_viewer_delete_button")
                        ) {
                            if (isDeleting) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = stringResource(R.string.action_delete)
                                )
                            }
                        }
                    }
                    if (!isVideo) {
                        IconButton(
                            enabled = !isSavingImage && !isDeleting,
                            onClick = {
                                isSavingImage = true
                                scope.launch {
                                    val savedResult = runCatching {
                                        val bytes = withContext(Dispatchers.IO) {
                                            val directUrl = networkModule.mediaContentUrl(mediaId)
                                            val request = Request.Builder().url(directUrl).build()
                                            networkModule.okHttpClient.newCall(request).execute().use { response ->
                                                if (response.isSuccessful) {
                                                    return@use response.body?.bytes()
                                                        ?: error("Empty image body")
                                                }
                                                error("HTTP ${response.code}")
                                            }
                                        }
                                        MediaUploadHelper.saveImageToGallery(
                                            context = context,
                                            imageBytes = bytes,
                                            preferredFilename = filename.ifBlank { "MCP_${System.currentTimeMillis()}.jpg" },
                                            mimeType = if (filename.lowercase().endsWith(".png")) "image/png" else "image/jpeg"
                                        )
                                    }
                                    isSavingImage = false
                                    savedResult.onSuccess {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.media_saved_to_gallery),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }.onFailure {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.media_save_failed),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            },
                            modifier = Modifier.testTag("media_viewer_save_button")
                        ) {
                            if (isSavingImage) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = stringResource(R.string.action_save_image)
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            if (isVideo) {
                when {
                    errorMessage != null -> {
                        MediaErrorRetryView(
                            message = errorMessage!!,
                            onRetry = { retryTrigger++ }
                        )
                    }
                    isResolvingVideo || videoPlaybackUri == null -> {
                        CircularProgressIndicator(color = Color.White)
                    }
                    else -> {
                        ExoVideoPlayer(
                            uri = videoPlaybackUri!!,
                            onFallbackRequired = {
                                // Switch from remote signed URL to authenticated download fallback on player failure
                                if (videoPlaybackUri?.scheme != "file") {
                                    retryTrigger++
                                } else {
                                    errorMessage = context.getString(R.string.video_playback_error)
                                }
                            }
                        )
                    }
                }
            } else {
                ZoomablePrivateImage(
                    mediaUrl = networkModule.mediaContentUrl(mediaId),
                    networkModule = networkModule,
                    contentDescription = filename
                )
            }
        }
    }
}

@Composable
private fun MediaErrorRetryView(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(44.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.testTag("media_viewer_retry_button")
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(6.dp))
            Text(stringResource(R.string.media_retry))
        }
    }
}

@Composable
private fun ZoomablePrivateImage(
    mediaUrl: String,
    networkModule: NetworkModule,
    contentDescription: String
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var retryCount by remember(mediaUrl) { mutableIntStateOf(0) }

    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context)
            .data(mediaUrl)
            .setParameter("retry", retryCount, memoryCacheKey = null)
            .crossfade(true)
            .build(),
        imageLoader = networkModule.imageLoader,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        loading = {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        },
        error = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(42.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.media_load_error),
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = { retryCount++ },
                    modifier = Modifier.testTag("media_image_retry_button")
                ) {
                    Text(stringResource(R.string.media_retry), color = Color.White)
                }
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 4f)
                    scale = nextScale
                    offset = if (nextScale > 1f) offset + pan else Offset.Zero
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun ExoVideoPlayer(
    uri: Uri,
    onFallbackRequired: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isBuffering by remember(uri) { mutableStateOf(true) }

    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                onFallbackRequired()
            }
        }
        player.addListener(listener)

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(listener)
            player.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { view ->
                view.player = player
            },
            modifier = Modifier.fillMaxSize()
        )
        if (isBuffering) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

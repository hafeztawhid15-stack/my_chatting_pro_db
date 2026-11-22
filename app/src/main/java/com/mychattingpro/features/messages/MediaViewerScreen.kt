package com.mychattingpro.features.messages

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.mychattingpro.core.network.NetworkModule
import com.mychattingpro.domain.repository.MessageRepository
import com.mychattingpro.features.media.MediaUploadHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    mediaId: String,
    filename: String,
    isVideo: Boolean,
    networkModule: NetworkModule,
    messageRepository: MessageRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var signedUrl by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSavingImage by remember { mutableStateOf(false) }

    LaunchedEffect(mediaId) {
        if (isVideo) {
            messageRepository.getSignedMediaUrl(mediaId)
                .onSuccess { signedUrl = it }
                .onFailure { errorMessage = context.getString(R.string.err_storage) }
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
                    if (!isVideo) {
                        IconButton(
                            enabled = !isSavingImage,
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
                    errorMessage != null -> Text(errorMessage!!, color = Color.White)
                    signedUrl == null -> CircularProgressIndicator(color = Color.White)
                    else -> ExoVideoPlayer(url = signedUrl!!)
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
private fun ZoomablePrivateImage(
    mediaUrl: String,
    networkModule: NetworkModule,
    contentDescription: String
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(mediaUrl)
            .crossfade(true)
            .build(),
        imageLoader = networkModule.imageLoader,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
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

@Composable
private fun ExoVideoPlayer(url: String) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

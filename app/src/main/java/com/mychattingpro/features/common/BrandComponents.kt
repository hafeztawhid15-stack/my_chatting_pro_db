package com.mychattingpro.features.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.mychattingpro.core.network.NetworkModule
import com.mychattingpro.core.network.SocketConnectionState
import com.mychattingpro.core.theme.OnlineGreen

@Composable
fun UserAvatar(
    displayName: String,
    avatarMediaId: String?,
    networkModule: NetworkModule,
    size: Dp = 48.dp,
    isOnline: Boolean = false,
    showOfflineDot: Boolean = false,
    onClick: (() -> Unit)? = null,
    enablePreviewOnTap: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val initials = displayName
        .trim()
        .split(Regex("\\s+"))
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifEmpty { "M" }

    var retryCount by remember(avatarMediaId) { mutableStateOf(0) }
    var imageLoadFailed by remember(avatarMediaId) {
        mutableStateOf(false)
    }
    var showAvatarPreview by remember(avatarMediaId, displayName) {
        mutableStateOf(false)
    }
    val clickLabel = stringResource(R.string.profile_photo_preview_cd, displayName)
    val effectiveOnClick: (() -> Unit)? = when {
        onClick != null -> onClick
        enablePreviewOnTap -> { { showAvatarPreview = true } }
        else -> null
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .then(
                    if (effectiveOnClick != null) {
                        Modifier.clickable(
                            onClickLabel = clickLabel,
                            onClick = effectiveOnClick
                        )
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (!avatarMediaId.isNullOrBlank() && !imageLoadFailed) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(networkModule.mediaContentUrl(avatarMediaId))
                        .setParameter("retry", retryCount, memoryCacheKey = null)
                        .crossfade(true)
                        .build(),
                    imageLoader = networkModule.imageLoader,
                    contentDescription = displayName,
                    contentScale = ContentScale.Crop,
                    onError = {
                        if (retryCount < 1) {
                            retryCount++
                        } else {
                            imageLoadFailed = true
                        }
                    },
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        if (isOnline || showOfflineDot) {
            val dotColor = if (isOnline) OnlineGreen else MaterialTheme.colorScheme.outline
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size((size.value * 0.28f).dp.coerceAtLeast(11.dp))
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }

    if (showAvatarPreview) {
        ProfileAvatarPreviewDialog(
            displayName = displayName,
            avatarMediaId = avatarMediaId,
            networkModule = networkModule,
            onDismiss = { showAvatarPreview = false }
        )
    }
}

@Composable
fun ProfileAvatarPreviewDialog(
    displayName: String,
    avatarMediaId: String?,
    networkModule: NetworkModule,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val initials = remember(displayName) {
        displayName
            .trim()
            .split(Regex("\\s+"))
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
            .ifEmpty { "M" }
    }
    var scale by remember(avatarMediaId) { mutableFloatStateOf(1f) }
    var offset by remember(avatarMediaId) { mutableStateOf(Offset.Zero) }
    var retryCount by remember(avatarMediaId) { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("profile_avatar_preview_dialog")
        ) {
            if (!avatarMediaId.isNullOrBlank()) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(networkModule.mediaContentUrl(avatarMediaId))
                        .setParameter("retry", retryCount, memoryCacheKey = null)
                        .crossfade(true)
                        .build(),
                    imageLoader = networkModule.imageLoader,
                    contentDescription = stringResource(R.string.profile_photo_preview_cd, displayName),
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
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.media_load_error),
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            TextButton(
                                onClick = { retryCount++ },
                                modifier = Modifier.testTag("profile_avatar_preview_retry")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.media_retry), color = Color.White)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .pointerInput(avatarMediaId) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1.2f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.4f
                                    }
                                }
                            )
                        }
                        .pointerInput(avatarMediaId) {
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
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initials,
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.profile_no_photo_preview),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.78f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.72f),
                                Color.Transparent
                            )
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("profile_avatar_preview_close")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun PresenceBadge(
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    onDarkSurface: Boolean = false
) {
    val dotColor = if (isOnline) {
        if (onDarkSurface) androidx.compose.ui.graphics.Color(0xFF4ADE80) else OnlineGreen
    } else {
        if (onDarkSurface) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.55f)
        else MaterialTheme.colorScheme.outline
    }
    val containerColor = when {
        onDarkSurface && isOnline -> androidx.compose.ui.graphics.Color(0xFF16A34A).copy(alpha = 0.24f)
        onDarkSurface -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f)
        isOnline -> OnlineGreen.copy(alpha = 0.13f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    }
    val textColor = when {
        onDarkSurface && isOnline -> androidx.compose.ui.graphics.Color(0xFF86EFAC)
        onDarkSurface -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.80f)
        isOnline -> OnlineGreen
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = stringResource(if (isOnline) R.string.status_online else R.string.status_user_offline),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                maxLines = 1
            )
        }
    }
}

@Composable
fun NetworkStatusBanner(
    isOnline: Boolean,
    socketState: SocketConnectionState
) {
    val showBanner = !isOnline || socketState == SocketConnectionState.RECONNECTING
    AnimatedVisibility(visible = showBanner) {
        Surface(
            color = if (!isOnline) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (!isOnline) Icons.Rounded.CloudOff else Icons.Rounded.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (!isOnline) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (!isOnline) {
                        stringResource(R.string.status_offline)
                    } else {
                        stringResource(R.string.status_reconnecting)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (!isOnline) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
            }
        }
    }
}

@Composable
fun FiveDigitPinField(
    pin: String,
    onPinChange: (String) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    imeAction: androidx.compose.ui.text.input.ImeAction = androidx.compose.ui.text.input.ImeAction.Done,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default
) {
    BasicTextField(
        value = pin,
        onValueChange = { raw ->
            val filtered = raw.filter { it.isDigit() }.take(5)
            onPinChange(filtered)
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = imeAction
        ),
        keyboardActions = keyboardActions,
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
        decorationBox = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 300.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(5) { idx ->
                        val filled = idx < pin.length
                        val isFocused = idx == pin.length
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (filled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                                .border(
                                    width = if (isFocused) 2.dp else 1.dp,
                                    color = if (isFocused) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(14.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (filled) "●" else "",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    )
}

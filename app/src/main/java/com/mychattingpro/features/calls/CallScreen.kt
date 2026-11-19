package com.mychattingpro.features.calls

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.mychattingpro.core.localization.DateTimeFormatters
import com.mychattingpro.core.network.NetworkModule
import com.mychattingpro.features.common.UserAvatar
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun CallScreen(
    viewModel: CallViewModel,
    peerName: String,
    peerAvatarMediaId: String?,
    networkModule: NetworkModule,
    onExitCall: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var didExit by remember { mutableStateOf(false) }

    fun exitCallOnce() {
        if (!didExit) {
            didExit = true
            onExitCall()
        }
    }

    BackHandler {
        viewModel.endCall { exitCallOnce() }
    }

    val requiredPermissions = remember(state.isVideo) {
        if (state.isVideo) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        else arrayOf(Manifest.permission.RECORD_AUDIO)
    }

    var permissionsGranted by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    var showPermissionRationale by remember { mutableStateOf(!permissionsGranted) }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allOk = requiredPermissions.all { result[it] == true }
        permissionsGranted = allOk
        if (allOk) {
            viewModel.beginAfterPermissionsGranted()
        } else {
            viewModel.endCall { exitCallOnce() }
        }
    }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            viewModel.beginAfterPermissionsGranted()
        }
    }

    LaunchedEffect(state.stage) {
        if (state.stage == WebRtcConnectionStage.ENDED) {
            exitCallOnce()
        }
    }

    if (showPermissionRationale && !permissionsGranted) {
        AlertDialog(
            onDismissRequest = {
                showPermissionRationale = false
                exitCallOnce()
            },
            title = {
                Text(
                    if (state.isVideo) stringResource(R.string.call_video)
                    else stringResource(R.string.call_audio)
                )
            },
            text = {
                Text(
                    if (state.isVideo) {
                        "${stringResource(R.string.perm_mic_rationale)}\n${stringResource(R.string.perm_camera_rationale)}"
                    } else {
                        stringResource(R.string.perm_mic_rationale)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionRationale = false
                    permLauncher.launch(requiredPermissions)
                }) {
                    Text(stringResource(R.string.action_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPermissionRationale = false
                    exitCallOnce()
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0A2421), Color(0xFF0D1514), Color(0xFF080D0C))
                )
            )
    ) {
        if (state.isVideo && permissionsGranted) {
            // Remote video full-screen surface
            val remoteRenderer = remember {
                SurfaceViewRenderer(context).apply {
                    init(viewModel.webRtcManager.eglBase.eglBaseContext, null)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    setEnableHardwareScaler(true)
                }
            }
            val localRenderer = remember {
                SurfaceViewRenderer(context).apply {
                    init(viewModel.webRtcManager.eglBase.eglBaseContext, null)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    setMirror(true)
                    setZOrderMediaOverlay(true)
                }
            }

            DisposableEffect(remoteRenderer, localRenderer) {
                viewModel.attachVideoSinks(local = localRenderer, remote = remoteRenderer)
                onDispose {
                    viewModel.attachVideoSinks(null, null)
                    runCatching { localRenderer.release() }
                    runCatching { remoteRenderer.release() }
                }
            }

            AndroidView(
                factory = { remoteRenderer },
                modifier = Modifier.fillMaxSize()
            )

            // Local picture-in-picture preview
            if (state.isCameraOn) {
                AndroidView(
                    factory = { localRenderer },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(16.dp)
                        .size(width = 112.dp, height = 160.dp)
                        .clip(RoundedCornerShape(18.dp))
                )
            }
        }

        // Top caller info & status
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 48.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!state.isVideo || state.stage != WebRtcConnectionStage.CONNECTED) {
                UserAvatar(
                    displayName = peerName,
                    avatarMediaId = peerAvatarMediaId,
                    networkModule = networkModule,
                    size = 96.dp
                )
                Spacer(modifier = Modifier.height(18.dp))
            }

            Text(
                text = peerName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))

            val stageLabel = when (state.stage) {
                WebRtcConnectionStage.CALLING -> stringResource(R.string.call_calling)
                WebRtcConnectionStage.RINGING -> {
                    if (state.isIncoming) {
                        if (state.isVideo) stringResource(R.string.call_incoming_video)
                        else stringResource(R.string.call_incoming_audio)
                    } else {
                        stringResource(R.string.call_ringing)
                    }
                }
                WebRtcConnectionStage.CONNECTING -> stringResource(R.string.call_connecting)
                WebRtcConnectionStage.CONNECTED -> DateTimeFormatters.formatDuration(state.durationSeconds)
                WebRtcConnectionStage.RECONNECTING -> stringResource(R.string.call_reconnecting)
                WebRtcConnectionStage.FAILED -> state.error?.toLocalizedMessage(context) ?: stringResource(R.string.err_call)
                WebRtcConnectionStage.ENDED -> stringResource(R.string.call_ended)
                WebRtcConnectionStage.IDLE -> ""
            }

            Text(
                text = stageLabel,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF9DF2E3)
            )
        }

        // Bottom call controls
        Surface(
            color = Color.Black.copy(alpha = 0.45f),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(vertical = 22.dp, horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.isIncoming && state.stage == WebRtcConnectionStage.RINGING) {
                    FilledIconButton(
                        onClick = { viewModel.rejectIncomingCall { exitCallOnce() } },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFD93B30)),
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .testTag("call_reject_button")
                    ) {
                        Icon(Icons.Rounded.CallEnd, contentDescription = stringResource(R.string.call_reject), tint = Color.White)
                    }
                    FilledIconButton(
                        onClick = { viewModel.acceptIncomingCall() },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF16A34A)),
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .testTag("call_accept_button")
                    ) {
                        Icon(Icons.Rounded.Call, contentDescription = stringResource(R.string.call_accept), tint = Color.White)
                    }
                } else {
                    FilledIconButton(
                        onClick = viewModel::toggleMute,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (state.isMuted) Color.White else Color.White.copy(alpha = 0.16f)
                        ),
                        modifier = Modifier
                            .size(54.dp)
                            .testTag("call_mute_button")
                    ) {
                        Icon(
                            imageVector = if (state.isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            contentDescription = stringResource(R.string.call_mute),
                            tint = if (state.isMuted) Color.Black else Color.White
                        )
                    }

                    if (state.isVideo) {
                        FilledIconButton(
                            onClick = viewModel::toggleCamera,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (!state.isCameraOn) Color.White else Color.White.copy(alpha = 0.16f)
                            ),
                            modifier = Modifier
                                .size(54.dp)
                                .testTag("call_camera_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (state.isCameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                                contentDescription = stringResource(R.string.call_camera_off),
                                tint = if (!state.isCameraOn) Color.Black else Color.White
                            )
                        }

                        FilledIconButton(
                            onClick = viewModel::switchCamera,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.16f)
                            ),
                            modifier = Modifier
                                .size(54.dp)
                                .testTag("call_switch_camera_button")
                        ) {
                            Icon(
                                Icons.Rounded.Cameraswitch,
                                contentDescription = stringResource(R.string.call_flip_camera),
                                tint = Color.White
                            )
                        }
                    }

                    FilledIconButton(
                        onClick = viewModel::toggleSpeaker,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (state.isSpeakerOn) Color.White else Color.White.copy(alpha = 0.16f)
                        ),
                        modifier = Modifier
                            .size(54.dp)
                            .testTag("call_speaker_button")
                    ) {
                        Icon(
                            Icons.Rounded.VolumeUp,
                            contentDescription = stringResource(R.string.call_speaker),
                            tint = if (state.isSpeakerOn) Color.Black else Color.White
                        )
                    }

                    FilledIconButton(
                        onClick = { viewModel.endCall { exitCallOnce() } },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFD93B30)),
                        modifier = Modifier
                            .size(62.dp)
                            .testTag("call_end_button")
                    ) {
                        Icon(
                            Icons.Rounded.CallEnd,
                            contentDescription = stringResource(R.string.call_end),
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

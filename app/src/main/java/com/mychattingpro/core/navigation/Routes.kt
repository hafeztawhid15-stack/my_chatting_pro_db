package com.mychattingpro.core.navigation

import kotlinx.serialization.Serializable

sealed interface AppRoute {
    @Serializable
    data object Splash : AppRoute

    @Serializable
    data object Welcome : AppRoute

    @Serializable
    data object Login : AppRoute

    @Serializable
    data object CreateAccount : AppRoute

    @Serializable
    data object MainTabs : AppRoute

    @Serializable
    data class ChatDetail(
        val conversationId: String,
        val peerUserId: String,
        val peerName: String
    ) : AppRoute

    @Serializable
    data object Profile : AppRoute

    @Serializable
    data object BlockedUsers : AppRoute

    @Serializable
    data object ActiveDevices : AppRoute

    @Serializable
    data object OwnerActivity : AppRoute

    @Serializable
    data class Call(
        val callId: String,
        val conversationId: String,
        val peerUserId: String,
        val peerName: String,
        val peerAvatarMediaId: String?,
        val isVideo: Boolean,
        val isIncoming: Boolean
    ) : AppRoute

    @Serializable
    data class MediaViewer(
        val mediaId: String,
        val mimeType: String,
        val filename: String,
        val isVideo: Boolean
    ) : AppRoute
}

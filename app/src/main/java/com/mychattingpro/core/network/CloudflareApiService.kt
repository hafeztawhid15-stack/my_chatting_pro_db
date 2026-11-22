package com.mychattingpro.core.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface CloudflareApiService {

    @POST("v1/auth/check-phone")
    suspend fun checkPhoneAvailable(@Body body: CheckPhoneRequestDto): CheckPhoneResponseDto

    @POST("v1/auth/register")
    suspend fun register(@Body body: RegisterRequestDto): AuthResponseDto

    @POST("v1/auth/login")
    suspend fun login(@Body body: LoginRequestDto): AuthResponseDto

    @POST("v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequestDto): RefreshResponseDto

    @POST("v1/auth/logout")
    suspend fun logout()

    @POST("v1/auth/logout-all")
    suspend fun logoutAllDevices(@Body body: LogoutAllRequestDto): LogoutAllResponseDto

    @POST("v1/auth/pin/change")
    suspend fun changeLoginPin(@Body body: ChangeLoginPinRequestDto): ChangeLoginPinResponseDto

    @POST("v1/auth/recovery-code/generate")
    suspend fun generateRecoveryCode(@Body body: GenerateRecoveryCodeRequestDto): GenerateRecoveryCodeResponseDto

    @POST("v1/auth/pin/reset")
    suspend fun resetLoginPinWithRecoveryCode(@Body body: ResetLoginPinRequestDto): AuthResponseDto

    @GET("v1/profiles/me")
    suspend fun getMyProfile(): ProfileDto

    @PATCH("v1/profiles/me")
    suspend fun updateMyProfile(@Body body: UpdateProfileRequestDto): ProfileDto

    @POST("v1/profiles/account/delete")
    suspend fun deleteAccount(@Body body: DeleteAccountRequestDto): DeleteAccountResponseDto

    @GET("v1/users/search")
    suspend fun searchUsers(
        @Query("phone") phone: String,
        @Query("name") name: String? = null
    ): UserSearchResponseDto

    @GET("v1/conversations")
    suspend fun listConversations(): ConversationsListResponseDto

    @POST("v1/conversations/direct")
    suspend fun createOrGetDirectConversation(@Body body: DirectConversationRequestDto): SingleConversationResponseDto

    @PATCH("v1/conversations/{conversationId}/preferences")
    suspend fun updateConversationPreferences(
        @Path("conversationId") conversationId: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    )

    @POST("v1/conversations/{conversationId}/clear")
    suspend fun clearConversation(@Path("conversationId") conversationId: String): ClearConversationResponseDto

    @DELETE("v1/conversations/{conversationId}")
    suspend fun deleteConversation(@Path("conversationId") conversationId: String): DeleteConversationResponseDto

    @GET("v1/conversations/{conversationId}/peer-profile")
    suspend fun getConversationPeerProfile(@Path("conversationId") conversationId: String): PeerProfileResponseDto

    @GET("v1/messages/{conversationId}")
    suspend fun listMessages(
        @Path("conversationId") conversationId: String,
        @Query("beforeTs") beforeTs: Long? = null,
        @Query("beforeId") beforeId: String? = null,
        @Query("limit") limit: Int = 40
    ): MessagesPageResponseDto

    @POST("v1/messages")
    suspend fun sendMessage(@Body body: SendMessageRequestDto): SendMessageResponseDto

    @POST("v1/messages/receipts")
    suspend fun markReceipts(@Body body: ReceiptsRequestDto)

    @DELETE("v1/messages/{messageId}")
    suspend fun deleteMessage(@Path("messageId") messageId: String)

    @Multipart
    @POST("v1/media/upload")
    suspend fun uploadMedia(
        @Part file: MultipartBody.Part,
        @Part("category") category: RequestBody,
        @Part("conversationId") conversationId: RequestBody?,
        @Part("durationMs") durationMs: RequestBody? = null
    ): MediaUploadResponseDto

    @GET("v1/media/{mediaId}/signed-url")
    suspend fun getSignedMediaUrl(@Path("mediaId") mediaId: String): SignedMediaUrlResponseDto

    @GET("v1/calls/ice-servers")
    suspend fun getIceServers(): IceServersResponseDto

    @POST("v1/calls")
    suspend fun initiateCall(@Body body: CreateCallRequestDto): CreateCallResponseDto

    @POST("v1/calls/{callId}/state")
    suspend fun updateCallState(
        @Path("callId") callId: String,
        @Body body: UpdateCallStateRequestDto
    )

    @GET("v1/calls/history")
    suspend fun getCallHistory(
        @Query("filter") filter: String? = null,
        @Query("limit") limit: Int = 60
    ): CallHistoryResponseDto

    @POST("v1/notifications/endpoints")
    suspend fun registerNotificationEndpoint(@Body body: NotificationEndpointRequestDto)

    @DELETE("v1/notifications/endpoints")
    suspend fun unregisterNotificationEndpoint()

    @GET("v1/privacy/blocked")
    suspend fun getBlockedUsers(): BlockedUsersResponseDto

    @POST("v1/privacy/block")
    suspend fun blockUser(@Body body: BlockUserRequestDto)

    @DELETE("v1/privacy/block/{targetUserId}")
    suspend fun unblockUser(@Path("targetUserId") targetUserId: String)

    @POST("v1/reports")
    suspend fun submitReport(@Body body: ReportRequestDto)

    @GET("v1/auth/sessions")
    suspend fun listActiveSessions(): ActiveSessionsResponseDto

    @DELETE("v1/auth/sessions/{sessionId}")
    suspend fun revokeSession(@Path("sessionId") sessionId: String)

    @GET("v1/owner/activity")
    suspend fun getOwnerActivity(
        @Header("X-Owner-Key") ownerKey: String,
        @Query("q") query: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): OwnerActivityResponseDto

    @GET("v1/private-chat/pin/status")
    suspend fun getPrivatePinStatus(): PrivatePinStatusResponseDto

    @POST("v1/private-chat/pin/setup")
    suspend fun setupPrivatePin(@Body body: PrivatePinSetupRequestDto): PrivatePinUnlockResponseDto

    @POST("v1/private-chat/pin/unlock")
    suspend fun unlockPrivateChat(@Body body: PrivatePinUnlockRequestDto): PrivatePinUnlockResponseDto

    @POST("v1/private-chat/pin/change")
    suspend fun changePrivatePin(@Body body: PrivatePinChangeRequestDto): PrivatePinUnlockResponseDto

    @POST("v1/private-chat/pin/reset")
    suspend fun resetPrivatePin(@Body body: PrivatePinResetRequestDto): PrivatePinUnlockResponseDto

    @GET("v1/private-chat/conversations")
    suspend fun listPrivateConversations(): ConversationsListResponseDto

    @POST("v1/private-chat/conversations/direct")
    suspend fun createOrGetPrivateDirectConversation(@Body body: DirectConversationRequestDto): SingleConversationResponseDto

    @PATCH("v1/private-chat/conversations/{conversationId}/preferences")
    suspend fun updatePrivateConversationPreferences(
        @Path("conversationId") conversationId: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    )

    @POST("v1/private-chat/conversations/{conversationId}/clear")
    suspend fun clearPrivateConversation(@Path("conversationId") conversationId: String): ClearConversationResponseDto

    @DELETE("v1/private-chat/conversations/{conversationId}")
    suspend fun deletePrivateConversation(@Path("conversationId") conversationId: String): DeleteConversationResponseDto

    @GET("v1/private-chat/conversations/{conversationId}/peer-profile")
    suspend fun getPrivateConversationPeerProfile(@Path("conversationId") conversationId: String): PeerProfileResponseDto

    @GET("v1/private-chat/users/search")
    suspend fun searchUsersForPrivateChat(
        @Query("phone") phone: String,
        @Query("name") name: String? = null
    ): UserSearchResponseDto

    @GET("v1/private-chat/messages/{conversationId}")
    suspend fun listPrivateMessages(
        @Path("conversationId") conversationId: String,
        @Query("beforeTs") beforeTs: Long? = null,
        @Query("beforeId") beforeId: String? = null,
        @Query("limit") limit: Int = 40
    ): MessagesPageResponseDto

    @POST("v1/private-chat/messages")
    suspend fun sendPrivateMessage(@Body body: SendMessageRequestDto): SendMessageResponseDto

    @POST("v1/private-chat/messages/receipts")
    suspend fun markPrivateReceipts(@Body body: ReceiptsRequestDto)

    @DELETE("v1/private-chat/messages/{messageId}")
    suspend fun deletePrivateMessage(@Path("messageId") messageId: String)
}

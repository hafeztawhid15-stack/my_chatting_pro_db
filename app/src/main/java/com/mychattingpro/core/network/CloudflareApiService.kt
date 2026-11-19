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
}

package com.mychattingpro

import com.mychattingpro.core.common.AppError
import com.mychattingpro.data.repository.AppException
import com.mychattingpro.data.repository.ErrorMapper
import com.mychattingpro.domain.models.CallHistoryFilter
import com.mychattingpro.domain.models.CallHistoryItem
import com.mychattingpro.domain.models.IceServerConfig
import com.mychattingpro.domain.models.MediaAttachment
import com.mychattingpro.domain.models.Message
import com.mychattingpro.domain.models.MessageStatus
import com.mychattingpro.domain.models.MessageType
import com.mychattingpro.domain.repository.CallRepository
import com.mychattingpro.features.calls.CallHistoryViewModel
import com.mychattingpro.features.messages.ChatTimelineItem
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class MediaAndCallDeleteUnitTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `timeline builder orders remaining messages and calls accurately after deletions`() {
        val msg = Message(
            id = "m-1",
            conversationId = "c-1",
            senderId = "u-1",
            clientMessageId = "cm-1",
            type = MessageType.IMAGE,
            bodyText = "Photo",
            media = MediaAttachment("med-1", "chat_image", "pic.jpg", "image/jpeg", 1024L),
            status = MessageStatus.SENT,
            createdAtMs = 1500L
        )
        val remainingCall = CallHistoryItem(
            callId = "call-keep",
            conversationId = "c-1",
            callerId = "u-1",
            peerUserId = "p-1",
            peerDisplayName = "Peer",
            peerAvatarMediaId = null,
            isVideo = true,
            status = "ended",
            direction = "outgoing",
            startedAtMs = 2000L,
            answeredAtMs = 2001L,
            endedAtMs = 2030L,
            durationSeconds = 29
        )

        val timeline = ChatTimelineItem.buildTimeline(listOf(msg), listOf(remainingCall))
        assertEquals(listOf("msg_m-1", "call_call-keep"), timeline.map { it.timelineId })
    }

    @Test
    fun `error mapper translates delete 403 and call 404 server errors cleanly`() {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val mapper = ErrorMapper(moshi)

        val forbiddenDeleteEx = HttpException(
            Response.error<Any>(
                403,
                """{"error":"forbidden_delete","message":"You can only delete your own messages for everyone."}"""
                    .toResponseBody("application/json".toMediaType())
            )
        )
        val mappedDelete = mapper.map(forbiddenDeleteEx)
        assertTrue(mappedDelete is AppError.ServerError)
        assertEquals(
            "You can only delete your own messages for everyone.",
            (mappedDelete as AppError.ServerError).serverMessage
        )

        val notFoundCallEx = HttpException(
            Response.error<Any>(
                404,
                """{"error":"call_not_found","message":"Call record not found."}"""
                    .toResponseBody("application/json".toMediaType())
            )
        )
        val mappedCall = mapper.map(notFoundCallEx)
        assertTrue(mappedCall is AppError.ServerError)
        assertEquals("Call record not found.", (mappedCall as AppError.ServerError).serverMessage)
    }

    @Test
    fun `call history viewmodel deletes single batch and clears all records across incoming outgoing missed rejected voice and video calls`() = runTest(testDispatcher) {
        val repo = FakeStatefulCallRepo(
            listOf(
                sampleCallItem("c1", "incoming", "accepted", isVideo = false, 1000L),
                sampleCallItem("c2", "outgoing", "ended", isVideo = true, 2000L),
                sampleCallItem("c3", "incoming", "missed", isVideo = false, 3000L),
                sampleCallItem("c4", "incoming", "rejected", isVideo = true, 4000L)
            )
        )
        val vm = CallHistoryViewModel(callRepository = repo)
        val collected = mutableListOf<List<String>>()
        val collectJob = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            vm.uiState.collect { collected.add(it.calls.map { c -> c.callId }) }
        }
        advanceUntilIdle()
        assertEquals(listOf("c1", "c2", "c3", "c4"), vm.uiState.value.calls.map { it.callId })
        assertEquals(listOf("c1", "c2", "c3", "c4"), collected.last())

        // Single delete
        vm.deleteCall("c1")
        advanceUntilIdle()
        assertEquals(listOf("c2", "c3", "c4"), vm.uiState.value.calls.map { it.callId })

        // Multi-select + selectAllVisible + batch delete
        vm.toggleSelection("c2")
        vm.toggleSelection("c3")
        advanceUntilIdle()
        assertEquals(setOf("c2", "c3"), vm.uiState.value.selectedCallIds)
        vm.deleteSelectedCalls()
        advanceUntilIdle()
        assertEquals(listOf("c4"), vm.uiState.value.calls.map { it.callId })
        assertTrue(vm.uiState.value.selectedCallIds.isEmpty())

        // Clear all
        vm.clearAllHistory()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.calls.isEmpty())
        collectJob.cancel()
    }

    @Test
    fun `call history viewmodel propagates server permission error without dropping visible items`() = runTest(testDispatcher) {
        val repo = FakeStatefulCallRepo(
            listOf(sampleCallItem("c1", "incoming", "missed", isVideo = false, 1000L)),
            failOnDelete = true
        )
        val vm = CallHistoryViewModel(callRepository = repo)
        val collectJob = backgroundScope.launch {
            vm.uiState.collect { }
        }
        advanceUntilIdle()

        vm.deleteCall("c1")
        advanceUntilIdle()
        assertEquals(listOf("c1"), vm.uiState.value.calls.map { it.callId })
        assertTrue(vm.uiState.value.error is AppError.ServerError)
        collectJob.cancel()
    }

    private fun sampleCallItem(
        callId: String,
        direction: String,
        status: String,
        isVideo: Boolean,
        startedAtMs: Long
    ) = CallHistoryItem(
        callId = callId,
        conversationId = "c-1",
        callerId = if (direction == "outgoing") "u-1" else "p-1",
        peerUserId = "p-1",
        peerDisplayName = "Family Contact",
        peerAvatarMediaId = null,
        isVideo = isVideo,
        status = status,
        direction = direction,
        startedAtMs = startedAtMs,
        answeredAtMs = null,
        endedAtMs = startedAtMs + 20,
        durationSeconds = 20
    )

    private class FakeStatefulCallRepo(
        initial: List<CallHistoryItem>,
        private val failOnDelete: Boolean = false
    ) : CallRepository {
        private val state = MutableStateFlow(initial)
        override fun observeCallHistory(): Flow<List<CallHistoryItem>> = state
        override fun observeCallsForPeer(conversationId: String, peerUserId: String): Flow<List<CallHistoryItem>> =
            state.map { list -> list.filter { it.conversationId == conversationId || it.peerUserId == peerUserId } }
        override suspend fun refreshCallHistory(filter: CallHistoryFilter): Result<List<CallHistoryItem>> =
            Result.success(state.value)
        override suspend fun deleteCallRecord(callId: String): Result<Unit> {
            if (failOnDelete) return Result.failure(AppException(AppError.ServerError("Forbidden")))
            state.value = state.value.filterNot { it.callId == callId }
            return Result.success(Unit)
        }
        override suspend fun deleteCallRecords(callIds: List<String>): Result<Unit> {
            if (failOnDelete) return Result.failure(AppException(AppError.ServerError("Forbidden")))
            val idSet = callIds.toSet()
            state.value = state.value.filterNot { it.callId in idSet }
            return Result.success(Unit)
        }
        override suspend fun clearAllCallHistory(): Result<Unit> {
            if (failOnDelete) return Result.failure(AppException(AppError.ServerError("Forbidden")))
            state.value = emptyList()
            return Result.success(Unit)
        }
        override suspend fun fetchIceServers(): Result<Pair<List<IceServerConfig>, Boolean>> =
            Result.success(emptyList<IceServerConfig>() to false)
        override suspend fun initiateCall(conversationId: String, calleeId: String, isVideo: Boolean): Result<String> =
            Result.success("call-new")
        override suspend fun updateCallState(callId: String, action: String): Result<Unit> =
            Result.success(Unit)
    }
}

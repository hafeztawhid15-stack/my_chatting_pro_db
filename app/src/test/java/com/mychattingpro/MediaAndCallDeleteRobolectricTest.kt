package com.mychattingpro

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mychattingpro.core.database.CallHistoryEntity
import com.mychattingpro.core.database.HiddenCallEntity
import com.mychattingpro.core.database.HiddenMessageEntity
import com.mychattingpro.core.database.MessageEntity
import com.mychattingpro.core.database.MyChattingProDatabase
import com.mychattingpro.core.datastore.AppPreferencesDataStore
import com.mychattingpro.core.localization.AppLanguage
import com.mychattingpro.core.network.NetworkModule
import com.mychattingpro.core.security.EncryptedTokenStore
import com.mychattingpro.core.security.StoredSession
import com.mychattingpro.core.theme.MyChattingProTheme
import com.mychattingpro.domain.models.CallHistoryFilter
import com.mychattingpro.domain.models.CallHistoryItem
import com.mychattingpro.domain.models.IceServerConfig
import com.mychattingpro.domain.models.MediaAttachment
import com.mychattingpro.domain.models.Message
import com.mychattingpro.domain.models.MessageStatus
import com.mychattingpro.domain.models.MessageType
import com.mychattingpro.domain.repository.CallRepository
import com.mychattingpro.features.calls.CallHistoryScreen
import com.mychattingpro.features.calls.CallHistoryViewModel
import com.mychattingpro.features.messages.MessageDetailsSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaAndCallDeleteRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var db: MyChattingProDatabase

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MyChattingProDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `hidden_messages tombstone keeps delete-for-me messages excluded across re-upsert`() = runTest {
        val msgDao = db.messageDao()
        val imgMsg = sampleMediaMessage(id = "m-img", convId = "c-1", type = "image", mediaId = "med-img", createdAt = 1000L)
        val vidMsg = sampleMediaMessage(id = "m-vid", convId = "c-1", type = "video", mediaId = "med-vid", createdAt = 2000L)
        val privImg = sampleMediaMessage(id = "m-priv", convId = "c-priv", type = "image", mediaId = "med-priv", createdAt = 3000L, isPrivate = true)

        msgDao.upsertAll(listOf(imgMsg, vidMsg, privImg))

        msgDao.hideMessage(HiddenMessageEntity("m-vid", 5000L))
        msgDao.deleteById("m-vid")

        val visibleRegular = msgDao.observeMessages("c-1").first()
        assertEquals(listOf("m-img"), visibleRegular.map { it.id })
        assertEquals("m-img", msgDao.getLatestVisibleMessage("c-1", isPrivate = false)?.id)

        msgDao.upsertAll(listOf(vidMsg))
        assertEquals(listOf("m-img"), msgDao.observeMessages("c-1").first().map { it.id })

        msgDao.hideMessage(HiddenMessageEntity("m-priv", 5100L))
        msgDao.deleteById("m-priv")
        assertTrue(msgDao.observePrivateMessages("c-priv").first().isEmpty())

        msgDao.clearHiddenMessages()
        msgDao.upsertAll(listOf(vidMsg))
        assertEquals(listOf("m-img", "m-vid"), msgDao.observeMessages("c-1").first().map { it.id })
    }

    @Test
    fun `markDeletedForAll clears mediaId and bodyText while preserving placeholder timestamp`() = runTest {
        val msgDao = db.messageDao()
        val imgMsg = sampleMediaMessage(id = "m-del", convId = "c-1", type = "image", mediaId = "med-123", createdAt = 1500L)
        msgDao.upsert(imgMsg)

        msgDao.markDeletedForAll("m-del", deletedAtMs = 9000L)
        val updated = msgDao.getById("m-del")!!
        assertEquals(9000L, updated.deletedForAllAtMs)
        assertNull(updated.mediaId)
        assertNull(updated.bodyText)
    }

    @Test
    fun `hidden_calls tombstone persists single batch and clear-all call history deletion`() = runTest {
        val callDao = db.callHistoryDao()
        val calls = listOf(
            sampleCallEntity("call-1", "c-1", "p-1", "audio", "incoming", "accepted", 1000L),
            sampleCallEntity("call-2", "c-1", "p-1", "video", "outgoing", "ended", 2000L),
            sampleCallEntity("call-3", "c-2", "p-2", "audio", "incoming", "missed", 3000L),
            sampleCallEntity("call-4", "c-2", "p-2", "video", "incoming", "rejected", 4000L)
        )
        callDao.replaceAll(calls)
        assertEquals(4, callDao.observeAll().first().size)

        callDao.hideCall(HiddenCallEntity("call-1", 5000L))
        callDao.deleteById("call-1")
        assertEquals(listOf("call-4", "call-3", "call-2"), callDao.observeAll().first().map { it.callId })
        assertEquals(listOf("call-2"), callDao.observeCallsForPeer("c-1", "p-1").first().map { it.callId })

        callDao.replaceAll(calls)
        assertEquals(listOf("call-4", "call-3", "call-2"), callDao.observeAll().first().map { it.callId })

        callDao.hideCalls(listOf(HiddenCallEntity("call-3", 5100L), HiddenCallEntity("call-4", 5100L)))
        callDao.deleteByIds(listOf("call-3", "call-4"))
        assertEquals(listOf("call-2"), callDao.observeAll().first().map { it.callId })

        callDao.clearAll()
        assertTrue(callDao.observeAll().first().isEmpty())
    }

    @Test
    fun `call history screen shows delete options and confirms before deleting`() = runTest {
        val prefs = AppPreferencesDataStore(context)
        val tokenStore = InMemoryTokenStore()
        val networkModule = NetworkModule(context, tokenStore, prefs)
        val repo = FakeStatefulCallRepo(
            listOf(
                sampleCallItem("call-a", "incoming", "missed", false, 1000L),
                sampleCallItem("call-b", "outgoing", "ended", true, 2000L)
            )
        )
        val vm = CallHistoryViewModel(callRepository = repo)

        composeRule.setContent {
            MyChattingProTheme {
                CallHistoryScreen(
                    viewModel = vm,
                    networkModule = networkModule,
                    onBack = {},
                    onStartCallback = { _, _ -> }
                )
            }
        }
        testDispatcher.scheduler.advanceUntilIdle()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("call_history_row_call-a").assertIsDisplayed()
        composeRule.onNodeWithTag("call_history_clear_all_button").assertIsDisplayed()

        composeRule.onNodeWithTag("delete_call_call-a").performClick()
        composeRule.onNodeWithTag("confirm_delete_call_single_button").assertIsDisplayed().performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("call_history_row_call-a").assertCountEquals(0)
        composeRule.onNodeWithTag("call_history_row_call-b").assertIsDisplayed()

        composeRule.onNodeWithTag("call_history_row_call-b").performTouchInput { longClick() }
        composeRule.onNodeWithTag("call_history_delete_selected_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("confirm_delete_call_batch_button").assertIsDisplayed().performClick()
        testDispatcher.scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("call_history_row_call-b").assertCountEquals(0)
    }

    @Test
    fun `message details sheet shows delete for me and everyone with confirmation`() {
        val myVideoMessage = Message(
            id = "msg-vid-1",
            conversationId = "c-1",
            senderId = "u-1",
            clientMessageId = "cm-vid-1",
            type = MessageType.VIDEO,
            bodyText = null,
            media = MediaAttachment("med-v", "chat_video", "clip.mp4", "video/mp4", 4096L, 5000L),
            status = MessageStatus.SENT,
            createdAtMs = 10_000L
        )
        var deletedForMe = false
        var deletedForEveryone = false

        composeRule.setContent {
            MyChattingProTheme {
                MessageDetailsSheet(
                    message = myVideoMessage,
                    isMine = true,
                    language = AppLanguage.ENGLISH,
                    onReply = {},
                    onCopy = {},
                    onShare = {},
                    onSaveMedia = null,
                    onRetry = {},
                    onDeleteForMe = { deletedForMe = true },
                    onDeleteForEveryone = { deletedForEveryone = true },
                    onReportClick = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("sheet_delete_for_me").assertIsDisplayed()
        composeRule.onNodeWithTag("sheet_delete_for_everyone").assertIsDisplayed()

        composeRule.onNodeWithTag("sheet_delete_for_everyone").performClick()
        assertFalse(deletedForEveryone)
        composeRule.onNodeWithTag("confirm_delete_message_button").assertIsDisplayed().performClick()
        assertTrue(deletedForEveryone)
        assertFalse(deletedForMe)
    }

    @Test
    fun `message details sheet hides delete for everyone on peer media message`() {
        val peerImageMessage = Message(
            id = "msg-img-peer",
            conversationId = "c-1",
            senderId = "peer-1",
            clientMessageId = "cm-img-peer",
            type = MessageType.IMAGE,
            bodyText = null,
            media = MediaAttachment("med-i", "chat_image", "photo.jpg", "image/jpeg", 2048L),
            status = MessageStatus.DELIVERED,
            createdAtMs = 10_000L
        )
        var deletedForMe = false

        composeRule.setContent {
            MyChattingProTheme {
                MessageDetailsSheet(
                    message = peerImageMessage,
                    isMine = false,
                    language = AppLanguage.ENGLISH,
                    onReply = {},
                    onCopy = {},
                    onShare = {},
                    onSaveMedia = null,
                    onRetry = {},
                    onDeleteForMe = { deletedForMe = true },
                    onDeleteForEveryone = {},
                    onReportClick = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("sheet_delete_for_me").assertIsDisplayed().performClick()
        assertFalse(deletedForMe)
        composeRule.onNodeWithTag("confirm_delete_message_button").performClick()
        assertTrue(deletedForMe)
        composeRule.onAllNodesWithTag("sheet_delete_for_everyone").assertCountEquals(0)
    }

    private fun sampleMediaMessage(
        id: String,
        convId: String,
        type: String,
        mediaId: String,
        createdAt: Long,
        isPrivate: Boolean = false
    ) = MessageEntity(
        id = id,
        conversationId = convId,
        senderId = "u-1",
        clientMessageId = "client-$id",
        messageType = type,
        bodyText = null,
        mediaId = mediaId,
        mediaCategory = if (type == "video") "chat_video" else "chat_image",
        mediaFilename = if (type == "video") "clip.mp4" else "photo.jpg",
        mediaMimeType = if (type == "video") "video/mp4" else "image/jpeg",
        mediaSizeBytes = 2048L,
        mediaDurationMs = if (type == "video") 4000L else null,
        localStatus = "SENT",
        localUri = null,
        uploadProgress = null,
        deletedForAllAtMs = null,
        createdAtMs = createdAt,
        deliveredAtMs = null,
        readAtMs = null,
        isPrivate = isPrivate
    )

    private fun sampleCallEntity(
        callId: String,
        convId: String,
        peerId: String,
        callType: String,
        direction: String,
        status: String,
        startedAtMs: Long
    ) = CallHistoryEntity(
        callId = callId,
        conversationId = convId,
        callerId = if (direction == "outgoing") "u-1" else peerId,
        peerUserId = peerId,
        peerDisplayName = "Family Member",
        peerAvatarMediaId = null,
        callType = callType,
        status = status,
        direction = direction,
        startedAtMs = startedAtMs,
        answeredAtMs = if (status == "accepted" || status == "ended") startedAtMs + 10 else null,
        endedAtMs = startedAtMs + 50,
        durationSeconds = if (status == "accepted" || status == "ended") 40 else 0
    )

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

    private class FakeStatefulCallRepo(initial: List<CallHistoryItem>) : CallRepository {
        private val state = MutableStateFlow(initial)
        override fun observeCallHistory(): Flow<List<CallHistoryItem>> = state
        override fun observeCallsForPeer(conversationId: String, peerUserId: String): Flow<List<CallHistoryItem>> =
            state.map { list -> list.filter { it.conversationId == conversationId || it.peerUserId == peerUserId } }
        override suspend fun refreshCallHistory(filter: CallHistoryFilter): Result<List<CallHistoryItem>> =
            Result.success(state.value)
        override suspend fun deleteCallRecord(callId: String): Result<Unit> {
            state.value = state.value.filterNot { it.callId == callId }
            return Result.success(Unit)
        }
        override suspend fun deleteCallRecords(callIds: List<String>): Result<Unit> {
            val idSet = callIds.toSet()
            state.value = state.value.filterNot { it.callId in idSet }
            return Result.success(Unit)
        }
        override suspend fun clearAllCallHistory(): Result<Unit> {
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

    private class InMemoryTokenStore : EncryptedTokenStore(ApplicationProvider.getApplicationContext()) {
        private val flow = MutableStateFlow<StoredSession?>(
            StoredSession("u-1", "+8801712345678", "Tawhid", "Hello", null, "acc", "ref")
        )
        override val sessionFlow: Flow<StoredSession?> = flow
        override suspend fun saveSession(session: StoredSession) { flow.value = session }
        override suspend fun getSession(): StoredSession? = flow.value
        override suspend fun updateUserProfile(displayName: String, about: String, avatarMediaId: String?) = Unit
        override suspend fun clearSession() { flow.value = null }
    }
}

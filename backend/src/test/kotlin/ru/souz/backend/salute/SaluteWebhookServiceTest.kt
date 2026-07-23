package ru.souz.backend.salute

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.service.SendMessageResult
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.salute.sandbox.SaluteToolAttributes
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.backend.testutil.repository.MemorySaluteDeviceBindingRepository
import ru.souz.runtime.sandbox.SandboxCommandResult

@OptIn(ExperimentalCoroutinesApi::class)
class SaluteWebhookServiceTest {
    @Test
    fun `new session returns greeting without touching the agent`() = runTest {
        val executor = RecordingSaluteTurnExecutor()
        val service = service(this, turnExecutor = executor)

        val response = service.handleWebhook(request(deviceId = "device-1", newSession = true))

        assertEquals("Навык связи с Souz запущен. Задайте вопрос.", response.payload.pronounceText)
        assertFalse(response.payload.finished)
        assertTrue(executor.calls.isEmpty())
    }

    @Test
    fun `utterance for an unconnected device is rejected without touching the agent`() = runTest {
        val executor = RecordingSaluteTurnExecutor()
        val service = service(this, turnExecutor = executor)

        val response = service.handleWebhook(request(deviceId = "device-1", text = "hello"))

        assertEquals("Устройство не подключено. Попробуйте позже.", response.payload.pronounceText)
        assertTrue(response.payload.finished)
        assertTrue(executor.calls.isEmpty())
    }

    @Test
    fun `pending end session is consumed once`() = runTest {
        val service = service(this)
        service.markEndSession("device-1")

        val first = service.handleWebhook(request(deviceId = "device-1", text = "hello"))
        assertEquals("Сеанс завершён.", first.payload.pronounceText)
        assertTrue(first.payload.finished)

        // Second call for the same device must fall through to the "not connected" branch,
        // not repeat the end-session reply — the flag was a one-shot.
        val second = service.handleWebhook(request(deviceId = "device-1", text = "hello"))
        assertEquals("Устройство не подключено. Попробуйте позже.", second.payload.pronounceText)
    }

    @Test
    fun `unbound device is told it's not bound, agent never runs`() = runTest {
        val pusher = RecordingDevicePusher(connectedDevices = setOf("device-1"))
        val executor = RecordingSaluteTurnExecutor()
        val service = service(this, connectionRegistry = pusher, turnExecutor = executor)

        service.handleWebhook(request(deviceId = "device-1", text = "hello"))
        advanceUntilIdle()

        assertTrue(executor.calls.isEmpty(), "agent must not run for an unbound device")
        val speak = pusher.execCalls.single().message
        assertTrue(speak.argv.orEmpty().last().contains("не привязано"))
    }

    @Test
    fun `connected device gets a silent response and the turn runs in the background`() = runTest {
        val pusher = RecordingDevicePusher(connectedDevices = setOf("device-1"))
        val executor = RecordingSaluteTurnExecutor(assistantResponse = "Ответ агента.")
        val bindingRepository = MemorySaluteDeviceBindingRepository()
        bindingRepository.insertIfAbsent(
            deviceId = "device-1",
            userId = DEFAULT_USER_ID,
            chatId = UUID.randomUUID(),
            now = Instant.parse("2026-07-20T09:00:00Z"),
        )
        val service = service(
            this,
            bindingRepository = bindingRepository,
            connectionRegistry = pusher,
            turnExecutor = executor,
        )

        val response = service.handleWebhook(request(deviceId = "device-1", sessionId = "s-1", messageId = 42L, text = "  what's up  "))
        assertEquals("", response.payload.pronounceText)
        assertFalse(response.payload.finished)
        assertTrue(executor.calls.isEmpty(), "turn must not run synchronously inside the webhook call")

        advanceUntilIdle()

        assertEquals(1, executor.calls.size)
        val call = executor.calls.single()
        assertEquals(DEFAULT_USER_ID, call.userId)
        assertTrue(call.content.startsWith("what's up"), "content should start with the transcribed text")
        assertTrue(call.content.contains("Голосовой канал"), "content should carry the voice-style instruction")
        assertEquals(UserSettingsOverrides(streamingMessages = false), call.requestOverrides)
        assertEquals("device-1", call.attributes[SaluteToolAttributes.DEVICE_ID])

        val binding = bindingRepository.getByDeviceId("device-1")
        assertNotNull(binding)
        assertEquals(call.chatId, binding.chatId)

        // LED on, thinking-phrase speak, LED off, final answer speak — all delivered as
        // generic `exec` pushes, never a bespoke "message" type.
        assertEquals(4, pusher.execCalls.size)
        assertTrue(pusher.execCalls.all { it.message.type == SaluteDeviceMessageType.EXEC })
        val finalSpeak = pusher.execCalls.last().message
        assertTrue(finalSpeak.argv.orEmpty().last().contains("Ответ агента."))
    }

    @Test
    fun `same device reuses the same binding and chat across turns`() = runTest {
        val pusher = RecordingDevicePusher(connectedDevices = setOf("device-1"))
        val executor = RecordingSaluteTurnExecutor()
        val bindingRepository = MemorySaluteDeviceBindingRepository()
        bindingRepository.insertIfAbsent(
            deviceId = "device-1",
            userId = DEFAULT_USER_ID,
            chatId = UUID.randomUUID(),
            now = Instant.parse("2026-07-20T09:00:00Z"),
        )
        val service = service(
            this,
            bindingRepository = bindingRepository,
            connectionRegistry = pusher,
            turnExecutor = executor,
        )

        service.handleWebhook(request(deviceId = "device-1", text = "first"))
        advanceUntilIdle()
        service.handleWebhook(request(deviceId = "device-1", text = "second"))
        advanceUntilIdle()

        assertEquals(2, executor.calls.size)
        assertEquals(executor.calls[0].chatId, executor.calls[1].chatId)
    }

    @Test
    fun `handleExecResult resolves a pending exec request registered for the same id`() = runTest {
        val execRequestRegistry = SaluteExecRequestRegistry()
        val service = service(this, execRequestRegistry = execRequestRegistry)
        val deferred = execRequestRegistry.beginRequest(deviceId = "device-1", id = "req-1")

        service.handleExecResult(
            deviceId = "device-1",
            message = SaluteDeviceMessage(
                type = SaluteDeviceMessageType.EXEC_RESULT,
                id = "req-1",
                exitCode = 0,
                stdout = "ok",
                stderr = "",
                timedOut = false,
            ),
        )

        assertEquals(SandboxCommandResult(exitCode = 0, stdout = "ok", stderr = "", timedOut = false), deferred.await())
    }

    @Test
    fun `handleExecResult for an unknown id is a no-op`() = runTest {
        val service = service(this)

        service.handleExecResult(
            deviceId = "device-1",
            message = SaluteDeviceMessage(type = SaluteDeviceMessageType.EXEC_RESULT, id = "unknown", exitCode = 0),
        )
    }

    private fun service(
        scope: CoroutineScope,
        bindingRepository: SaluteDeviceBindingRepository = MemorySaluteDeviceBindingRepository(),
        connectionRegistry: SaluteDevicePusher = RecordingDevicePusher(),
        turnExecutor: SaluteTurnExecutor = RecordingSaluteTurnExecutor(),
        execRequestRegistry: SaluteExecRequestRegistry = SaluteExecRequestRegistry(),
    ): SaluteWebhookService = SaluteWebhookService(
        bindingRepository = bindingRepository,
        connectionRegistry = connectionRegistry,
        turnExecutor = turnExecutor,
        applicationScope = scope,
        execRequestRegistry = execRequestRegistry,
        clock = Clock.fixed(Instant.parse("2026-07-20T10:00:00Z"), ZoneOffset.UTC),
    )

    private fun request(
        deviceId: String?,
        sessionId: String = "session-1",
        messageId: Long = 1L,
        newSession: Boolean = false,
        text: String? = null,
    ): SaluteWebhookRequest = SaluteWebhookRequest(
        sessionId = sessionId,
        messageId = messageId,
        uuid = null,
        payload = SaluteWebhookPayload(
            device = deviceId?.let { SaluteDevice(deviceId = it) },
            message = text?.let { SaluteMessage(originalText = it) },
            newSession = newSession,
        ),
    )

    private companion object {
        const val DEFAULT_USER_ID = "owner"
    }
}

private class RecordingSaluteTurnExecutor(
    private val assistantResponse: String? = "Готово",
) : SaluteTurnExecutor {
    val calls = mutableListOf<SaluteTurnCall>()

    override suspend fun execute(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String,
        requestOverrides: UserSettingsOverrides,
        attributes: Map<String, String>,
    ): SendMessageResult {
        calls += SaluteTurnCall(userId, chatId, content, clientMessageId, requestOverrides, attributes)
        val userMessage = ChatMessage(
            id = UUID.randomUUID(),
            userId = userId,
            chatId = chatId,
            seq = 1L,
            role = ChatRole.USER,
            content = content,
            metadata = emptyMap(),
            createdAt = Instant.parse("2026-07-20T10:00:00Z"),
        )
        val assistantMessage = assistantResponse?.let { response ->
            ChatMessage(
                id = UUID.randomUUID(),
                userId = userId,
                chatId = chatId,
                seq = 2L,
                role = ChatRole.ASSISTANT,
                content = response,
                metadata = emptyMap(),
                createdAt = Instant.parse("2026-07-20T10:00:01Z"),
            )
        }
        return SendMessageResult(
            userMessage = userMessage,
            assistantMessage = assistantMessage,
            execution = AgentExecution(
                id = UUID.randomUUID(),
                userId = userId,
                chatId = chatId,
                userMessageId = userMessage.id,
                assistantMessageId = assistantMessage?.id,
                status = AgentExecutionStatus.COMPLETED,
                requestId = null,
                clientMessageId = clientMessageId,
                model = null,
                provider = null,
                startedAt = Instant.parse("2026-07-20T10:00:00Z"),
                finishedAt = Instant.parse("2026-07-20T10:00:01Z"),
                cancelRequested = false,
                errorCode = null,
                errorMessage = null,
                usage = null,
                metadata = emptyMap(),
            ),
        )
    }
}

private data class SaluteTurnCall(
    val userId: String,
    val chatId: UUID,
    val content: String,
    val clientMessageId: String,
    val requestOverrides: UserSettingsOverrides,
    val attributes: Map<String, String> = emptyMap(),
)

private class RecordingDevicePusher(
    private val connectedDevices: Set<String> = emptySet(),
) : SaluteDevicePusher {
    val execCalls = mutableListOf<ExecCall>()
    private val connected = ConcurrentHashMap.newKeySet<String>().apply { addAll(connectedDevices) }

    override fun isConnected(deviceId: String): Boolean = connected.contains(deviceId)

    override suspend fun sendExec(deviceId: String, message: SaluteDeviceMessage): Boolean {
        execCalls += ExecCall(deviceId, message)
        return connected.contains(deviceId)
    }
}

private data class ExecCall(
    val deviceId: String,
    val message: SaluteDeviceMessage,
)

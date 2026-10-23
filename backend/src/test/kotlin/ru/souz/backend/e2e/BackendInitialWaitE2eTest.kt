package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.websocket.Frame
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import ru.souz.agent.spi.TurnClassification
import ru.souz.agent.spi.TurnClassifier
import ru.souz.backend.http.BackendHttpRoutes

class BackendInitialWaitE2eTest {
    @Test
    fun `initial wait follows the forecast independently of narration and is never replayed`() {
        val decisions = Channel<TurnClassification?>(Channel.UNLIMITED)
        val releaseAnswer = Channel<Unit>(Channel.UNLIMITED)
        backendE2eTest(
            "initial_wait",
            turnClassifier = TurnClassifier { decisions.receive() ?: error("classification unavailable") },
            llm = E2eLlmApi { request -> releaseAnswer.receive(); reply(request, "Done") },
        ) {
            val userId = UUID.randomUUID().toString()
            for (narrate in listOf(false, true)) {
                client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(userId); jsonBody("""{"narrateSteps":$narrate}""")
                }
                for ((index, decision) in listOf(
                    TurnClassification(false, false), TurnClassification(false, true),
                    TurnClassification(true, false), TurnClassification(true, true), null,
                ).withIndex()) {
                    val id = "$narrate-$index"
                    val chatId = createPublicChat(userId, requestId = id)
                    val frame = messageFrame(chatId, userId, id, text = id)
                    decisions.send(decision)
                    withPublicSocket(chatId) { socket ->
                        try {
                            socket.send(Frame.Text(frame))
                            val ack = readJson(socket)
                            assertEquals("accepted", ack["status"].asText())
                            assertEquals("thread.status", readJson(socket)["type"].asText())
                            llm.awaitPrompt(id)
                            if (decision?.expectedFast != true) {
                                val waiting = readJson(socket)
                                assertEquals("assistant.message", waiting["type"].asText())
                                assertEquals(ack["thread"]["id"], waiting["threadId"])
                                assertTrue(waiting["seq"].isNull)
                                assertTrue(waiting["payload"]["content"].asText() in WAITING_PHRASES)
                            }
                            // An idempotent submission cannot trigger another initial response.
                            socket.send(Frame.Text(frame))
                            assertTrue(readJson(socket)["duplicate"].asBoolean())
                            assertEquals("thread.status", readJson(socket)["type"].asText())
                            releaseAnswer.send(Unit)
                            val terminal = readJson(socket)
                            assertEquals("thread.completed", terminal["type"].asText())
                            assertEquals("Done", terminal["payload"]["response"].asText())
                            withPublicSocket(chatId) { replay ->
                                assertEquals(terminal, readJson(replay))
                                replay.send(Frame.Text(historyFrame(chatId, "barrier", "user", "context")))
                                assertEquals("ack", readJson(replay)["kind"].asText())
                            }
                        } finally {
                            releaseAnswer.trySend(Unit)
                        }
                    }
                    val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
                    assertEquals(listOf(id, "Done", "context"), messages.map { it["content"].asText() })
                    assertFalse(llm.requests.last().messages.any { it.content in WAITING_PHRASES })
                    // Remove the finally safeguard so the next turn also stays blocked for inspection.
                    releaseAnswer.tryReceive()
                }
            }
        }
    }

    private companion object {
        val WAITING_PHRASES = setOf("Минуту...", "Одну минуту...", "Секунду...", "Одну секунду...",
            "Секундочку...", "Один момент...", "Сейчас вернусь...")
    }
}

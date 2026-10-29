package ru.souz.backend.e2e

import io.ktor.websocket.Frame
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.llms.LLMMessageRole

class BackendClientDiscoveryE2eTest {
    @Test
    fun `connection and ordinary turns do not discover and restart expires the previous catalogue`() {
        val schema = newPostgresSchema("client_lazy_discovery")
        val owner = UUID.randomUUID().toString()
        var chat = ""
        var afterSeq = 0L
        for (restart in listOf(false, true)) {
            var discovered = false
            val llm = E2eLlmApi { request ->
                val turn = request.messages.drop(request.messages.indexOfLast { it.role == LLMMessageRole.user })
                if (turn.none { it.role == LLMMessageRole.function }) {
                    assertEquals(discovered && !restart, "speaker.old" in request.messages.first().content)
                }
                if (request.conversationPrompt() == "discover" && turn.last().role != LLMMessageRole.function) {
                    toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "orion.tools.list", "arguments" to emptyMap<String, Any>()))
                } else reply(request, "Done")
            }
            backendE2eTest("client_lazy_discovery", schema = schema, llm = llm) {
                if (!restart) chat = createPublicChat(owner)
                withMultiChatSocket { socket ->
                    for (prompt in listOf("hello", "discover", "hello again")) {
                        // Re-subscription must not request a catalogue, either before or after explicit discovery.
                        socket.send(Frame.Text("""{"kind":"chat.subscribe","chatId":"$chat","requestId":"subscribe-$prompt","afterSeq":$afterSeq}"""))
                        assertEquals("accepted", readRawJson(socket)["status"].asText())
                        socket.send(Frame.Text(messageFrame(chat, owner, "$restart-$prompt", text = prompt)))
                        assertEquals("accepted", readJson(socket)["status"].asText())
                        assertEquals("thread.status", readJson(socket)["type"].asText())
                        if (prompt == "discover") {
                            val discovery = readJson(socket)
                            assertEquals("orion.tools.list", discovery["payload"]["name"].asText())
                            assertFalse(discovery["seq"].isNull)
                            discovered = true
                            socket.send(Frame.Text(toolResultFrame(discovery, "succeeded", mapOf("tools" to
                                json.readTree(if (restart) "[]" else clientToolsJson("speaker.old"))))))
                            assertEquals("accepted", readJson(socket)["status"].asText())
                        }
                        val completed = readJson(socket)
                        assertEquals("thread.completed", completed["type"].asText())
                        afterSeq = completed["seq"].asLong()
                    }
                }
            }
        }
    }
}

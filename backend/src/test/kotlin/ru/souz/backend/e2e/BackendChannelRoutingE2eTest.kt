package ru.souz.backend.e2e

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.telegram.TelegramChat
import ru.souz.backend.telegram.TelegramMessage
import ru.souz.backend.telegram.TelegramUpdate
import ru.souz.backend.telegram.TelegramUser
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.restJsonMapper

class BackendChannelRoutingE2eTest {
    @Test
    fun `cached routes refresh explicitly after migration and load lazily after restart`() {
        val schema = newPostgresSchema("e2e_channel_routing")
        val owner = UUID.randomUUID().toString()
        val telegram = FakeTelegramBotApi()
        var source = ""
        var target = ""
        var archived = ""
        var foreign = ""
        for (restarted in listOf(false, true)) {
            val llm = E2eLlmApi { request ->
                val prompt = request.conversationPrompt()
                assertFalse("Current communication channels for this execution:" in request.messages.first().content)
                val currentTurn = request.messages.drop(request.messages.indexOfLast { it.role == LLMMessageRole.user })
                if (prompt == "remember channels" || prompt == "refresh channels") {
                    if (currentTurn.last().role == LLMMessageRole.function) {
                        val channels = restJsonMapper.readTree(currentTurn.last().content)["channels"]
                        assertEquals(setOf(source, target), channels.map { it["channelId"].asText() }.toSet())
                        assertFalse(channels.any { it["channelId"].asText() in setOf(archived, foreign) })
                        assertEquals(if (prompt == "remember channels") "telegram" else "public_client",
                            channels.single { it["channelId"].asText() == target }["channelType"].asText())
                        reply(request, "Remembered")
                    } else toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "ListActiveChannels", "arguments" to emptyMap<String, Any>()))
                } else if (currentTurn.last().role == LLMMessageRole.function) {
                    assertEquals(prompt != "stale route", restJsonMapper.readTree(currentTurn.last().content)["success"].asBoolean())
                    reply(request, "Done")
                } else {
                    // The model keeps the old type; server-side routing must resolve the same chat without another LLM hop.
                    toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "SendMessageToChannel", "arguments" to mapOf(
                        "channelType" to "telegram", "channelId" to target, "text" to prompt,
                    )))
                }
            }
            backendE2eTest("e2e_channel_routing", schema = schema, llm = llm, telegramApi = telegram,
                featureFlags = BackendFeatureFlags(wsEvents = true, telegramBot = true)) {
                if (!restarted) {
                    source = createPublicChat(owner, "source")
                    target = createPublicChat(owner, "telegram")
                    archived = createPublicChat(owner, "archived")
                    foreign = createPublicChat(UUID.randomUUID().toString(), "foreign")
                    assertEquals(HttpStatusCode.OK, client.post(BackendHttpRoutes.archiveChat(archived)) { trusted(owner) }.status)
                    val link = client.put(BackendHttpRoutes.chatTelegramBot(target)) {
                        trusted(owner); jsonBody("""{"token":"123456:routing-test"}""")
                    }.jsonBody()["pendingLinkCommand"].asText()
                    telegram.enqueue(TelegramUpdate(1, TelegramMessage(1, TelegramUser(701, firstName = "Owner"), TelegramChat(701, "private"), link)))
                    backend.pollTelegramOnce()
                    assertEquals(HttpStatusCode.OK, sendTurn(source, owner, "remember channels").status)
                    // Removing the embedded provider leaves this same chat reachable over public WS.
                    assertEquals(HttpStatusCode.OK, client.delete(BackendHttpRoutes.chatTelegramBot(target)) { trusted(owner) }.status)
                }
                if (!restarted) {
                    sendTurn(source, owner, "stale route")
                    sendTurn(source, owner, "refresh channels")
                }
                repeat(2) { index ->
                    val prompt = "deliver restart=$restarted index=$index"
                    sendTurn(source, owner, prompt)
                    val received = client.get(BackendHttpRoutes.chatMessages(target)) { trusted(owner) }.jsonBody()["items"]
                    assertEquals(1, received.count { it["content"].asText() == prompt })
                }
            }
        }
    }

    @Test
    fun `child with a compiled send tool uses server routing without a destination snapshot`() {
        var target = ""
        val llm = E2eLlmApi { request ->
            val system = request.messages.first().content
            assertFalse(SNAPSHOT_HEADER in system)
            when {
                request.functions.map { it.name } == listOf("SendMessageToChannel") -> {
                    if (request.messages.last().role == LLMMessageRole.function) reply(request, "Child delivered")
                    else toolCallReply(request, "SendMessageToChannel", mapOf(
                        "channelType" to "telegram", "channelId" to target, "text" to "From child",
                    ))
                }
                request.messages.last().name == "SpawnSubagent" -> reply(request, "Done")
                else -> toolCallReply(request, "SpawnSubagent", mapOf(
                    "task" to "Send From child to the target channel.", "skillIds" to listOf("SendMessageToChannel"),
                ))
            }
        }
        backendE2eTest("e2e_channel_routing_child", llm = llm) {
            val owner = UUID.randomUUID().toString()
            val source = createPublicChat(owner, "source")
            target = createPublicChat(owner, "target")
            sendTurn(source, owner, "Delegate sending")
            val messages = client.get(BackendHttpRoutes.chatMessages(target)) { trusted(owner) }.jsonBody()["items"]
            assertEquals("From child", messages.single()["content"].asText())
        }
    }

    @Test
    fun `lazy routing includes destinations beyond the chat list page`() = backendE2eTest("e2e_channel_routing_pages") {
        val owner = UUID.randomUUID().toString()
        val target = createPublicChat(owner, "target")
        repeat(51) { createPublicChat(owner, "later-$it") }
        val source = createPublicChat(owner, "source")
        llm.requestSkill("SendMessageToChannel", mapOf("channelType" to "telegram", "channelId" to target, "text" to "Older destination"))
        sendTurn(source, owner, "Send to the older chat")
        val received = client.get(BackendHttpRoutes.chatMessages(target)) { trusted(owner) }.jsonBody()["items"]
        assertEquals("Older destination", received.single()["content"].asText())
    }

    @Test
    fun `disabled channel messaging does not inject destination metadata`() = backendE2eTest("e2e_channel_routing_disabled") {
        val owner = UUID.randomUUID().toString()
        val source = createPublicChat(owner, "source")
        val target = createPublicChat(owner, "target")
        assertEquals(HttpStatusCode.OK, client.patch(BackendHttpRoutes.SETTINGS) {
            trusted(owner); jsonBody("""{"enabledTools":[]}""")
        }.status)
        assertEquals(HttpStatusCode.OK, sendTurn(source, owner, "hello").status)
        val system = llm.requests.last().messages.first().content
        assertFalse(SNAPSHOT_HEADER in system)
        assertFalse(target in system)
    }

    private suspend fun BackendE2eScope.sendTurn(chatId: String, owner: String, text: String): io.ktor.client.statement.HttpResponse {
        val response = client.post(BackendHttpRoutes.chatMessages(chatId)) {
            trusted(owner); jsonBody("""{"content":"$text","options":{"model":"${E2E_MODEL.alias}"}}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val executionId = response.jsonBody()["execution"]["id"].asText()
        val terminal = eventually("terminal channel-routing execution") {
            client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(owner) }.jsonBody()["items"].firstOrNull {
                it["executionId"].asText() == executionId &&
                    when (it["type"].asText()) {
                        "execution.finished", "execution.failed", "execution.cancelled" -> true
                        else -> false
                    }
            }
        }
        assertEquals("execution.finished", terminal["type"].asText(), terminal.toString())
        return response
    }

    companion object {
        private const val SNAPSHOT_HEADER = "Current communication channels for this execution:\n"
    }
}

package ru.souz.llms.codex

import io.ktor.client.HttpClient
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.openai.OpenAiChatCodec

/** Native agent DTO adapter; model IDs and Responses behavior belong to the shared completion client. */
class CodexChatAPI(
    private val settingsProvider: SettingsProvider,
    oauthService: CodexOAuthService,
    client: HttpClient,
    endpoints: CodexEndpoints = CodexEndpoints(),
) : LLMChatAPI {
    private val codec = OpenAiChatCodec(LlmProvider.CODEX)
    private val completions = CodexCompletionClient(CodexResponsesTransport(client, settingsProvider, oauthService, endpoints))

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat = try {
        val request = codexObject("messages" to codec.buildMessages(body.messages), "stream" to false,
            "tools" to codec.buildTools(body.functions))
        body.reasoningEffort?.let { request.put("reasoning_effort", it) }
        if (body.maxTokens > 0) request.put("max_completion_tokens", body.maxTokens)
        codec.parseCompletionsResponse(completions.completion(request, body.model,
            timeoutMillis = settingsProvider.requestTimeoutMillis).single(), body.model)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: CodexFailure) {
        LLMResponse.Chat.Error(failure.status, if (failure.code == "model_not_found")
            codexObject("error" to codexObject("code" to failure.code, "message" to failure.message)).toString() else failure.message)
    } catch (failure: Exception) {
        LLMResponse.Chat.Error(-1, "Codex connection error: ${failure.message}")
    }

    // Keep the native contract: a single finalized result, with no partial tool arguments.
    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> = flow { emit(message(body)) }

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        LLMResponse.Embeddings.Error(-1, "Codex provider does not support embeddings")

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile = error("Codex provider does not support file uploads")
    override suspend fun downloadFile(fileId: String): String? = null

    override suspend fun balance(): LLMResponse.Balance = LLMResponse.Balance.Error(-1, "Balance not available for Codex")
}

package ru.souz.backend.llmgateway

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.produceIn
import kotlinx.io.readByteArray
import ru.souz.backend.http.describePublic
import ru.souz.backend.http.jsonResponse
import ru.souz.backend.http.jsonBody

internal fun Route.llmGatewayRoutes(gateway: LlmGateway) {
    if (!gateway.config.enabled) return
    get("$GATEWAY_BASE/models") {
        call.gatewayResponse(gateway) {
            call.respondText(gatewayObject("object" to "list", "data" to gateway.modelNames().map {
                gatewayObject("id" to it, "object" to "model", "created" to 0, "owned_by" to "souz")
            }).toString(), ContentType.Application.Json)
        }
    }.describePublic("listGatewayModels", "LLM Gateway", "List configured gateway models") {
        security { requirement("llmGatewayBearer") }
        responses { jsonResponse<Map<String, Any>>(HttpStatusCode.OK, "OpenAI model list.") }
    }
    post("$GATEWAY_BASE/chat/completions") {
        call.gatewayResponse(gateway) {
            if (call.request.contentType().withoutParameters() != ContentType.Application.Json) {
                throw GatewayFailure(415, "unsupported_media_type", "Content-Type must be application/json.")
            }
            val bytes = withTimeoutOrNull(5_000) {
                call.receiveChannel().readRemaining(MAX_GATEWAY_BYTES + 1L).use { it.readByteArray() }
            } ?: throw GatewayFailure(408, "request_body_timeout", "Request body timed out.")
            if (bytes.size > MAX_GATEWAY_BYTES) throw GatewayFailure(413, "request_too_large", "Request exceeds 8 MiB.")
            val body = parseGatewayObject(bytes.toString(Charsets.UTF_8))
            supervisorScope {
                val upstream = gateway.completion(body).buffer(0).produceIn(this)
                try {
                    // Resolve initial errors and fallbacks before committing the HTTP status.
                    val first = upstream.receive()
                    if (!body.path("stream").asBoolean()) {
                        upstream.receiveCatching().exceptionOrNull()?.let { throw it }
                        call.respondText(first, ContentType.Application.Json)
                    } else {
                        val written = CompletableDeferred<Unit>()
                        call.response.header(HttpHeaders.CacheControl, "no-cache")
                        call.response.header("X-Accel-Buffering", "no")
                        call.respondTextWriter(ContentType.Text.EventStream) {
                            try {
                                write("data: $first\n\n")
                                flush()
                                for (data in upstream) { write("data: $data\n\n"); flush() }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                write("data: ${asGatewayFailure(failure).body()}\n\n")
                                flush()
                            } finally {
                                written.complete(Unit)
                            }
                        }
                        written.await()
                    }
                } finally {
                    upstream.cancel()
                }
            }
        }
    }.describePublic("createGatewayChatCompletion", "LLM Gateway", "Create a model completion", "Stateless configured-model call with optional SSE streaming.") {
        security { requirement("llmGatewayBearer") }
        requestBody { jsonBody<Map<String, Any?>>("OpenAI Chat Completions request, at most 8 MiB.") }
        responses { jsonResponse<Map<String, Any>>(HttpStatusCode.OK, "OpenAI completion or text/event-stream chunks.") }
    }
}

private suspend fun ApplicationCall.gatewayResponse(gateway: LlmGateway, action: suspend () -> Unit) {
    try {
        if (!gateway.config.authenticates(request.headers.getAll(HttpHeaders.Authorization)?.singleOrNull())) {
            response.header(HttpHeaders.WWWAuthenticate, "Bearer")
            throw GatewayFailure(401, "invalid_api_key", "Invalid gateway API key.")
        }
        action()
    } catch (failure: GatewayFailure) {
        respondText(failure.body().toString(), ContentType.Application.Json, HttpStatusCode.fromValue(failure.status))
    }
}

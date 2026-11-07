package ru.souz.backend.http.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.JsonSchema
import io.ktor.openapi.JsonType
import io.ktor.openapi.ReferenceOr
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import ru.souz.backend.hooks.HookAccepted
import ru.souz.backend.hooks.HookDefinitions
import ru.souz.backend.hooks.HookRequest
import ru.souz.backend.hooks.HookService
import ru.souz.backend.hooks.HookStatus
import ru.souz.backend.hooks.hookError
import ru.souz.backend.hooks.MAX_HOOK_MULTIPART_BYTES
import ru.souz.backend.hooks.parseHookMultipart
import ru.souz.backend.http.describePublic
import ru.souz.backend.http.describeV1
import ru.souz.backend.http.jsonBody
import ru.souz.backend.http.jsonResponse
import ru.souz.backend.http.requireUserIdFromTrustedProxy
import ru.souz.backend.http.uuidPathParameter
import ru.souz.backend.http.v1ErrorResponses

internal fun Route.hookRoutes(service: HookService) {
    post("/hooks/{hookId}") {
        val authorization = call.request.headers.getAll("Authorization")?.singleOrNull()
        val accepted = service.withRequest(call.parameters["hookId"].orEmpty(), authorization) { definition ->
            val isBearer = definition.definition.auth != null
            val contentType = call.request.contentType()
            val multipart = contentType.match(ContentType.MultiPart.FormData)
            if (multipart && !isBearer) throw hookError(415, "hook_multipart_requires_bearer")
            if (isBearer && !multipart && !contentType.match(ContentType.Application.Json)) throw hookError(400, "hook_json_required")
            val headers = call.request.headers.entries().associate { it.key.lowercase() to it.value }
            if (headers.size > 32 || headers.entries.sumOf { (name, values) -> name.length + values.sumOf(String::length) } > 16_384) {
                throw hookError(400, "hook_headers_too_large")
            }
            val limit = if (multipart) MAX_HOOK_MULTIPART_BYTES else HookDefinitions.MAX_BODY_BYTES
            val body = withTimeoutOrNull(if (multipart) 15_000 else 5_000) {
                call.receiveChannel().readRemaining(limit + 1L).use { it.readByteArray() }
            } ?: throw hookError(408, "hook_body_timeout")
            if (body.size > limit) throw hookError(413, "hook_payload_too_large")
            val keys = call.request.headers.getAll("Idempotency-Key")
            if (isBearer && keys != null && keys.size != 1) throw hookError(400, "invalid_idempotency_key")
            if (multipart) return@withRequest service.acceptMultipart(definition,
                parseHookMultipart(body, contentType.toString()), keys?.singleOrNull())
            service.accept(definition, HookRequest(
                call.request.local.method.value, call.request.path(),
                headers.filterKeys { it != "host" && it != "x-user-id" && it != "x-souz-proxy-auth" }, body,
            ), keys?.singleOrNull())
        }
        call.respond(if (accepted.duplicate) HttpStatusCode.OK else HttpStatusCode.Accepted, accepted)
    }.describePublic("invokeHook", "Hooks", "Invoke a workspace hook", "Authenticates with the configured Bearer secret or verifier in the owner's sandbox before admission. Verifiers receive raw body bytes and supply the event ID. 202 means persisted, not completed.") {
        security { requirement("hookBearer"); requirement(emptyMap()) }
        parameters {
            path("hookId") { schema = JsonSchema(type = JsonType.STRING) }
            header("Idempotency-Key") { required = false; schema = JsonSchema(type = JsonType.STRING, maxLength = 200) }
        }
        requestBody {
            jsonBody<Map<String, Any?>>("JSON event up to 64 KiB, or Bearer-only multipart with one payload JSON field and 1–5 files; total body up to 10000000 bytes. Files require configured attachment storage.")
            ContentType.MultiPart.FormData {
                schema = JsonSchema(type = JsonType.OBJECT, required = listOf("payload", "files"), properties = mapOf(
                    "payload" to ReferenceOr.Value(JsonSchema(type = JsonType.STRING, description = "JSON event, at most 65536 UTF-8 bytes.")),
                    "files" to ReferenceOr.Value(JsonSchema(type = JsonType.ARRAY, minItems = 1, maxItems = 5,
                        items = ReferenceOr.Value(JsonSchema(type = JsonType.STRING, format = "binary")))),
                ))
            }
        }
        responses {
            jsonResponse<HookAccepted>(HttpStatusCode.Accepted, "Durable receipt.")
            jsonResponse<HookAccepted>(HttpStatusCode.OK, "Previously accepted receipt.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound, HttpStatusCode.Conflict, HttpStatusCode.RequestTimeout, HttpStatusCode.PayloadTooLarge, HttpStatusCode.UnsupportedMediaType, HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)
        }
    }
    post("/v1/hooks/reload") {
        call.respond(mapOf("hookIds" to service.reload(call.requireUserIdFromTrustedProxy())))
    }.describeV1("reloadHooks", "Hooks", "Reload owned workspace hooks", "Replaces this allowed owner's snapshot; invalid files disable the owner's hooks. No files are created.") {
        responses {
            jsonResponse<Map<String, List<String>>>(HttpStatusCode.OK, "Enabled, unambiguous hook IDs.")
            v1ErrorResponses(HttpStatusCode.Forbidden)
        }
    }
    get("/v1/hooks/receipts/{receiptId}") {
        val id = runCatching { UUID.fromString(call.parameters["receiptId"]) }.getOrNull()
            ?: throw hookError(400, "invalid_hook_receipt_id")
        call.respond(service.status(call.requireUserIdFromTrustedProxy(), id))
    }.describeV1("getHookReceipt", "Hooks", "Get an owned hook result", "Returns status, execution (including chatId for events, options and cancellation), usage and final response. Delivery is recorded in the execution's tool events.") {
        parameters { uuidPathParameter("receiptId", "Receipt UUID returned by invokeHook.") }
        responses {
            jsonResponse<HookStatus>(HttpStatusCode.OK, "Owned receipt and execution result.")
            v1ErrorResponses(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)
        }
    }
}

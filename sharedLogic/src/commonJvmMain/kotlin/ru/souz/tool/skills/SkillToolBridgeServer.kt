package ru.souz.tool.skills

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.state.AgentTools
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/**
 * Resolves a tool by name from exactly the set the model itself is currently allowed to call —
 * the same lookup [ToolInvokeSkill] uses for compiled/tool-backed Skills.
 */
internal fun resolveEnabledTool(
    toolCatalog: AgentToolCatalog,
    toolsFilter: AgentToolsFilter,
    name: String,
): LLMToolSetup? = AgentTools(toolsFilter.applyFilter(toolCatalog.toolsByCategory)).byName[name]

/**
 * Lets a sandboxed Skill script call already-registered Souz tools synchronously over a local
 * Unix Domain Socket, without those calls ever becoming visible turns in the conversation. One
 * connection = one message: the client writes a JSON envelope, half-closes its write side, and
 * reads the JSON response until the server closes.
 *
 * The envelope is `{"type": "tool.call"|"log", ...}`:
 * - `tool.call`: `{"type":"tool.call","name":"...","arguments":{...}}` — resolved and invoked
 *   exactly as before. [allowedToolNames] is a closed allowlist from the invoking Skill's own
 *   manifest (`souz.bridge-tools`) — never "whatever the model can currently call." An unlisted
 *   name is refused before [toolCatalog] is even consulted.
 * - `log`: `{"type":"log","level":"INFO"|"DEBUG"|"WARN"|"ERROR","message":"..."}` — written
 *   straight into the backend's own log (tagged with [skillId] and the caller's userId) instead
 *   of anywhere tool-catalog related, so a Skill's internal step-by-step diagnostics land in the
 *   same place operators already look, without a second log file to know about.
 */
internal class SkillToolBridgeServer private constructor(
    private val serverChannel: ServerSocketChannel,
    private val socketPath: Path,
    private val scope: CoroutineScope,
) {
    fun stop() {
        runCatching { serverChannel.close() }
        scope.cancel()
        runCatching { socketPath.deleteIfExists() }
    }

    companion object {
        private val l = LoggerFactory.getLogger(SkillToolBridgeServer::class.java)

        fun start(
            socketPath: Path,
            toolCatalog: AgentToolCatalog,
            toolsFilter: AgentToolsFilter,
            allowedToolNames: Set<String>,
            skillId: String,
            meta: ToolInvocationMeta,
        ): SkillToolBridgeServer {
            socketPath.parent?.let(Files::createDirectories)
            socketPath.deleteIfExists()
            val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            channel.bind(UnixDomainSocketAddress.of(socketPath))

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                acceptLoop(channel, toolCatalog, toolsFilter, allowedToolNames, skillId, meta, scope)
            }
            return SkillToolBridgeServer(channel, socketPath, scope)
        }

        private fun acceptLoop(
            channel: ServerSocketChannel,
            toolCatalog: AgentToolCatalog,
            toolsFilter: AgentToolsFilter,
            allowedToolNames: Set<String>,
            skillId: String,
            meta: ToolInvocationMeta,
            scope: CoroutineScope,
        ) {
            while (true) {
                val connection = try {
                    channel.accept()
                } catch (closed: ClosedChannelException) {
                    return
                } catch (error: Exception) {
                    if (!channel.isOpen) return
                    l.warn("Skill tool bridge accept failed: {}", error.message)
                    continue
                }
                scope.launch {
                    handleConnection(connection, toolCatalog, toolsFilter, allowedToolNames, skillId, meta)
                }
            }
        }

        private suspend fun handleConnection(
            connection: SocketChannel,
            toolCatalog: AgentToolCatalog,
            toolsFilter: AgentToolsFilter,
            allowedToolNames: Set<String>,
            skillId: String,
            meta: ToolInvocationMeta,
        ) {
            connection.use { socket ->
                val envelope = try {
                    val bytes = Channels.newInputStream(socket).readBytes()
                    restJsonMapper.readValue(bytes, BridgeEnvelope::class.java)
                } catch (error: Exception) {
                    writeResponse(socket, bridgeError("invalid_request", error.message ?: "Malformed bridge message."))
                    return
                }

                when (envelope.type) {
                    "log" -> handleLog(socket, envelope, skillId, meta)
                    "tool.call" -> handleToolCall(socket, envelope, toolCatalog, toolsFilter, allowedToolNames, meta)
                    else -> writeResponse(
                        socket,
                        bridgeError("invalid_request", "Unknown envelope type: '${envelope.type}' (expected 'tool.call' or 'log')."),
                    )
                }
            }
        }

        private fun handleLog(socket: SocketChannel, envelope: BridgeEnvelope, skillId: String, meta: ToolInvocationMeta) {
            val message = envelope.message?.trim().orEmpty()
            if (message.isEmpty()) {
                writeResponse(socket, bridgeError("invalid_request", "log message must not be blank."))
                return
            }
            val skillLogger = LoggerFactory.getLogger("ru.souz.tool.skills.bridge.skill.$skillId")
            val level = runCatching { Level.valueOf(envelope.level?.uppercase() ?: "INFO") }.getOrDefault(Level.INFO)
            val line = "user={} {}"
            when (level) {
                Level.ERROR -> skillLogger.error(line, meta.userId, message)
                Level.WARN -> skillLogger.warn(line, meta.userId, message)
                Level.DEBUG -> skillLogger.debug(line, meta.userId, message)
                Level.TRACE -> skillLogger.trace(line, meta.userId, message)
                Level.INFO -> skillLogger.info(line, meta.userId, message)
            }
            writeResponse(socket, """{"ok":true}""")
        }

        private suspend fun handleToolCall(
            socket: SocketChannel,
            envelope: BridgeEnvelope,
            toolCatalog: AgentToolCatalog,
            toolsFilter: AgentToolsFilter,
            allowedToolNames: Set<String>,
            meta: ToolInvocationMeta,
        ) {
            val name = envelope.name?.trim().orEmpty()
            if (name.isEmpty()) {
                writeResponse(socket, bridgeError("invalid_request", "Tool name must not be blank."))
                return
            }
            if (name !in allowedToolNames) {
                writeResponse(socket, bridgeError("tool_not_allowed", "Skill is not permitted to call: $name"))
                return
            }
            val tool = resolveEnabledTool(toolCatalog, toolsFilter, name)
            if (tool == null) {
                writeResponse(socket, bridgeError("tool_not_found", "No such enabled tool: $name"))
                return
            }
            val result = try {
                tool.invoke(LLMResponse.FunctionCall(name = name, arguments = envelope.arguments), meta)
            } catch (error: Exception) {
                writeResponse(socket, bridgeError("tool_invocation_failed", error.message ?: "Tool invocation failed."))
                return
            }
            writeResponse(socket, result.content)
        }

        private fun writeResponse(socket: SocketChannel, text: String) {
            runCatching {
                Channels.newOutputStream(socket).use { out -> out.write(text.toByteArray(Charsets.UTF_8)) }
            }
        }

        private fun bridgeError(code: String, message: String): String =
            restJsonMapper.writeValueAsString(mapOf("error" to mapOf("code" to code, "message" to message)))
    }

    private data class BridgeEnvelope(
        val type: String = "",
        // tool.call fields
        val name: String? = null,
        val arguments: Map<String, Any> = emptyMap(),
        // log fields
        val level: String? = null,
        val message: String? = null,
    )
}

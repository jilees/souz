package ru.souz.tool.browser

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxMode
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup

/**
 * Drives a persistent, per-user browser that acts on the user's behalf — logging in, filling
 * forms, clicking through multi-step flows.
 *
 * The session lives inside the user's runtime sandbox container (Lightpanda + the `agent-browser`
 * daemon; see `docs/backend-docker-sandbox-rollout.md`). This tool is portable — it only issues
 * `agent-browser` commands through the sandbox command executor, never touching the host OS — and
 * only works in DOCKER sandbox mode.
 *
 * Decided architecture (see docs §10): the engine is Lightpanda, and it makes no attempt to pass
 * itself off as a different browser — User-Agent, Sec-Ch-Ua and `navigator.userAgent` all honestly
 * say "Lightpanda". That, plus the from-scratch DOM, means sites with real anti-bot defenses
 * (a captcha, a "verifying your browser" page) are out of reach regardless of how this tool is
 * used — a captcha-solving step or a human handoff is a separate, deliberate concern, not
 * something to work around here. This tool is for ordinary sites and JS-rendered pages a plain
 * fetch can't handle.
 *
 * The browser is bound to the user (the container), not the conversation: page, DOM, cookies and
 * JS state persist across calls and across turns, so a flow that has to pause for the user (e.g.
 * "type the code from the SMS") just resumes on the next call — but only for as long as the
 * container stays up; a restart or recreation logs every session out (Lightpanda's `--cookie-jar`
 * does not flush on a signalled shutdown, only in-memory state exists day to day).
 *
 * `agent-browser` refs (`@e1`, `@e2`, ...) are re-assigned on every snapshot and go stale the
 * moment the page changes, so every page-changing action here returns a fresh snapshot.
 */
class ToolBrowser(
    private val sandboxResolver: ToolInvocationRuntimeSandboxResolver,
    private val mapper: ObjectMapper = restJsonMapper,
) : ToolSetup<ToolBrowser.Input> {

    @Suppress("EnumEntryName")
    enum class Action { snapshot, navigate, click, type, read, back, reset }

    data class Input(
        @InputParamDescription(
            "snapshot: list the interactive elements on the current page with @refs. " +
                "navigate: open a URL. click: click an element by its @ref from the latest snapshot. " +
                "type: enter text into an input by @ref. read: return the visible page text. " +
                "back: navigate back. reset: blank the page (optionally clearing cookies).",
        )
        val action: Action,
        @InputParamDescription("navigate: the URL to open (http:// or https:// only).")
        val url: String? = null,
        @InputParamDescription("click / type: element reference from the latest snapshot, e.g. \"e5\".")
        val ref: String? = null,
        @InputParamDescription("type: the text to enter into the referenced field.")
        val text: String? = null,
        @InputParamDescription("type: press Enter after entering the text.")
        val submit: Boolean = false,
        @InputParamDescription("reset: also clear every cookie (logs the session out of all sites).")
        val clearCookies: Boolean = false,
        @InputParamDescription("read: maximum characters of page text to return.")
        val maxChars: Int = 6000,
    )

    override val name: String = "browser"

    override val description: String =
        "A real browser for the user — an honest automated client, not disguised as a different " +
            "browser. It will not get past sites with real anti-bot defenses: a captcha, a " +
            "'verifying your browser' page, or a persistent block. If one shows up, say so plainly " +
            "instead of retrying the same action — it will not resolve itself. Use it to (1) act on " +
            "the user's behalf on ordinary sites — log in, fill forms, click through multi-step " +
            "flows — and (2) open or read JavaScript-rendered pages a plain fetch can't handle, or " +
            "whenever WebPageText / a fetch returns empty or too little. One shared browser per " +
            "user; page and login state persist between calls and turns, but only while the sandbox " +
            "container stays up — a restart logs every session out. Workflow: 'navigate' to a URL, " +
            "then 'snapshot' to see the interactive elements, act on the @refs it returns, and read " +
            "the fresh snapshot every action returns — refs go stale on any page change. Use 'read' " +
            "for the plain visible text of the current page. For a login that needs an SMS/OTP " +
            "code: submit the phone, ask the user for the code in a normal reply, then continue " +
            "with 'type'."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Открой wildberries.ru",
            params = mapOf("action" to Action.navigate, "url" to "https://www.wildberries.ru"),
        ),
        FewShotExample(
            request = "Что сейчас на странице?",
            params = mapOf("action" to Action.snapshot),
        ),
        FewShotExample(
            request = "Нажми кнопку «Войти»",
            params = mapOf("action" to Action.click, "ref" to "e12"),
        ),
        FewShotExample(
            request = "Введи телефон +7 999 000-00-00 и подтверди",
            params = mapOf("action" to Action.type, "ref" to "e8", "text" to "+79990000000", "submit" to true),
        ),
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "url" to ReturnProperty("string", "Current page URL after the action"),
            "title" to ReturnProperty("string", "Current page title"),
            "snapshot" to ReturnProperty(
                "string",
                "Interactive-element tree with @refs (snapshot / navigate / click / type / back)",
            ),
            "text" to ReturnProperty("string", "Visible page text (read)"),
            "error" to ReturnProperty("string", "Present when the action failed"),
        ),
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String =
        runBlocking { suspendInvoke(input, meta) }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        val sandbox = sandboxResolver.resolve(meta)
        if (sandbox.mode != SandboxMode.DOCKER) {
            return err("The browser tool requires DOCKER sandbox mode (SOUZ_SANDBOX_MODE=docker).")
        }
        val exec = sandbox.commandExecutor

        suspend fun ab(vararg args: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): SandboxCommandResult =
            exec.execute(
                SandboxCommandRequest(
                    runtime = SandboxCommandRuntime.PROCESS,
                    command = listOf(AGENT_BROWSER) + args,
                    timeoutMillis = timeoutMs,
                ),
            )

        fun SandboxCommandResult.failText() = stderr.ifBlank { stdout }.trim().take(ERROR_CAP)

        // Behavioural realism: anti-bot scoring on submit/search flags instant
        // robotic input. Short randomised pauses between the sub-steps of an
        // action, and per-character keystrokes (agent-browser `keyboard type`
        // fires keydown/keypress/keyup), look like a person rather than a script.
        suspend fun pause(minMs: Long, maxMs: Long) = delay(Random.nextLong(minMs, maxMs + 1))

        // click, but with a mouse approach: scroll it into view, hover (fires
        // mousemove/mouseover), dwell, then click.
        suspend fun humanClick(ref: String, timeoutMs: Long = NAV_TIMEOUT_MS): SandboxCommandResult {
            ab("scrollintoview", ref, timeoutMs = DEFAULT_TIMEOUT_MS)
            pause(120, 350)
            ab("hover", ref, timeoutMs = DEFAULT_TIMEOUT_MS)
            pause(150, 450)
            return ab("click", ref, timeoutMs = timeoutMs)
        }

        suspend fun snapshotResponse(): String {
            val snap = ab("snapshot", "-i")
            if (snap.exitCode != 0 && snap.stdout.isBlank()) {
                return err("snapshot failed: ${snap.failText()}")
            }
            val url = ab("get", "url").stdout.trim()
            val title = ab("get", "title").stdout.trim()
            return mapper.writeValueAsString(
                buildMap {
                    put("url", url)
                    put("title", title)
                    put("snapshot", snap.stdout.trim().take(SNAPSHOT_CAP))
                },
            )
        }

        return try {
            when (input.action) {
                Action.snapshot -> snapshotResponse()

                Action.read -> {
                    val r = ab("get", "text", "body")
                    if (r.exitCode != 0) {
                        err("read failed: ${r.failText()}")
                    } else {
                        mapper.writeValueAsString(
                            mapOf(
                                "url" to ab("get", "url").stdout.trim(),
                                "text" to r.stdout.trim().take(input.maxChars.coerceIn(500, 20_000)),
                            ),
                        )
                    }
                }

                Action.navigate -> {
                    val url = requireHttpUrl(input.url)
                    val r = ab("open", url, timeoutMs = NAV_TIMEOUT_MS)
                    if (r.exitCode != 0) {
                        err("navigate failed: ${r.failText()}")
                    } else {
                        pause(700, 1600) // let the page settle; a person doesn't act instantly
                        snapshotResponse()
                    }
                }

                Action.click -> {
                    pause(200, 700) // think time
                    val r = humanClick(requireRef(input.ref))
                    if (r.exitCode != 0) {
                        err("click failed: ${r.failText()}")
                    } else {
                        pause(200, 600)
                        snapshotResponse()
                    }
                }

                Action.type -> {
                    val ref = requireRef(input.ref)
                    val text = input.text ?: throw BadInputException("type requires 'text'")
                    pause(200, 700)
                    // focus the field the way a person does — approach + click
                    val focusRes = humanClick(ref, timeoutMs = DEFAULT_TIMEOUT_MS)
                    if (focusRes.exitCode != 0) {
                        return err("type failed (focusing field): ${focusRes.failText()}")
                    }
                    pause(120, 350)
                    // clear any existing value, then type character-by-character
                    // (keyboard type fires real keydown/keypress/keyup per char)
                    ab("press", "Control+a", timeoutMs = DEFAULT_TIMEOUT_MS)
                    ab("press", "Delete", timeoutMs = DEFAULT_TIMEOUT_MS)
                    pause(80, 250)
                    if (text.isEmpty()) return err("type failed: empty text")
                    if (text.length <= SHORT_INPUT_MAX && text.all { it.code in 0x20..0x7E }) {
                        // Short ASCII input (phone, OTP, email, login): press each key
                        // — agent-browser `press` fires real keydown/keypress/keyup.
                        for (ch in text) {
                            val k = ab("press", if (ch == ' ') "Space" else ch.toString(), timeoutMs = DEFAULT_TIMEOUT_MS)
                            if (k.exitCode != 0) return err("type failed: ${k.failText()}")
                            pause(45, 160)
                        }
                    } else {
                        // Longer / non-ASCII (search queries, Cyrillic): `keyboard type`
                        // only fires beforeinput/input, so chunk it for a human macro-rhythm.
                        for (chunk in text.chunkedForTyping()) {
                            val t = ab("keyboard", "type", chunk, timeoutMs = NAV_TIMEOUT_MS)
                            if (t.exitCode != 0) return err("type failed: ${t.failText()}")
                            pause(55, 190)
                        }
                    }
                    if (input.submit) {
                        pause(250, 800)
                        ab("press", "Enter", timeoutMs = NAV_TIMEOUT_MS)
                    }
                    pause(200, 600)
                    snapshotResponse()
                }

                Action.back -> {
                    ab("back", timeoutMs = NAV_TIMEOUT_MS)
                    snapshotResponse()
                }

                Action.reset -> {
                    if (input.clearCookies) ab("cookies", "clear")
                    ab("open", "about:blank", timeoutMs = NAV_TIMEOUT_MS)
                    mapper.writeValueAsString(mapOf("url" to "about:blank", "title" to "", "snapshot" to ""))
                }
            }
        } catch (e: BadInputException) {
            err(e.message ?: "bad input")
        }
    }

    private fun err(message: String): String = mapper.writeValueAsString(mapOf("error" to message))

    private fun requireHttpUrl(raw: String?): String {
        val u = raw?.trim().orEmpty()
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw BadInputException("navigate requires an http:// or https:// URL")
        }
        return u
    }

    private fun requireRef(raw: String?): String {
        val r = raw?.trim()?.removePrefix("@").orEmpty()
        if (!REF_PATTERN.matches(r)) {
            throw BadInputException("ref must look like 'e5' — copy it from the latest snapshot")
        }
        return "@$r"
    }

    /** Split text into 2–4 char chunks so typing has a human macro-rhythm. */
    private fun String.chunkedForTyping(): List<String> {
        if (isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var i = 0
        while (i < length) {
            val n = Random.nextInt(2, 5).coerceAtMost(length - i)
            out.add(substring(i, i + n))
            i += n
        }
        return out
    }

    private companion object {
        const val AGENT_BROWSER = "agent-browser"
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val NAV_TIMEOUT_MS = 45_000L
        const val SNAPSHOT_CAP = 12_000
        const val ERROR_CAP = 500
        const val SHORT_INPUT_MAX = 24
        val REF_PATTERN = Regex("e\\d+")
    }
}

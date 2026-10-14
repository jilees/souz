package ru.souz.backend.attachments

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.direct
import org.kodein.di.instance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import ru.souz.backend.app.backendDiModule
import ru.souz.backend.storage.postgres.PostgresAttachmentRepository
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.PostgresUserRepository
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig
import ru.souz.backend.storage.postgres.write
import ru.souz.db.SettingsProvider
import ru.souz.llms.FileContent
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox

class AttachmentStorageTest {
    @Test
    fun `document and text share one receipt with lossless overflow and replay`() = attachmentTest {
        val files = listOf(save("alice", "page", "page.html", "<h1>Page</h1>"), save("alice", "notes", "notes.md", "# Notes")).map { it.toContent() }
        val delivery = AttachmentDelivery(storage, dataSource)
        val text = "1234567😀 сопровождение документа"
        val sent = mutableListOf<String>()
        val acceptedText = StringBuilder()
        val acceptedFiles = mutableListOf<FileContent>()
        suspend fun deliver() = delivery.deliver("alice", "combined", text, files, 20,
            documentTextLimit = 8,
            onAccepted = { part, file -> part?.let(acceptedText::append); file?.let(acceptedFiles::add) },
            sendText = { sent += it }, sendFile = { file, _ -> sent += file.name; "file" },
            sendDocumentWithText = { file, _, caption ->
                assertEquals("page.html", file.name)
                assertEquals("1234567", caption)
                sent += "document:$caption"; "combined"
            })
        deliver()
        assertEquals(text, acceptedText.toString())
        assertEquals(files, acceptedFiles)
        assertEquals("document:1234567", sent.first())
        assertEquals("notes.md", sent.last())
        val sentCount = sent.size
        deliver()
        assertEquals(sentCount, sent.size)
    }

    @Test
    fun `combined sender resumes legacy layout and does not repeat uncertain combined sends`() = attachmentTest {
        val file = save("alice", "page", "page.html", "<h1>Page</h1>").toContent()
        val delivery = AttachmentDelivery(storage, dataSource)
        var sends = 0
        delivery.deliver("alice", "legacy", "Готово", listOf(file), 20,
            sendText = { sends++ }, sendFile = { _, _ -> sends++; "file" })
        delivery.deliver("alice", "legacy", "Готово", listOf(file), 20,
            sendText = { error("Text already delivered") }, sendFile = { _, _ -> error("File already delivered") },
            sendDocumentWithText = { _, _, _ -> error("Must preserve the old delivery layout") })
        assertEquals(2, sends)
        suspend fun uncertain() = delivery.deliver("alice", "uncertain-combined", "Готово", listOf(file), 20,
            sendText = { error("Text belongs to the document") }, sendFile = { _, _ -> error("File belongs to combined message") },
            sendDocumentWithText = { _, _, _ -> sends++; throw java.io.IOException("Unknown result") })
        assertFailsWith<java.io.IOException> { uncertain() }
        assertEquals("attachment_delivery_uncertain", assertFailsWith<AttachmentException> { uncertain() }.code)
        assertEquals(3, sends)
    }

    @Test
    fun `account deletion during commit leaves late object eligible for cleanup`() = attachmentTest {
        execute("""create function remove_attachment_owner() returns trigger language plpgsql as
            'begin delete from users where id = new.user_id; new.state := ''deleted''; return new; end'""")
        execute("""create trigger remove_attachment_owner before update on stored_attachments
            for each row when (new.state = 'ready') execute function remove_attachment_owner()""")
        assertFailsWith<IllegalArgumentException> { save("alice", "late-write", "private.txt", "private bytes") }
        assertEquals(1, storage.cleanup())
        assertEquals(0, storage.cleanup())
        assertEquals(null, storage.find("alice", "late-write"))
    }

    @Test
    fun `vision resolves pixels only for five occurrences and leaves durable history untouched`() = attachmentTest {
        val png = java.io.ByteArrayOutputStream().also { output ->
            javax.imageio.stream.MemoryCacheImageOutputStream(output).use {
                javax.imageio.ImageIO.write(java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
            }
        }.toByteArray()
        val file = storage.save("alice", "image", "image.png", png.inputStream()).toContent()
        val request = ru.souz.llms.LLMRequest.Chat("gpt-6.1-sol", provider = ru.souz.llms.LlmProvider.CODEX,
            messages = List(6) { ru.souz.llms.LLMRequest.Message(ru.souz.llms.LLMMessageRole.user, "Describe",
                files = listOf(file), images = listOf(ru.souz.llms.ImageContent(file.file))) })
        val input = BackendAttachmentInput(storage, config)
        val prepared = input.prepare("alice", request) { _, _, _ -> error("No fallback") }
        assertEquals(5, prepared.messages.sumOf { it.attachments.orEmpty().size })
        assertTrue(prepared.messages.drop(1).all { it.attachments!!.single().startsWith("data:image/png;base64,") })
        assertTrue(request.messages.all { it.files == listOf(file) && it.attachments.isNullOrEmpty() })
        val foreign = input.prepare("bob", request) { _, _, _ -> error("Foreign pixels") }
        assertTrue(foreign.messages.all { it.attachments.isNullOrEmpty() && "attachment_not_found" in it.content })
        clock.now = clock.now.plus(Duration.ofDays(30))
        val expired = input.prepare("alice", request) { _, _, _ -> error("Expired pixels") }
        assertTrue(expired.messages.all { it.attachments.isNullOrEmpty() && "attachment_expired" in it.content })
    }

    @Test
    fun `delivery receipts skip accepted parts and never replay an uncertain send`() = attachmentTest {
        val first = save("alice", "first", "a.md", "First")
        val second = save("alice", "second", "b.md", "Second")
        val delivery = AttachmentDelivery(storage, dataSource)
        var sends = 0
        val deliver: suspend () -> Unit = {
            delivery.deliver("alice", "reply-key", "", listOf(first.toContent(), second.toContent()), 4000,
                sendText = { error("No empty text part") }, sendFile = { _, _ ->
                    sends++
                    if (sends == 2) throw java.io.IOException("Remote outcome unknown")
                    "receipt-1"
                })
        }
        assertFailsWith<java.io.IOException> { deliver() }
        assertEquals("attachment_delivery_uncertain", assertFailsWith<AttachmentException> { deliver() }.code)
        assertEquals(2, sends)
        assertFailsWith<AttachmentException> {
            delivery.deliver("bob", "foreign", "text must not go first", listOf(first.toContent()), 4000,
                sendText = { error("Must validate files before sending text") }, sendFile = { _, _ -> error("Foreign file") })
        }
    }

    @Test
    fun `private per-user objects survive restart and recover edited working copies`() = attachmentTest {
        val first = save("alice", "event", "../report.md", "# Привет")
        val second = save("bob", "event", "report.md", "# Привет")
        assertNotEquals(first.bucket, second.bucket)
        assertEquals("report.md", first.name)
        assertEquals("text/markdown", first.mimeType)
        assertEquals("attachment_not_found", assertFailsWith<AttachmentException> { storage.read("bob", first.id) }.code)
        val anonymous = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("${config.endpoint}/${first.bucket}/${first.objectKey}")).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(403, anonymous.statusCode())
        val root = Files.createTempDirectory("attachment-sandbox")
        try {
            val fs = LocalRuntimeSandbox(SandboxScope("alice"), di.instance<SettingsProvider>(), root, root.resolve("state"), root).fileSystem
            val path = storage.prepare("alice", first.id, fs)
            assertEquals("# Привет", fs.readText(fs.resolveExistingFile(path)))
            fs.writeText(fs.resolveExistingFile(path), "edited")
            val restarted = AttachmentStorage(config, PostgresAttachmentRepository(dataSource), objects, clock)
            restarted.prepare("alice", first.id, fs)
            assertEquals("# Привет", fs.readText(fs.resolveExistingFile(path)))
            fs.delete(fs.resolveExistingDirectory("$root/attachments"), recursively = true)
            restarted.prepare("alice", first.id, fs)
            assertEquals("# Привет", fs.readText(fs.resolveExistingFile(path)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `concurrent retry preserves identity bytes and expiry without prolongation`() = attachmentTest {
        val first = coroutineScope {
            val a = async { save("alice", "same-event", "a.txt", "original") }
            val b = async { save("alice", "same-event", "a.txt", "original") }
            a.await().also { assertEquals(it, b.await()) }
        }
        clock.now = clock.now.plus(Duration.ofDays(29))
        assertEquals(first, save("alice", "same-event", "a.txt", "original"))
        assertEquals("attachment_idempotency_conflict", assertFailsWith<AttachmentException> {
            save("alice", "same-event", "a.txt", "replacement")
        }.code)
        clock.now = first.expiresAt!!
        assertEquals("attachment_expired", assertFailsWith<AttachmentException> { storage.read("alice", first.id) }.code)
        assertEquals(1, storage.cleanup())
        assertEquals(0, storage.cleanup())
        assertFailsWith<Exception> { objects.read(first, config.maxFileBytes) }
        assertEquals("attachment_expired", assertFailsWith<AttachmentException> {
            save("alice", "same-event", "a.txt", "original")
        }.code)
    }

    @Test
    fun `failed database commit after PUT recovers and abandoned uploads are cleaned`() = attachmentTest {
        execute("""create function reject_attachment() returns trigger language plpgsql as 'begin raise exception ''test failure''; end'""")
        execute("""create constraint trigger reject_attachment after update on stored_attachments deferrable initially deferred for each row execute function reject_attachment()""")
        assertFailsWith<java.sql.SQLException> { save("alice", "interrupted", "a.txt", "persisted") }
        execute("drop trigger reject_attachment on stored_attachments")
        val recovered = save("alice", "interrupted", "a.txt", "persisted")
        assertContentEquals("persisted".toByteArray(), storage.read("alice", recovered.id))
        execute("update stored_attachments set state = 'pending', stored_at = null, expires_at = null")
        clock.now = clock.now.plus(Duration.ofHours(24))
        assertEquals(1, storage.cleanup())
        assertFailsWith<Exception> { objects.read(recovered, config.maxFileBytes) }
    }

    @Test
    fun `oversized and interrupted streams fail before persistence and user deletion cleans objects`() = attachmentTest {
        assertEquals("attachment_too_large", assertFailsWith<AttachmentException> {
            storage.save("alice", "large", "large.txt", ByteArray(config.maxFileBytes + 1).inputStream())
        }.code)
        val broken = object : InputStream() { override fun read(): Int = throw java.io.IOException("broken") }
        assertFailsWith<java.io.IOException> { storage.save("alice", "broken", "a.txt", broken) }
        val file = save("alice", "kept", "a.txt", "private")
        execute("delete from users where id = 'alice'")
        assertEquals(1, storage.cleanup())
        assertFailsWith<Exception> { objects.read(file, config.maxFileBytes) }
        assertTrue(file.bucket.startsWith(config.bucketPrefix))
    }
}

internal object AttachmentMinio {
    fun config(ttsEndpoint: String? = null, mediaApiKey: String? = null) = AttachmentStorageConfig(
        mediaApiKey = mediaApiKey,
        ttsEndpoint = ttsEndpoint,
        endpoint = "http://${server.host}:${server.firstMappedPort}",
        accessKey = "attachment-test", secretKey = "attachment-test-secret",
    )

    val server = GenericContainer<Nothing>("quay.io/minio/minio:RELEASE.2023-09-04T19-57-37Z").apply {
        withEnv("MINIO_ROOT_USER", "attachment-test")
        withEnv("MINIO_ROOT_PASSWORD", "attachment-test-secret")
        withCommand("server", "/data")
        withExposedPorts(9000)
        waitingFor(Wait.forHttp("/minio/health/live").forPort(9000))
        start()
    }
}

private class AttachmentClock(var now: Instant = Instant.parse("2026-10-13T00:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}

private fun attachmentTest(block: suspend AttachmentFixture.() -> Unit) = runBlocking {
    AttachmentFixture().use { fixture ->
        PostgresUserRepository(fixture.dataSource).ensureUser("alice")
        PostgresUserRepository(fixture.dataSource).ensureUser("bob")
        fixture.block()
    }
}

private class AttachmentFixture : AutoCloseable {
    private val appConfig = postgresAppConfig(newPostgresSchema("attachments"))
    val dataSource = PostgresDataSourceFactory.create(appConfig.postgres)
    val clock = AttachmentClock()
    val config = AttachmentStorageConfig(
        endpoint = "http://${AttachmentMinio.server.host}:${AttachmentMinio.server.firstMappedPort}",
        accessKey = "attachment-test", secretKey = "attachment-test-secret", maxFileBytes = 1024,
    )
    val di = DI {
        import(backendDiModule("Attachment test", appConfig.copy(attachments = config), dataSourceFactory = { dataSource }))
        bindSingleton<Clock>(overrides = true) { clock }
    }.direct
    val storage: AttachmentStorage = di.instance()
    val objects: S3AttachmentObjects = di.instance()
    suspend fun save(user: String, key: String, name: String, text: String) = storage.save(user, key, name, text.byteInputStream())
    suspend fun execute(sql: String) = dataSource.write { it.createStatement().use { statement -> statement.execute(sql) } }
    override fun close() { objects.close(); dataSource.close() }
}

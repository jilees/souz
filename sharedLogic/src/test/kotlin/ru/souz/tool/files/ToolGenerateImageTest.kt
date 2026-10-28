package ru.souz.tool.files

import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import ru.souz.db.SettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.llms.runtime.GeneratedImage
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.ImageGenerationSource
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolGenerateImageTest {
    private val tempRoots = mutableListOf<Path>()

    @Test
    fun `editing reads ordered original sources through the invoking sandbox`() = runTest {
        val homeDir = tempDir("home")
        val sources = listOf(
            java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="),
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 42),
        )
        val paths = sources.mapIndexed { i, bytes -> Files.write(homeDir.resolve("source-$i"), bytes).toString() }
        var calls = 0
        val gateway = ImageGenerationGateway { input ->
            calls++
            assertEquals("Change the background", input.prompt)
            assertEquals(listOf("image/png", "image/jpeg"), input.images.map { it.mimeType })
            input.images.zip(sources).forEach { (actual, expected) -> assertContentEquals(expected, actual.bytes) }
            GeneratedImage(sources.first(), "image/png", "CODEX")
        }
        val tool = ToolGenerateImage(createFilesToolUtil(homeDir), gateway)
        val result = restJsonMapper.readTree(tool.suspendInvoke(ToolGenerateImage.Input(
            " Change the background ", imagePaths = paths,
        ), ToolInvocationMeta.localDefault()))
        assertContentEquals(sources.first(), Files.readAllBytes(Path.of(result["outputPath"].asText())))
        assertEquals(1, calls)
    }

    @Test
    fun `invalid oversized excess and inaccessible sources never call the generator`() = runTest {
        val homeDir = tempDir("home")
        val text = Files.writeString(homeDir.resolve("fake.png"), "not an image").toString()
        val oversized = Files.write(homeDir.resolve("large.png"), ByteArray(ImageGenerationSource.MAX_BYTES + 1)).toString()
        val outside = Files.writeString(tempDir("outside").resolve("image.png"), "outside").toString()
        val tool = ToolGenerateImage(createFilesToolUtil(homeDir), ImageGenerationGateway { error("must not generate") })
        for (paths in listOf(listOf(text), listOf(oversized), List(6) { text }, listOf(outside))) {
            assertFailsWith<ru.souz.tool.BadInputException> {
                tool.suspendInvoke(ToolGenerateImage.Input("Edit", imagePaths = paths), ToolInvocationMeta.localDefault())
            }
        }
    }

    @AfterEach
    fun cleanup() {
        tempRoots.asReversed().forEach { path -> runCatching { path.toFile().deleteRecursively() } }
        tempRoots.clear()
    }

    @Test
    fun `writes generated image to default safe path`() = runTest {
        val homeDir = tempDir("home")
        val filesToolUtil = createFilesToolUtil(homeDir)
        val gateway = mockk<ImageGenerationGateway>()
        val imageBytes = byteArrayOf(9, 8, 7, 6)
        coEvery { gateway.generate(any()) } returns GeneratedImage(
            bytes = imageBytes,
            mimeType = "image/png",
            provider = "OPENAI",
            model = "gpt-image-1",
        )

        val tool = ToolGenerateImage(
            filesToolUtil = filesToolUtil,
            imageGenerationGateway = gateway,
        )

        val raw = tool.suspendInvoke(
            ToolGenerateImage.Input(
                prompt = "A tiny red cube",
                outputPath = null,
            ),
            ToolInvocationMeta.localDefault(),
        )

        val result: Map<String, String> = restJsonMapper.readValue(raw)
        val savedPath = Path.of(result.getValue("outputPath"))

        assertTrue(savedPath.exists())
        assertContentEquals(imageBytes, savedPath.readBytes())
        assertEquals("image/png", result["mimeType"])
        assertEquals("OPENAI", result["provider"])
        assertEquals("gpt-image-1", result["model"])
        assertEquals(
            filesToolUtil.souzDocumentsDirectoryPath.toAbsolutePath().normalize().toRealPath(),
            savedPath.parent.toAbsolutePath().normalize().toRealPath(),
        )
    }

    @Test
    fun `rejects explicit output path with mismatched extension`() = runTest {
        val homeDir = tempDir("home")
        val filesToolUtil = createFilesToolUtil(homeDir)
        val gateway = mockk<ImageGenerationGateway>()
        coEvery { gateway.generate(any()) } returns GeneratedImage(
            bytes = byteArrayOf(9, 8, 7, 6),
            mimeType = "image/png",
            provider = "OPENAI",
            model = "gpt-image-1",
        )

        val tool = ToolGenerateImage(
            filesToolUtil = filesToolUtil,
            imageGenerationGateway = gateway,
        )

        val error = assertFailsWith<ru.souz.tool.BadInputException> {
            tool.suspendInvoke(
                ToolGenerateImage.Input(
                    prompt = "A tiny red cube",
                    outputPath = "${homeDir.resolve("Pictures/result.jpg")}",
                ),
                ToolInvocationMeta.localDefault(),
            )
        }

        assertEquals("outputPath extension .jpg does not match generated MIME type image/png", error.message)
    }

    private fun createFilesToolUtil(homeDir: Path): FilesToolUtil {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns emptyList()
        return FilesToolUtil(
            LocalRuntimeSandbox(
                scope = SandboxScope(userId = "tool-generate-image-test"),
                settingsProvider = settingsProvider,
                homePath = homeDir,
                stateRoot = tempDir("state"),
            )
        )
    }

    private fun tempDir(prefix: String): Path =
        Files.createTempDirectory(prefix).also(tempRoots::add)
}

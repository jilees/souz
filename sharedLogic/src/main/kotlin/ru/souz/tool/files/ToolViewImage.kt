package ru.souz.tool.files

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.runtime.DEFAULT_MAX_VISION_IMAGE_BYTES
import ru.souz.llms.runtime.ImageFileFormats
import ru.souz.llms.runtime.MAX_VISION_IMAGES
import ru.souz.llms.runtime.VisionGateway
import ru.souz.llms.runtime.VisionImage
import ru.souz.llms.runtime.VisionInput
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup

class ToolViewImage(
    private val filesToolUtil: FilesToolUtil,
    private val visionGateway: VisionGateway,
    private val maxImageBytes: Long = DEFAULT_MAX_VISION_IMAGE_BYTES,
) : ToolSetup<ToolViewImage.Input> {

    data class Input(
        @InputParamDescription("Absolute path to one image. Supply either imagePath or imagePaths, not both.")
        val imagePath: String? = null,
        @InputParamDescription("Question about the images. For PDF review, map image order to page numbers and request only concrete defects per page.")
        val question: String,
        @InputParamDescription("Ordered list of 1 to 5 absolute image paths to analyze together in one model request. Alternative to imagePath.")
        val imagePaths: List<String> = emptyList(),
    )

    override val name: String = NAME
    override val description: String =
        "Analyze one image or up to five images together with the current multimodal model. " +
            "Use imagePaths for a batch of PDF pages instead of separate calls per page."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Посмотри /Users/me/Pictures/cat.png и опиши, что на нем.",
            params = mapOf(
                "imagePath" to "/Users/me/Pictures/cat.png",
                "question" to "Что на изображении?",
            ),
        ),
        FewShotExample(
            request = "Check two PDF pages together",
            params = mapOf(
                "imagePaths" to listOf("/souz/workspace/pdf-pages/page-1.png", "/souz/workspace/pdf-pages/page-2.png"),
                "question" to "Images are PDF pages 1 and 2, in that order. List only concrete readability or layout defects per page; say OK for pages without defects.",
            ),
        ),
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Model answer about the image."),
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String = runBlocking { suspendInvoke(input, meta) }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        if (input.question.isBlank()) {
            throw BadInputException("question must not be empty")
        }
        if (input.imagePath != null && input.imagePaths.isNotEmpty()) {
            throw BadInputException("Supply either imagePath or imagePaths, not both")
        }
        val paths = input.imagePath?.let { listOf(it) } ?: input.imagePaths
        if (paths.size !in 1..MAX_VISION_IMAGES) {
            throw BadInputException("Supply 1 to $MAX_VISION_IMAGES image paths")
        }
        paths.forEach(::requireAbsolutePath)
        return analyzeImages(paths, 0, emptyList(), input.question.trim(), meta)
    }

    // Nested scopes keep every sandbox temporary file alive until the single model call finishes.
    private suspend fun analyzeImages(
        paths: List<String>,
        index: Int,
        images: List<VisionImage>,
        question: String,
        meta: ToolInvocationMeta,
    ): String {
        if (index == paths.size) {
            val first = images.first()
            return visionGateway.analyze(VisionInput(
                imagePath = first.imagePath,
                mimeType = first.mimeType,
                sizeBytes = first.sizeBytes,
                question = question,
                additionalImages = images.drop(1),
            ))
        }
        val image = filesToolUtil.resolveSafeExistingFile(paths[index], meta)
        return filesToolUtil.withReadableLocalPathSuspend(image, meta) { localImagePath ->
            val sizeBytes = image.sizeBytes
                ?: runCatching { Files.size(localImagePath) }.getOrElse {
                    throw BadInputException("Failed to determine image size for ${image.name}")
                }
            requireSizeWithinLimit(sizeBytes)
            val mimeType = ImageFileFormats.detectMimeType(localImagePath)
                ?: throw BadInputException("Unsupported image type: ${image.name}")
            analyzeImages(paths, index + 1, images + VisionImage(localImagePath, mimeType, sizeBytes), question, meta)
        }
    }

    private fun requireAbsolutePath(rawPath: String) {
        // Docker sandbox paths are POSIX-absolute even when the host is Windows.
        val path = runCatching { Path.of(rawPath) }.getOrNull()
        if (path == null || !(path.isAbsolute || rawPath.startsWith("/"))) {
            throw BadInputException("imagePath must be an absolute local path")
        }
    }

    private fun requireSizeWithinLimit(sizeBytes: Long) {
        if (sizeBytes > maxImageBytes) {
            throw BadInputException("image file is too large: $sizeBytes bytes exceeds limit of $maxImageBytes bytes")
        }
    }

    companion object {
        const val NAME = "ViewImage"
    }
}

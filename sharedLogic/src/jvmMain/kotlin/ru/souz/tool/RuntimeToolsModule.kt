package ru.souz.tool

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import org.kodein.di.instanceOrNull
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.giga.toGiga
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxFactory
import ru.souz.runtime.sandbox.ToolInvocationSandboxScopeResolver
import ru.souz.tool.browser.ToolBrowser
import ru.souz.tool.dataAnalytics.ToolCreatePlotFromCsv
import ru.souz.tool.dataAnalytics.excel.ExcelRead
import ru.souz.tool.dataAnalytics.excel.ExcelReport
import ru.souz.tool.files.ToolExtractText
import ru.souz.tool.files.ToolReadPdfPages
import ru.souz.tool.web.ToolWebImageSearch
import ru.souz.tool.web.internal.WebImageDownloader
import ru.souz.tool.web.internal.WebResearchClient

fun runtimeToolsDiModule(
    includeWebImageSearch: Boolean = true,
    scopeResolver: ToolInvocationSandboxScopeResolver = defaultToolInvocationSandboxScopeResolver(),
): DI.Module = DI.Module("runtimeTools") {
    bindSingleton<RuntimeSandboxFactory> { DefaultRuntimeSandboxFactory(settingsProvider = instance()) }
    import(
        portableRuntimeToolsDiModule(
            scopeResolver = scopeResolver,
            bindAgentToolCatalog = false,
        )
    )
    bindSingleton { ToolExtractText(instance()) }
    bindSingleton { ToolReadPdfPages(instance()) }

    // Sandbox-hosted browser session (Lightpanda + agent-browser daemon). DOCKER mode only —
    // no-ops with an error under LOCAL. Backend-only: not wired into the desktop catalog.
    bindSingleton { ToolBrowser(sandboxResolver = instance()) }

    bindSingleton { ToolCreatePlotFromCsv(instance()) }
    bindSingleton { ExcelRead(instance()) }
    bindSingleton { ExcelReport(instance()) }

    if (includeWebImageSearch) {
        bindSingleton { WebImageDownloader(instance()) }
        bindSingleton { ToolWebImageSearch(filesToolUtil = instance(), webResearchClient = instance(), webImageDownloader = instance()) }
    }

    bindRuntimeToolsFactory(includeWebImageSearch = includeWebImageSearch)
    bindSingleton<AgentToolCatalog> { instance<RuntimeToolsFactory>() }
}

fun DI.Builder.bindRuntimeToolsFactory(
    includeWebImageSearch: Boolean = true,
) {
    bindSingleton {
        RuntimeToolsFactory(
            portableToolsFactory = instance(),
            toolExtractText = instance(),
            toolReadPdfPages = instance(),
            toolCreatePlotFromCsv = instance(),
            excelRead = instance(),
            excelReport = instance(),
            toolWebImageSearch = if (includeWebImageSearch) instance() else null,
            toolBrowser = instanceOrNull(),
        )
    }
}

class RuntimeToolsFactory(
    portableToolsFactory: PortableRuntimeToolsFactory,
    toolExtractText: ToolExtractText,
    toolReadPdfPages: ToolReadPdfPages,
    toolCreatePlotFromCsv: ToolCreatePlotFromCsv,
    excelRead: ExcelRead,
    excelReport: ExcelReport,
    toolWebImageSearch: ToolWebImageSearch?,
    toolBrowser: ToolBrowser? = null,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
        composeToolCatalogs(
            portableToolsFactory,
            immutableToolCatalogFromLists(
                mapOf(
                    ToolCategory.FILES to listOf(
                        toolExtractText.toGiga(),
                        toolReadPdfPages.toGiga(),
                    ),
                    ToolCategory.WEB_SEARCH to listOfNotNull(toolWebImageSearch?.toGiga()),
                    ToolCategory.BROWSER to listOfNotNull(toolBrowser?.toGiga()),
                    ToolCategory.DATA_ANALYTICS to listOf(
                        toolCreatePlotFromCsv.toGiga(),
                        excelRead.toGiga(),
                        excelReport.toGiga(),
                    ),
                )
            ),
        ).toolsByCategory
}

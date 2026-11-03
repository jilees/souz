package ru.souz.backend.llmgateway

import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.llms.codex.CodexEndpoints

/** Optional HTTP model access over the host's existing catalogue, credentials and provider transports. */
internal fun llmGatewayModule(config: LlmGatewayConfig, endpoints: CodexEndpoints, concurrency: Int): DI.Module =
    DI.Module("llmGateway") {
        bindSingleton {
            LlmGateway(config = config, models = instance(), settings = instance(), clients = instance(),
                oauth = instance(), endpoints = endpoints, concurrency = concurrency, metrics = instance())
        }
    }

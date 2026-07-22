package ru.souz.backend.salute.sandbox

/** Well-known [ru.souz.llms.ToolInvocationMeta.attributes] keys used by the Salute sandbox wiring. */
object SaluteToolAttributes {
    /** Set when a turn originates from the Salute device itself, so tool calls target that exact device. */
    const val DEVICE_ID = "saluteDeviceId"
}

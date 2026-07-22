package ru.souz.backend.salute

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import ru.souz.runtime.sandbox.SandboxCommandResult

class SaluteExecRequestRegistryTest {
    @Test
    fun `complete resolves the matching pending deferred`() = runTest {
        val registry = SaluteExecRequestRegistry()
        val deferred = registry.beginRequest(deviceId = "device-1", id = "req-1")

        registry.complete("req-1", SandboxCommandResult(exitCode = 0, stdout = "out", stderr = "", timedOut = false))

        assertEquals(SandboxCommandResult(exitCode = 0, stdout = "out", stderr = "", timedOut = false), deferred.await())
    }

    @Test
    fun `complete for an unknown id is a silent no-op`() {
        val registry = SaluteExecRequestRegistry()

        registry.complete("unknown", SandboxCommandResult(exitCode = 0, stdout = "", stderr = "", timedOut = false))
    }

    @Test
    fun `complete after discard is a silent no-op and does not resolve the deferred`() = runTest {
        val registry = SaluteExecRequestRegistry()
        val deferred = registry.beginRequest(deviceId = "device-1", id = "req-1")
        registry.discard("req-1")

        registry.complete("req-1", SandboxCommandResult(exitCode = 0, stdout = "late", stderr = "", timedOut = false))

        assertFalse(deferred.isCompleted)
    }

    @Test
    fun `failAllForDevice only fails entries for the matching device`() = runTest {
        val registry = SaluteExecRequestRegistry()
        val deviceOneDeferred = registry.beginRequest(deviceId = "device-1", id = "req-1")
        val deviceTwoDeferred = registry.beginRequest(deviceId = "device-2", id = "req-2")

        registry.failAllForDevice("device-1", "disconnected")

        assertFailsWith<SaluteDeviceDisconnectedException> { deviceOneDeferred.await() }
        assertFalse(deviceTwoDeferred.isCompleted)
    }
}

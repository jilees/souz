package ru.souz.agent.skills.bundle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SkillBundleParserTest {

    @Test
    fun `runsOnDevice defaults to false when absent`() {
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: paper
            description: summarizes papers
            ---
            body
            """.trimIndent()
        )

        assertEquals(false, manifest.runsOnDevice)
    }

    @Test
    fun `runsOnDevice parses true`() {
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: smart-light
            description: controls smart lights
            runsOnDevice: true
            ---
            body
            """.trimIndent()
        )

        assertEquals(true, manifest.runsOnDevice)
    }

    @Test
    fun `runsOnDevice parses false explicitly`() {
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: smart-light
            description: controls smart lights
            runsOnDevice: false
            ---
            body
            """.trimIndent()
        )

        assertEquals(false, manifest.runsOnDevice)
    }

    @Test
    fun `invalid runsOnDevice value throws`() {
        assertFailsWith<SkillBundleException> {
            SkillBundleParser.parseManifest(
                """
                ---
                name: smart-light
                description: controls smart lights
                runsOnDevice: yes
                ---
                body
                """.trimIndent()
            )
        }
    }
}

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

    @Test
    fun `oauthProvider and oauthScopes default to absent when not declared`() {
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: paper
            description: summarizes papers
            ---
            body
            """.trimIndent()
        )

        assertEquals(null, manifest.oauthProvider)
        assertEquals(emptyList(), manifest.oauthScopes)
    }

    @Test
    fun `oauthProvider and oauthScopes parse from frontmatter`() {
        val manifest = SkillBundleParser.parseManifest(
            """
            ---
            name: yandex-disk
            description: reads files from Yandex Disk
            oauthProvider: yandex
            oauthScopes:
              - cloud_api:disk.read
              - login:info
            ---
            body
            """.trimIndent()
        )

        assertEquals("yandex", manifest.oauthProvider)
        assertEquals(listOf("cloud_api:disk.read", "login:info"), manifest.oauthScopes)
    }
}

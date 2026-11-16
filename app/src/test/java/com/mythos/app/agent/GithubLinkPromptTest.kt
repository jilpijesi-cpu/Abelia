package com.mythos.app.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class GithubLinkPromptTest {
    private val prompt = Protocol.systemPrompt(ToolRegistry.default(false), false, notes = "")

    @Test
    fun promptTellsModelToWebFetchPastedGithubLinks() {
        val i = prompt.indexOf("GITHUB LINKS")
        assertTrue("aturan link GitHub harus ada", i >= 0)
        val rule = prompt.substring(i, prompt.indexOf('\n', i))
        listOf("WebFetch", "raw.githubusercontent.com", "Do NOT WebSearch", "Task", "same reply").forEach {
            assertTrue("hilang: $it", rule.contains(it))
        }
    }

    @Test
    fun importRepoDocPointsToWebFetchForOtherRepos() {
        assertTrue(GithubImportRepo().doc.contains("WebFetch"))
    }
}

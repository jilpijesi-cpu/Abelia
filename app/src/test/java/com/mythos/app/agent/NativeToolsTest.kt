package com.mythos.app.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeToolsTest {
    private fun props(doc: String): JSONObject = NativeTools.schemaFromDoc(doc).getJSONObject("properties")

    @Test
    fun schemaIsInferredFromDocExample() {
        val p = props("""WebSearch {"query": "...", "max_results": 6} — search the web""")
        assertEquals("string", p.getJSONObject("query").getString("type"))
        assertEquals("integer", p.getJSONObject("max_results").getString("type"))
        val g = props("""Grep {"pattern": "x", "-i": false, "-C": 0, "head_limit": 250} — regex""")
        assertEquals("boolean", g.getJSONObject("-i").getString("type"))
        assertEquals("integer", g.getJSONObject("head_limit").getString("type"))
    }

    @Test
    fun arraysAndAlternativeFormsAreCovered() {
        val c = props("""ComposeEmail {"to": ["<email>"], "subject": "s"} — draft""")
        assertEquals("array", c.getJSONObject("to").getString("type"))
        assertEquals("string", c.getJSONObject("to").getJSONObject("items").getString("type"))
        val r = props("""SetReminder {"kind": "alarm", "time": "<HH:mm>"} or {"kind": "calendar", "title": "t", "duration_minutes": 30} — set""")
        assertTrue(r.has("time"))
        assertTrue(r.has("title"))
        assertEquals("integer", r.getJSONObject("duration_minutes").getString("type"))
    }

    @Test
    fun nestedKeysAndValueAlternativesAreNotTopLevelProperties() {
        val p = props("""Todo {"todos": [{"content": "x", "status": "pending|done"}]} — list""")
        assertTrue(p.has("todos"))
        assertFalse(p.has("content"))
        val a = props("""Ledger {"action": "upsert_case"|"add_claim"|"summary", ...} — ledger""")
        assertEquals(1, a.length())
    }

    @Test
    fun openAiStreamingToolCallBecomesToolUseBlock() {
        val acc = NativeCallAcc()
        acc.openAiEvent("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"WebSearch","arguments":""}}]}}]}""")
        acc.openAiEvent("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":\"codex"}}]}}]}""")
        acc.openAiEvent("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":" terbaru\"}"}}]}}]}""")
        assertTrue(acc.any)
        val out = acc.render()
        assertTrue(out.startsWith(Protocol.OPEN))
        assertTrue(out.endsWith(Protocol.CLOSE))
        val json = JSONObject(out.removePrefix(Protocol.OPEN).removeSuffix(Protocol.CLOSE))
        assertEquals("WebSearch", json.getString("name"))
        assertEquals("codex terbaru", json.getJSONObject("input").getString("query"))
    }

    @Test
    fun anthropicStreamingToolUseBecomesToolUseBlock() {
        val acc = NativeCallAcc()
        acc.anthropicEvent("content_block_start", """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t1","name":"Read","input":{}}}""")
        acc.anthropicEvent("content_block_delta", """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"file_path\":"}}""")
        acc.anthropicEvent("content_block_delta", """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"a.kt\"}"}}""")
        val json = JSONObject(acc.render().removePrefix(Protocol.OPEN).removeSuffix(Protocol.CLOSE))
        assertEquals("Read", json.getString("name"))
        assertEquals("a.kt", json.getJSONObject("input").getString("file_path"))
    }

    @Test
    fun geminiFunctionCallBecomesToolUseBlock() {
        val acc = NativeCallAcc()
        acc.geminiEvent("""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"LS","args":{"path":"."}}}]}}]}""")
        val json = JSONObject(acc.render().removePrefix(Protocol.OPEN).removeSuffix(Protocol.CLOSE))
        assertEquals("LS", json.getString("name"))
        assertEquals(".", json.getJSONObject("input").getString("path"))
    }

    @Test
    fun noCallsRendersNothingAndBodiesCarryTools() {
        assertEquals("", NativeCallAcc().render())
        val specs = listOf(NativeToolSpec("LS", "doc", NativeTools.schemaFromDoc("""LS {"path": "."} — x""")))
        val cfg = ProviderConfig(ProviderKind.OPENAI_COMPATIBLE, "https://x", "k", "m")
        assertTrue(openAiBody(cfg, "sys", emptyList(), specs).has("tools"))
        assertFalse(openAiBody(cfg, "sys", emptyList()).has("tools"))
        assertTrue(anthropicBody(cfg, "sys", emptyList(), specs).has("tools"))
        assertTrue(geminiBody(cfg, "sys", emptyList(), specs).has("tools"))
    }

    @Test
    fun rejectionHeuristicIgnoresSizeAndAuthErrors() {
        assertTrue(NativeTools.looksLikeRejection(LlmException("HTTP 400: tools not supported", false, 400)))
        assertFalse(NativeTools.looksLikeRejection(LlmException("HTTP 413", false, 413)))
        assertFalse(NativeTools.looksLikeRejection(LlmException("HTTP 401", false, 401)))
    }
}

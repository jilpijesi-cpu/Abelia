package com.mythos.app.agent

import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Function calling asli (OpenAI-compatible, Anthropic, Gemini).
 *
 * Rancangan: alat tetap didefinisikan oleh [Tool.doc]. Skema parameter diturunkan otomatis dari contoh JSON di doc itu, lalu dikirim
 * ke penyedia lewat parameter `tools`. Panggilan yang dikembalikan penyedia diubah menjadi blok `<tool_use>{...}</tool_use>` yang
 * SAMA dengan protokol teks, sehingga seluruh loop agen, persetujuan, dan keamanan tidak berubah. Model yang tidak memakai function
 * calling tetap bisa menulis blok teks (kedua bentuk diterima). Bila penyedia menolak parameter `tools`, klien mengulang permintaan
 * tanpa `tools` dan mengingat penolakan itu.
 */
class NativeToolSpec(val name: String, val description: String, val schema: JSONObject)

/** Dibawa lewat coroutine context supaya tidak mengubah tanda tangan [LlmClient] maupun pembungkusnya (Retry, KeyPool, VisionGate). */
class NativeToolsContext(val specs: List<NativeToolSpec>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NativeToolsContext>
}

internal object NativeTools {
    /** Sakelar darurat. */
    @Volatile var enabled: Boolean = true

    private val rejected: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun key(cfg: ProviderConfig) = cfg.kind.name + "|" + cfg.baseUrl + "|" + cfg.model

    suspend fun specs(cfg: ProviderConfig): List<NativeToolSpec> {
        if (!enabled || cfg.baseUrl.startsWith("codex://") || key(cfg) in rejected) return emptyList()
        return currentCoroutineContext()[NativeToolsContext.Key]?.specs.orEmpty()
    }

    fun reject(cfg: ProviderConfig) {
        rejected.add(key(cfg))
    }

    /** Galat 4xx pada permintaan yang membawa `tools` dianggap penolakan parameter itu (bukan 413 ukuran, bukan 401/403/429). */
    fun looksLikeRejection(e: LlmException): Boolean {
        if (e.code == 413 || e.code == 401 || e.code == 403 || e.code == 429) return false
        if (e.code in setOf(400, 404, 405, 415, 422, 501)) return true
        val m = e.message.orEmpty().lowercase()
        return "tool" in m || "function" in m
    }

    fun specsFor(tools: List<Tool>): List<NativeToolSpec> =
        tools.map { NativeToolSpec(it.name, it.doc.trim().take(2000), schemaFromDoc(it.doc)) }

    const val SYSTEM_NOTE = "\n\n# Native tool calling\n" +
        "Most of the tools above are also registered as native functions. Call a registered tool through the function-calling interface: that is the preferred way, " +
        "and the app runs it exactly like the text blocks. A tool that is not registered natively is still called with a <tool_use> block. " +
        "Earlier calls in this conversation are shown as <tool_use> text only as a record; do not copy that format for a natively registered tool. " +
        "Never announce a call (\"saya cari...\", \"saya cek...\") without making it in the same reply."

    // ---------------------------------------------------------------------------------------------------------------
    // Skema dari doc
    // ---------------------------------------------------------------------------------------------------------------

    private val KEY_OK = Regex("-?[A-Za-z0-9_]{1,60}")

    private fun closingQuote(s: String, open: Int): Int {
        var i = open + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') { i += 2; continue }
            if (c == '"') return i
            i++
        }
        return s.length - 1
    }

    private fun skipWs(s: String, from: Int): Int {
        var k = from
        while (k < s.length && s[k].isWhitespace()) k++
        return k
    }

    private fun numberType(s: String, k: Int): JSONObject {
        var e = k
        var dot = false
        while (e < s.length && (s[e].isDigit() || s[e] == '-' || s[e] == '.' || s[e] == 'e' || s[e] == 'E' || s[e] == '+')) {
            if (s[e] == '.') dot = true
            e++
        }
        return JSONObject().put("type", if (dot) "number" else "integer")
    }

    private fun scalarOrNull(s: String, k: Int): JSONObject? {
        if (k >= s.length) return null
        val c = s[k]
        return when {
            c == '"' -> JSONObject().put("type", "string")
            c.isDigit() || c == '-' -> numberType(s, k)
            s.startsWith("true", k) || s.startsWith("false", k) -> JSONObject().put("type", "boolean")
            c == '{' -> JSONObject().put("type", "object")
            else -> null
        }
    }

    private fun valueType(s: String, k: Int): JSONObject {
        if (k >= s.length) return JSONObject().put("type", "string")
        if (s[k] == '[') {
            val items = scalarOrNull(s, skipWs(s, k + 1))
            val o = JSONObject().put("type", "array")
            if (items != null) o.put("items", items)
            return o
        }
        return scalarOrNull(s, k) ?: JSONObject().put("type", "string")
    }

    /**
     * Doc berbentuk `Nama {"arg": contoh, ...} - uraian`. Semua kunci tingkat-1 pada bagian sebelum " — " dipakai (juga untuk alat yang
     * punya beberapa bentuk, mis. `{...} or {...}`); tipe ditebak dari nilai contohnya. Tidak ada `required` dan tidak ada
     * additionalProperties=false: uraian lengkap tetap dikirim sebagai deskripsi, jadi model tahu aturannya.
     */
    fun schemaFromDoc(doc: String): JSONObject {
        val head = doc.substringBefore(" — ")
        val props = JSONObject()
        var depth = 0
        var i = 0
        while (i < head.length) {
            val c = head[i]
            if (c == '{' || c == '[') {
                depth++
                i++
            } else if (c == '}' || c == ']') {
                depth--
                i++
            } else if (c == '"') {
                val end = closingQuote(head, i)
                val str = if (end > i) head.substring(i + 1, end) else ""
                val j = skipWs(head, end + 1)
                if (depth == 1 && j < head.length && head[j] == ':' && KEY_OK.matches(str) && !props.has(str)) {
                    props.put(str, valueType(head, skipWs(head, j + 1)))
                }
                i = end + 1
            } else {
                i++
            }
        }
        return JSONObject().put("type", "object").put("properties", props)
    }

    /** Gemini memakai subset OpenAPI: tipe huruf besar, larik wajib punya items. */
    fun toGeminiSchema(node: JSONObject): JSONObject {
        val out = JSONObject()
        val type = node.optString("type", "string")
        out.put("type", type.uppercase())
        if (type == "object") {
            val props = node.optJSONObject("properties")
            if (props != null && props.length() > 0) {
                val np = JSONObject()
                val keys = props.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    np.put(k, toGeminiSchema(props.getJSONObject(k)))
                }
                out.put("properties", np)
            }
        }
        if (type == "array") {
            val items = node.optJSONObject("items")
            out.put("items", if (items != null) toGeminiSchema(items) else JSONObject().put("type", "STRING"))
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Isi permintaan
    // ---------------------------------------------------------------------------------------------------------------

    fun openAiTools(specs: List<NativeToolSpec>): JSONArray {
        val arr = JSONArray()
        specs.forEach {
            arr.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.schema)
                )
            )
        }
        return arr
    }

    fun anthropicTools(specs: List<NativeToolSpec>): JSONArray {
        val arr = JSONArray()
        specs.forEach {
            arr.put(JSONObject().put("name", it.name).put("description", it.description).put("input_schema", it.schema))
        }
        return arr
    }

    /** Gemini menolak OBJECT tanpa properti; alat dengan parameter seperti itu tetap dipanggil lewat blok teks. */
    private fun hasOpaqueObject(node: JSONObject, top: Boolean = true): Boolean {
        val type = node.optString("type", "string")
        if (type == "object") {
            val props = node.optJSONObject("properties")
            if (props == null || props.length() == 0) return !top
            val keys = props.keys()
            while (keys.hasNext()) if (hasOpaqueObject(props.getJSONObject(keys.next()), false)) return true
            return false
        }
        if (type == "array") {
            val items = node.optJSONObject("items") ?: return false
            return hasOpaqueObject(items, false)
        }
        return false
    }

    fun geminiTools(specs: List<NativeToolSpec>): JSONArray {
        val decls = JSONArray()
        specs.filter { !hasOpaqueObject(it.schema) }.forEach {
            val d = JSONObject().put("name", it.name).put("description", it.description)
            val props = it.schema.optJSONObject("properties")
            if (props != null && props.length() > 0) d.put("parameters", toGeminiSchema(it.schema))
            decls.put(d)
        }
        return if (decls.length() == 0) JSONArray() else JSONArray().put(JSONObject().put("functionDeclarations", decls))
    }
}

/** Mengumpulkan panggilan alat yang datang bertahap (streaming) atau utuh, lalu menyajikannya sebagai blok protokol teks. */
internal class NativeCallAcc {
    private class Slot {
        var name: String = ""
        val args = StringBuilder()
        var whole: JSONObject? = null
    }

    private val slots = TreeMap<Int, Slot>()
    private var seq = 0

    val any: Boolean get() = slots.values.any { it.name.isNotBlank() }

    private fun slot(index: Int): Slot = slots.getOrPut(index) { Slot() }

    /** Satu simpul `delta`/`message` OpenAI-compatible. */
    fun openAiNode(node: JSONObject?) {
        val calls = node?.optJSONArray("tool_calls") ?: return
        for (n in 0 until calls.length()) {
            val tc = calls.optJSONObject(n) ?: continue
            val fn = tc.optJSONObject("function") ?: continue
            val name = fn.optString("name", "")
            val idx = if (tc.has("index")) tc.optInt("index", 0) else if (name.isNotBlank()) 1000 + (seq++) else if (slots.isEmpty()) 1000 + (seq++) else slots.lastKey()
            val s = slot(idx)
            if (name.isNotBlank()) s.name = name
            val a = fn.opt("arguments")
            if (a is String) s.args.append(a) else if (a is JSONObject) s.whole = a
        }
    }

    /** Satu peristiwa SSE OpenAI-compatible (mentah). */
    fun openAiEvent(data: String) {
        val t = data.trim()
        if (t.isEmpty() || t == "[DONE]") return
        val o = try { JSONObject(t) } catch (e: JSONException) { return }
        val choice = o.optJSONArray("choices")?.optJSONObject(0) ?: return
        openAiNode(choice.optJSONObject("delta") ?: choice.optJSONObject("message"))
    }

    fun anthropicEvent(event: String?, data: String) {
        val t = data.trim()
        if (t.isEmpty()) return
        val o = try { JSONObject(t) } catch (e: JSONException) { return }
        when (o.optString("type", event ?: "")) {
            "content_block_start" -> {
                val b = o.optJSONObject("content_block") ?: return
                if (b.optString("type") == "tool_use") {
                    val s = slot(o.optInt("index", seq++))
                    s.name = b.optString("name", "")
                    b.optJSONObject("input")?.let { if (it.length() > 0) s.whole = it }
                }
            }
            "content_block_delta" -> {
                val d = o.optJSONObject("delta") ?: return
                if (d.optString("type") == "input_json_delta") {
                    val idx = o.optInt("index", -1)
                    if (idx >= 0 && slots.containsKey(idx)) slots[idx]!!.args.append(d.optString("partial_json", ""))
                }
            }
        }
    }

    fun anthropicMessage(json: JSONObject) {
        val blocks = json.optJSONArray("content") ?: return
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            if (b.optString("type") == "tool_use") {
                val s = slot(1000 + (seq++))
                s.name = b.optString("name", "")
                s.whole = b.optJSONObject("input") ?: JSONObject()
            }
        }
    }

    fun geminiChunk(o: JSONObject) {
        val parts = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            val fc = parts.optJSONObject(i)?.optJSONObject("functionCall") ?: continue
            val s = slot(1000 + (seq++))
            s.name = fc.optString("name", "")
            s.whole = fc.optJSONObject("args") ?: JSONObject()
        }
    }

    fun geminiEvent(data: String) {
        val t = data.trim()
        if (t.isEmpty()) return
        val o = try { JSONObject(t) } catch (e: JSONException) { return }
        geminiChunk(o)
    }

    /** Blok `<tool_use>` untuk setiap panggilan, dipisah baris baru; kosong bila tidak ada panggilan. */
    fun render(): String {
        val sb = StringBuilder()
        slots.values.forEach { s ->
            if (s.name.isBlank()) return@forEach
            val input = s.whole ?: run {
                val raw = s.args.toString().trim()
                if (raw.isEmpty()) JSONObject() else try { JSONObject(raw) } catch (e: JSONException) { JSONObject() }
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(Protocol.OPEN).append(JSONObject().put("name", s.name).put("input", input).toString()).append(Protocol.CLOSE)
        }
        return sb.toString()
    }
}

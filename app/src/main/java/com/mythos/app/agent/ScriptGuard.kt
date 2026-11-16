package com.mythos.app.agent

/**
 * Jaring pengaman bahasa: sebagian model menyelipkan huruf Mandarin/Jepang/Korea di tengah jawaban berbahasa Indonesia
 * (mis. "dan可解释."). Aturan "ONE LANGUAGE" di prompt adalah lapis pertama; ini lapis kedua untuk jawaban akhir.
 *
 * Aman: tidak menyentuh apa pun bila pengguna sendiri memakai huruf itu (mis. bertanya arti kata Mandarin), dan tidak
 * menyentuh blok kode berpagar maupun `kode satu baris`.
 */
internal object ScriptGuard {
    private val CJK = Regex("[\\u2E80-\\u2FDF\\u3000-\\u30FF\\u3100-\\u312F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uAC00-\\uD7AF\\uF900-\\uFAFF\\uFF00-\\uFFEF]+")
    private val INLINE_CODE = Regex("`[^`\\n]*`")

    fun hasCjk(text: String): Boolean = CJK.containsMatchIn(text)

    /** Buang huruf CJK dari [answer] kecuali [userText] sendiri memuatnya. */
    fun strip(answer: String, userText: String): String {
        if (!hasCjk(answer) || hasCjk(userText)) return answer
        val out = StringBuilder()
        var inFence = false
        val lines = answer.split('\n')
        for ((i, line) in lines.withIndex()) {
            val sep = if (i < lines.lastIndex) "\n" else ""
            if (line.trim().startsWith("```")) {
                inFence = !inFence
                out.append(line).append(sep)
            } else if (inFence) {
                out.append(line).append(sep)
            } else {
                out.append(stripLine(line)).append(sep)
            }
        }
        return out.toString()
    }

    private fun stripLine(line: String): String {
        if (!hasCjk(line)) return line
        val codes = INLINE_CODE.findAll(line).map { it.range }.toList()
        val sb = StringBuilder()
        var last = 0
        for (r in codes) {
            sb.append(clean(line.substring(last, r.first)))
            sb.append(line.substring(r.first, r.last + 1))
            last = r.last + 1
        }
        sb.append(clean(line.substring(last)))
        return sb.toString()
    }

    private fun clean(part: String): String =
        part.replace(CJK, "").replace(Regex("[ \\t]{2,}"), " ").replace(Regex("[ \\t]+([.,;:!?)])"), "$1")
}

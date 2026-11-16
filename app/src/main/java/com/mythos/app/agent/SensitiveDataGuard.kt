package com.mythos.app.agent

/**
 * Penjaga keamanan data sensitif dan anti-prompt-injection untuk konten dari luar (email, web, dokumen lampiran, log).
 *
 * 1. Menyamarkan kode OTP, PIN, CVV/CVC, nomor kartu pembayaran 16 digit (valid Luhn), serta kata sandi/token Bearer
 *    agar tidak bocor ke konteks model LLM, log audit, atau keluar melalui alat.
 * 2. Menetralkan tag protokol internal (`</tool_result>`, `<tool_use>`, `</tool_use>`, `<system-reminder>`, `</system-reminder>`)
 *    di dalam data eksternal supaya halaman web, dokumen, atau email tidak dapat memalsukan instruksi sistem atau memutus blok hasil alat.
 */
object SensitiveDataGuard {

    // Pola OTP / Kode Verifikasi: kata kunci diikuti 4-8 angka dalam jarak dekat.
    private val OTP_CONTEXT_RE = Regex(
        """(?i)\b(otp|kode\s+verifikasi|verification\s+code|kode\s+rahasia|security\s+code|passcode|kode\s+masuk|one[-\s]?time\s+password|jangan\s+berikan\s+kode)\b([^\n\r]{0,40}?)\b(\d{4,8})\b"""
    )

    // Pola terbalik: angka 4-8 digit diikuti "adalah kode OTP / verifikasi Anda"
    private val OTP_REVERSE_RE = Regex(
        """\b(\d{4,8})\b([^\n\r]{0,30}?)\b(?i)(adalah\s+kode\s+(?:otp|verifikasi|rahasia)|is\s+your\s+(?:otp|verification|security)\s+code)\b"""
    )

    // Pola CVV / CVC / PIN eksplisit
    private val CVV_PIN_RE = Regex(
        """(?i)\b(cvv2?|cvc2?|pin(?:\s+(?:atm|kartu|transaksi|m-?banking))?)\s*(?:[:=]|adalah|is)\s*(\d{3,6})\b"""
    )

    // Pola kata sandi / Bearer token eksplisit
    private val PASSWORD_BEARER_RE = Regex(
        """(?i)\b(password|kata\s+sandi|passphrase)\s*[:=]\s*([^\s"',;]{4,64})"""
    )

    private val BEARER_TOKEN_RE = Regex(
        """(?i)(Authorization\s*:\s*Bearer\s+)([A-Za-z0-9._\-]{16,})"""
    )

    // Pola token OAuth 2.0 Google (ya29., 1//), GitHub PAT (ghp_, github_pat_), dan kunci API AI (sk-, AIza, hf_, gsk_, tvly-)
    private val API_TOKEN_SECRET_RE = Regex(
        """\b(ya29\.[A-Za-z0-9._\-]{12,}|1//[A-Za-z0-9._\-]{12,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9._\-]{16,}|AIza[A-Za-z0-9._\-]{30,}|hf_[A-Za-z0-9]{16,}|gsk_[A-Za-z0-9]{16,}|tvly-[A-Za-z0-9._\-]{16,}|GOCSPX-[A-Za-z0-9._\-]{16,})\b"""
    )

    // Pola field JSON/query access_token, refresh_token, client_secret, api_key
    private val JSON_TOKEN_RE = Regex(
        """(?i)("(?:access_token|refresh_token|client_secret|api_key|id_token)"\s*:\s*")([^"]{8,})(")"""
    )

    // Kandidat 16 digit kartu pembayaran (dengan spasi atau strip opsional per 4 digit)
    private val CARD_CANDIDATE_RE = Regex(
        """(?<!\d)(\d{4}[ -]\d{4}[ -]\d{4}[ -]\d{4})(?!\d)"""
    )

    // 16 digit berurutan (tanpa pemisah): bisa kartu pembayaran (lulus Luhn) atau NIK (struktur wilayah + tanggal lahir).
    private val DIGITS16_RE = Regex("""(?<![\d])(\d{16})(?![\d])""")

    // NIK dengan pemisah spasi/titik/strip (4-4-4-4 atau 6-6-4) umum di dokumen kasus.
    private val NIK_SEPARATED_RE = Regex("""(?<![\d])(\d{4}[ .\-]\d{4}[ .\-]\d{4}[ .\-]\d{4})(?![\d])""")

    // Nomor rekening / virtual account: kata kunci diikuti 8-18 digit (boleh berpemisah spasi/strip).
    private val ACCOUNT_RE = Regex(
        """(?i)\b(no(?:mor|\.)?\s*rek(?:ening)?|rekening(?:\s+tujuan)?|account\s*(?:number|no\.?)|virtual\s*account|no\.?\s*va|nomor\s*va)\b([^\n\r\d]{0,20}?)(\d(?:[ -]?\d){7,17})(?![\d])"""
    )

    /** NIK sah secara struktur: provinsi 11-94, bulan 01-12, tanggal 01-31 (perempuan +40 = 41-71). Menghindari nomor referensi biasa. */
    internal fun looksLikeNik(d: String): Boolean {
        if (d.length != 16 || !d.all { it.isDigit() }) return false
        val prov = d.substring(0, 2).toInt()
        val day = d.substring(6, 8).toInt()
        val month = d.substring(8, 10).toInt()
        return prov in 11..94 && month in 1..12 && (day in 1..31 || day in 41..71)
    }

    private fun maskNik(digits: String) = "NIK-${digits.take(4)}********${digits.takeLast(4)}"

    /**
     * Luhn check untuk membedakan nomor kartu kredit/debit 16 digit berformat "xxxx xxxx xxxx xxxx"
     * dari nomor referensi biasa.
     */
    internal fun passesLuhn(digitsOnly: String): Boolean {
        if (digitsOnly.length != 16 || !digitsOnly.all { it.isDigit() }) return false
        // Hindari positif palsu seperti 0000... atau angka berulang sederhana
        if (digitsOnly.toSet().size <= 2) return false
        var sum = 0
        for (i in digitsOnly.indices) {
            var d = digitsOnly[15 - i] - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }

    /** Samarkan OTP, CVV, PIN, kata sandi, Bearer token, OAuth token, API key, dan nomor kartu pembayaran. */
    fun redactSensitiveCredentials(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        out = OTP_CONTEXT_RE.replace(out) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}[OTP-DISEMBUNYIKAN]"
        }
        out = OTP_REVERSE_RE.replace(out) { m ->
            "[OTP-DISEMBUNYIKAN]${m.groupValues[2]}${m.groupValues[3]}"
        }
        out = CVV_PIN_RE.replace(out) { m ->
            "${m.groupValues[1]}: [RAHASIA-DISEMBUNYIKAN]"
        }
        out = PASSWORD_BEARER_RE.replace(out) { m ->
            "${m.groupValues[1]}: [SANDI-DISEMBUNYIKAN]"
        }
        out = BEARER_TOKEN_RE.replace(out) { m ->
            "${m.groupValues[1]}[TOKEN-DISEMBUNYIKAN]"
        }
        out = JSON_TOKEN_RE.replace(out) { m ->
            "${m.groupValues[1]}[TOKEN-DISEMBUNYIKAN]${m.groupValues[3]}"
        }
        out = API_TOKEN_SECRET_RE.replace(out) {
            "[TOKEN-DISEMBUNYIKAN]"
        }
        out = ACCOUNT_RE.replace(out) { m ->
            val digits = m.groupValues[3].filter { it.isDigit() }
            "${m.groupValues[1]}${m.groupValues[2]}[REKENING-${digits.takeLast(4)}-DISEMBUNYIKAN]"
        }
        out = DIGITS16_RE.replace(out) { m ->
            val d = m.groupValues[1]
            when {
                passesLuhn(d) -> "${d.take(4)}-****-****-${d.takeLast(4)}"
                looksLikeNik(d) -> maskNik(d)
                else -> d
            }
        }
        out = NIK_SEPARATED_RE.replace(out) { m ->
            val d = m.groupValues[1].filter { it.isDigit() }
            // Kartu berpemisah yang lulus Luhn ditangani aturan kartu di bawah, bukan sebagai NIK.
            if (!passesLuhn(d) && looksLikeNik(d)) maskNik(d) else m.value
        }
        out = CARD_CANDIDATE_RE.replace(out) { m ->
            val raw = m.groupValues[1]
            val digits = raw.filter { it.isDigit() }
            if (passesLuhn(digits)) {
                "${digits.take(4)}-****-****-${digits.takeLast(4)}"
            } else {
                raw
            }
        }
        return out
    }

    // Tag/penanda yang oleh Protocol.kt diubah menjadi panggilan alat atau dipakai untuk membatasi blok hasil. Toleran terhadap spasi,
    // atribut (`<tool_use name=..>`), huruf besar/kecil, dan bentuk `<function=Nama>`.
    private val PROTOCOL_TAG_RE = Regex(
        """<(\s*/?\s*)(tool_results?|tool_uses?|tool_calls?|toolcalls?|[A-Za-z0-9_]+_(?:function_calls?|tool_calls?|toolcalls?)|function_calls?|function_results?|function|invokename|invoke|parameter|arg_key|arg_value|system-reminder|system_reminder|antml:[A-Za-z_]+)(?=[\s>/=:]|$)""",
        RegexOption.IGNORE_CASE
    )

    // Token khusus gaya model: <|tool_call_begin|>, <|/tool_name|>, </|file_path|>, <|im_start|>, dst.
    private val SPECIAL_TOKEN_RE = Regex("""<(\s*/?\s*)\|(\s*/?\s*)([A-Za-z0-9_]+)\s*\|>""")

    /**
     * Netralkan semua tag protokol internal di dalam teks tak tepercaya agar tidak dapat menutup
     * `<tool_result>` atau `<system-reminder>` lebih awal maupun menyuntikkan panggilan alat palsu
     * (`<tool_use>`, `<tool_call>`, `<invoke>`, `<function=...>`, `</function>`, `<arg_key>`, `<|tool_call_begin|>`), termasuk dengan spasi/atribut/huruf besar.
     */
    fun escapeProtocolTags(text: String): String {
        if (!text.contains('<')) return text
        var out = PROTOCOL_TAG_RE.replace(text) { m ->
            val lead = m.groupValues[1]
            val tag = m.groupValues[2]
            val nextIdx = m.range.last + 1
            // `<function>` biasa dalam prosa kode dibiarkan; yang dinetralkan adalah `<function=Nama>` dan penutup `</function>`.
            if (tag.equals("function", ignoreCase = true) && !lead.contains('/') && (nextIdx >= text.length || text[nextIdx] != '=')) {
                m.value
            } else if (lead.contains('/')) {
                "<\\/" + tag
            } else {
                "<\\" + tag
            }
        }
        out = SPECIAL_TOKEN_RE.replace(out) { m ->
            val hasSlash = m.groupValues[1].contains('/') || m.groupValues[2].contains('/')
            if (hasSlash) "<\\|/" + m.groupValues[3] + "|>" else "<\\|" + m.groupValues[3] + "|>"
        }
        return out
    }

    /**
     * Bungkus dan bersihkan konten yang berasal dari sumber eksternal (Web, Email, Issue GitHub, Dokumen)
     * agar model tidak memperlakukan isi di dalamnya sebagai perintah sistem.
     */
    fun sanitizeExternalContent(text: String): String {
        return redactSensitiveCredentials(escapeProtocolTags(text))
    }
}

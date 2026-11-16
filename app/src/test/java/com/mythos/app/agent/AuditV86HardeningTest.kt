package com.mythos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Lanjutan audit v85: regex kunci privat linear, berkas rahasia tidak dikirim, digest mencakup berkas build sensitif. */
class AuditV86HardeningTest {

    private val oldPem = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----")

    // ---------- pemindai kunci privat ----------

    @Test(timeout = 5000)
    fun manyBeginMarkersWithoutEndStayLinear() {
        // Regex lama: 437 KB = 28 detik. Pemindai linear harus selesai jauh di bawah batas.
        val s = "-----BEGIN PRIVATE KEY-----\n".repeat(20_000)
        val (out, n) = redactPrivateKeyBlocks(s)
        assertEquals(0, n)
        assertEquals(s, out)
    }

    @Test(timeout = 5000)
    fun manyInvalidEndMarkersStayLinear() {
        val s = "-----BEGIN PRIVATE KEY-----\n" + "-----END X-----\n".repeat(100_000)
        assertEquals(0, redactPrivateKeyBlocks(s).second)
    }

    @Test
    fun privateKeyBlocksAreRedactedAndSurroundingTextKept() {
        val pem1 = "-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----"
        val pem2 = "-----BEGIN PRIVATE KEY-----\ndef\n-----END PRIVATE KEY-----"
        val (out, n) = redactPrivateKeyBlocks("a $pem1 b\n$pem2\nc")
        assertEquals(2, n)
        assertEquals("a REDACTED b\nREDACTED\nc", out)
    }

    @Test
    fun publicKeysAndUnterminatedBlocksAreLeftAlone() {
        val pub = "-----BEGIN PUBLIC KEY-----\nabc\n-----END PUBLIC KEY-----"
        assertEquals(0, redactPrivateKeyBlocks(pub).second)
        val open = "-----BEGIN PRIVATE KEY-----\nabc tanpa penutup"
        assertEquals(open, redactPrivateKeyBlocks(open).first)
    }

    @Test
    fun linearScannerMatchesOldRegexOnEdgeCases() {
        val cases = listOf(
            "-----BEGIN PRIVATE KEY-----a-----END RSA PRIVATE KEY-----",
            "-----BEGIN PRIVATE KEY-----x-----BEGIN PRIVATE KEY-----y-----END PRIVATE KEY-----z",
            "-----BEGIN EC PRIVATE KEY-----\n-----END PUBLIC KEY-----\n-----END EC PRIVATE KEY-----",
            "-----BEGIN private key-----x-----END private key-----",
            "x -----BEGIN  PRIVATE KEY-----1-----END  PRIVATE KEY----- y",
            "-----BEGIN PRIVATE KEY----x-----END PRIVATE KEY-----",
        )
        for (c in cases) {
            val expected = oldPem.replace(c, "REDACTED")
            assertEquals("kasus: $c", expected, redactPrivateKeyBlocks(c).first)
        }
    }

    @Test
    fun redactSecretsStillCatchesPemAndApiKeys() {
        val (out, n) = redactSecrets("k=ghp_" + "a".repeat(36) + "\n-----BEGIN PRIVATE KEY-----\nzzz\n-----END PRIVATE KEY-----")
        assertEquals(2, n)
        assertFalse(out.contains("zzz"))
    }

    // ---------- berkas rahasia ----------

    @Test
    fun secretFilesAreRecognisedByName() {
        listOf(
            "app/release.jks", "keys/my.keystore", "cert.p12", "a/b/server.pem", ".env", "app/.env.local",
            "local.properties", "keystore.properties", ".npmrc", "id_rsa", "config/service-account-prod.json",
        ).forEach { assertTrue("harus dianggap rahasia: $it", isSecretFile(it)) }
        listOf(
            "app/build.gradle.kts", "gradle.properties", "README.md", ".env.example", "google-services.json",
            "src/main/res/raw/keep.xml", "docs/keystore-howto.md",
        ).forEach { assertFalse("bukan rahasia: $it", isSecretFile(it)) }
    }

    @Test
    fun credentialLinesInPropertiesAreRedacted() {
        val src = "android.useAndroidX=true\nstorePassword=hunter2\nkeyPassword = abc123\r\nmyToken: xyz\n# password=komentar\nsigning.secret=\${env}\nemptyPassword=\norg.gradle.jvmargs=-Xmx2g\n"
        val (out, n) = redactCredentialLines(src)
        assertEquals(3, n)
        assertTrue(out.contains("android.useAndroidX=true"))
        assertTrue(out.contains("storePassword=REDACTED"))
        assertTrue(out.contains("keyPassword =REDACTED\r\n"))
        assertTrue(out.contains("myToken:REDACTED"))
        assertTrue(out.contains("# password=komentar"))
        assertTrue(out.contains("signing.secret=\${env}"))
        assertTrue(out.contains("emptyPassword=\n"))
        assertTrue(out.contains("org.gradle.jvmargs=-Xmx2g"))
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("abc123"))
        assertTrue(out.endsWith("\n"))
    }

    @Test(timeout = 5000)
    fun credentialLineScannerHandlesHugeLineQuickly() {
        val (out, n) = redactCredentialLines("a".repeat(5_000_000))
        assertEquals(0, n)
        assertEquals(5_000_000, out.length)
    }

    // ---------- digest persetujuan GithubBuild ----------

    private fun project(): File {
        val d = Files.createTempDirectory("audit86").toFile()
        File(d, ".github/workflows").mkdirs()
        File(d, ".github/workflows/build.yml").writeText("on: push")
        return d
    }

    @Test
    fun digestChangesWhenBuildSrcPackageJsonOrLocalJsActionChange() {
        val d = project()
        File(d, "buildSrc/src/main/kotlin").mkdirs()
        val kt = File(d, "buildSrc/src/main/kotlin/Deps.kt").apply { writeText("object Deps") }
        val pj = File(d, "package.json").apply { writeText("{}") }
        File(d, ".github/actions/x").mkdirs()
        val js = File(d, ".github/actions/x/index.js").apply { writeText("console.log(1)") }
        val base = workflowDigest(d)
        assertTrue(base.contains("buildSrc/src/main/kotlin/Deps.kt"))
        assertTrue(base.contains("package.json"))
        assertTrue(base.contains(".github/actions/x/index.js"))
        for (f in listOf(kt, pj, js)) {
            val before = workflowDigest(d)
            f.writeText(f.readText() + "\n// diubah")
            assertNotEquals("perubahan ${f.name} harus mengubah digest", before, workflowDigest(d))
        }
    }

    @Test
    fun testSourcesAffectDigestOnlyWhenStrict() {
        val d = project()
        File(d, "app/src/test/java").mkdirs()
        val t = File(d, "app/src/test/java/FooTest.kt").apply { writeText("class FooTest") }
        val loose = workflowDigest(d)
        val strict = workflowDigest(d, strict = true)
        assertFalse(loose.contains("FooTest.kt"))
        assertTrue(strict.contains("app/src/test/java/FooTest.kt"))
        t.writeText("class FooTest { /* diubah */ }")
        assertEquals(loose, workflowDigest(d))
        assertNotEquals(strict, workflowDigest(d, strict = true))
    }

    @Test
    fun digestStaysEmptyWithoutWorkflow() {
        val d = Files.createTempDirectory("audit86empty").toFile()
        File(d, "package.json").writeText("{}")
        assertEquals("", workflowDigest(d))
        assertEquals("", workflowDigest(d, strict = true))
    }

    @Test(timeout = 10_000)
    fun officeXmlAndSearchHtmlScannersStayLinearOnUnclosedTags() {
        val badXml = "<row r=\"1\"><c r=\"A1\"><v>1</v></c></row>" + "<row r=\"2\">".repeat(50_000)
        assertEquals(1, AttachmentText.findXmlElements(badXml, "row").size)
        val badLite = "<a class='result-link' href='https://example.org'>Judul</a>" + "<td class='result-snippet'>x".repeat(50_000)
        assertEquals(1, parseLiteResults(badLite, 5).size)
        val badBing = "<li class='b_algo'><a href='https://example.org'>Judul</a><p>Ringkasan</p></li>" + "<li class='b_algo'>x".repeat(50_000)
        assertEquals(1, parseBingResults(badBing, 5).size)
    }

    @Test
    fun boundedReaderRejectsOversizedStreamAndOpenConnDisablesRedirects() {
        val conn = openConn("https://example.com/v1")
        assertFalse(conn.instanceFollowRedirects)
        assertEquals("halo", readBoundedText(java.io.StringReader("halo"), 10))
        val ex = runCatching { readBoundedText(java.io.StringReader("x".repeat(100)), 20, failOnOverflow = true) }.exceptionOrNull()
        assertTrue(ex is LlmException)
        assertEquals(20, readBoundedText(java.io.StringReader("x".repeat(100)), 20, failOnOverflow = false).length)
    }

    @Test
    fun parsePageRangesHandlesOverflowNumbersWithoutException() {
        assertEquals(null, parsePageRanges("1-99999999999999999999999", 10))
        assertEquals(null, parsePageRanges("99999999999999999999999-2", 10))
    }
}

package com.mythos.app.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CodexPhaseTest {
    @Test fun penandaFaseMasukRingkasanLatensi() {
        LatencyTrace.begin()
        val st = CodexStreamState { }
        st.accept("""{"n":1,"type":"phase","name":"spawn","ms":900}""")
        st.accept("""{"n":2,"type":"phase","name":"text","ms":17100}""")
        st.accept("""{"n":3,"type":"phase","name":"text","ms":99999}""") // kemunculan kedua diabaikan
        val s = LatencyTrace.finish()
        assertTrue(s, s.contains("codex spawn 0,9 dtk text 17,1 dtk"))
    }

    @Test fun runnerMemasangPenandaFase() {
        val js = File("src/main/assets/codex_app_server.js").readText()
        for (name in listOf("spawn", "init", "thread", "turn", "reasoning", "text")) {
            assertTrue("mark('$name')", js.contains("mark('$name')"))
        }
    }

    @Test fun pollingBerkasJarangSaatSoketSehat() {
        val kt = File("src/main/java/com/mythos/app/agent/TermuxLauncher.kt").readText()
        assertTrue(kt.contains("THROTTLED_MS = 4000L"))
    }
}

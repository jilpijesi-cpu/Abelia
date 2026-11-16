package com.mythos.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** patch18: dari rekaman layar, setelah pesan dikirim layar melompat ke atas sendiri; penyematan di atas dimatikan secara default. */
class NoJumpOnSendTest {
    private val chat get() = File("src/main/java/com/mythos/app/ui/ChatScreen.kt").readText()

    @Test fun penyematanDiAtasMatiSecaraDefault() {
        assertFalse(PIN_USER_MESSAGE_ON_SEND)
    }

    @Test fun semuaJalurPenyematanDijagaOlehSaklar() {
        assertTrue(chat.contains("PIN_USER_MESSAGE_ON_SEND && ((running"))
        assertTrue(chat.contains("if (PIN_USER_MESSAGE_ON_SEND && lastUserId != null"))
    }
}

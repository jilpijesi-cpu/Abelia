package com.mythos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalPaceTest {
    @Test fun gumpalanJarangDisebarSepanjangJeda() {
        val p = ArrivalPace()
        p.onLength(300, 0); p.onLength(600, 5_000); p.onLength(900, 10_000)
        // 300 huruf tiap 5 dtk -> ideal 60/dtk x1,25 = 75/dtk, jauh di bawah laju tetap 130/dtk
        assertEquals(75f, p.speed(100), 0.5f)
        assertTrue(p.speed(100) < revealSpeed(100))
    }

    @Test fun aliranCepatTetapMemakaiLajuPenuh() {
        val p = ArrivalPace()
        p.onLength(50, 0); p.onLength(100, 100); p.onLength(150, 200)
        assertEquals(revealSpeed(40), p.speed(40), 0.01f)
    }

    @Test fun antreanBesarSelaluDikejarDenganLajuPenuh() {
        val p = ArrivalPace()
        p.onLength(300, 0); p.onLength(600, 5_000); p.onLength(900, 10_000)
        assertEquals(revealSpeed(700), p.speed(700), 0.01f)
    }

    @Test fun belumCukupContohMemakaiLajuBiasa() {
        val p = ArrivalPace()
        assertEquals(revealSpeed(10), p.speed(10), 0.01f)
        p.onLength(300, 0); p.onLength(600, 5_000)
        assertEquals(revealSpeed(10), p.speed(10), 0.01f)
    }

    @Test fun lajuTidakPernahTurunDiBawahBatas() {
        val p = ArrivalPace()
        p.onLength(10, 0); p.onLength(20, 8_000); p.onLength(30, 16_000)
        assertEquals(MIN_PACE_CPS, p.speed(5), 0.01f)
    }

    @Test fun teksMenyusutTidakMerusakIrama() {
        val p = ArrivalPace()
        p.onLength(300, 0); p.onLength(280, 1_000); p.onLength(600, 5_000); p.onLength(900, 10_000)
        assertTrue(p.speed(50) >= MIN_PACE_CPS && p.speed(50) <= revealSpeed(50))
    }
}

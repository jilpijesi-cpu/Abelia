package com.mythos.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityInsetTest {
    @Test fun barisLangkahMenjorokKeDalamSecukupnyaDariMarginTeks() {
        assertTrue(ACTIVITY_INSET.value in 8f..16f)
    }
}

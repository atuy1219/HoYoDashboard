package com.atuy.hoyodashboard.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressTest {
    @Test fun fractionHandlesUnknownMaximum() {
        assertEquals(0f, Progress(10, 0).fraction, 0.00001f)
    }
    @Test fun fractionClampsOverflow() {
        assertEquals(1f, Progress(300, 200).fraction, 0.00001f)
    }
}

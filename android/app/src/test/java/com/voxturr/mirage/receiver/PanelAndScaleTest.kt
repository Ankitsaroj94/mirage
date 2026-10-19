package com.voxturr.mirage.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelAndScaleTest {

    @Test fun orientsTabS9FePanelRegardlessOfNaturalOrientation() {
        assertEquals(2304 to 1440, Panel.oriented(1440, 2304, landscape = true))
        assertEquals(2304 to 1440, Panel.oriented(2304, 1440, landscape = true))
        assertEquals(1440 to 2304, Panel.oriented(2304, 1440, landscape = false))
    }

    @Test fun orientedSizesAreEven() {
        assertEquals(2560 to 1600, Panel.oriented(1601, 2561, landscape = true))
    }

    @Test fun looksLikeMatchesSpecTable() {
        // spec §5: Tab S9 FE 2304×1440 → crisp 1152×720 pt.
        assertEquals(1152 to 720, ScalePreference.CRISP.looksLike(2304, 1440))
        assertEquals(978 to 612, ScalePreference.LARGER.looksLike(2304, 1440))
        assertEquals(1324 to 828, ScalePreference.MORE.looksLike(2304, 1440))
        assertEquals(1498 to 936, ScalePreference.MOST.looksLike(2304, 1440))
        assertEquals(720 to 1152, ScalePreference.CRISP.looksLike(1440, 2304))
    }

    @Test fun wireNames() {
        assertEquals(ScalePreference.MORE, ScalePreference.fromWire("more"))
        assertEquals(ScalePreference.CRISP, ScalePreference.fromWire(null))
        assertEquals(ScalePreference.CRISP, ScalePreference.fromWire("bogus"))
        assertEquals(listOf("crisp", "larger", "more", "most"), ScalePreference.entries.map { it.wire })
    }
}

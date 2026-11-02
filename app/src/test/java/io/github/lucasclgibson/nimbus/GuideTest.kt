package io.github.lucasclgibson.nimbus

import io.github.lucasclgibson.nimbus.data.db.ProgrammeLite
import io.github.lucasclgibson.nimbus.player.programmeAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Catch-up follows the channel's guide as playback and scrubbing move between programmes. */
class GuideTest {
    private fun prog(id: Long, start: Long, end: Long) = ProgrammeLite(id, "e", start, end, "P$id")

    // 18:00–18:30, 18:30–19:00, (gap), 19:15–20:00
    private val guide = listOf(prog(1, 1_800, 1_830), prog(2, 1_830, 1_900), prog(3, 1_915, 2_000))

    @Test
    fun findsTheProgrammeAtAnyMoment() {
        assertEquals(1L, guide.programmeAt(1_800)?.id)
        assertEquals(1L, guide.programmeAt(1_829)?.id)
        assertEquals(2L, guide.programmeAt(1_830)?.id) // a programme's start belongs to it, its end to the next
        assertEquals(3L, guide.programmeAt(1_999)?.id)
    }

    @Test
    fun gapsAndEdgesHaveNoProgramme() {
        assertNull(guide.programmeAt(1_905))
        assertNull(guide.programmeAt(1_799))
        assertNull(guide.programmeAt(2_000))
        assertNull(emptyList<ProgrammeLite>().programmeAt(1_800))
    }
}

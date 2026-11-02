package io.github.lucasclgibson.nimbus

import io.github.lucasclgibson.nimbus.core.cleanTitle
import io.github.lucasclgibson.nimbus.core.normaliseChannelName
import io.github.lucasclgibson.nimbus.data.source.Catchup
import io.github.lucasclgibson.nimbus.data.source.M3uEntry
import io.github.lucasclgibson.nimbus.data.source.M3uParser
import io.github.lucasclgibson.nimbus.data.source.XmltvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class ParsersTest {

    private fun parse(text: String): Pair<Map<String, String>, List<M3uEntry>> {
        var header: Map<String, String> = emptyMap()
        val entries = ArrayList<M3uEntry>()
        M3uParser.parse(text.byteInputStream(), { header = it }) { entries += it }
        return header to entries
    }

    @Test fun m3uAttributesAndCommasInNames() {
        val (header, entries) = parse(
            """
            ﻿#EXTM3U url-tvg="http://epg.example/guide.xml.gz" catchup="shift" catchup-days="3"
            #EXTINF:-1 tvg-id="BBCOne.uk" tvg-name="BBC One" tvg-logo="http://l/bbc1.png" group-title="UK, Entertainment" tvg-chno="101",BBC One, HD
            #EXTVLCOPT:http-user-agent=MyAgent/1.0
            #EXTVLCOPT:http-referrer=http://ref.example/
            http://host/live/u/p/1.ts
            #EXTINF:0 tvg-id='single' group-title=Unquoted,Second Channel
            #EXTGRP:Ignored because group-title wins
            http://host/live/u/p/2.m3u8|User-Agent=Kodi%2F21&Referer=http%3A%2F%2Fx
            """.trimIndent()
        )
        assertEquals("http://epg.example/guide.xml.gz", header["url-tvg"])
        assertEquals(2, entries.size)
        val a = entries[0]
        assertEquals("BBC One, HD", a.name)
        assertEquals("UK, Entertainment", a.group)
        assertEquals("BBCOne.uk", a.attr("tvg-id"))
        assertEquals("101", a.attr("tvg-chno"))
        assertEquals("MyAgent/1.0", a.userAgent)
        assertEquals("http://ref.example/", a.referrer)
        val b = entries[1]
        assertEquals("Second Channel", b.name)
        assertEquals("single", b.attr("tvg-id"))
        assertEquals("Unquoted", b.group)
        assertEquals("http://host/live/u/p/2.m3u8", b.url)
        assertEquals("Kodi/21", b.userAgent)
        assertEquals("http://x", b.referrer)
    }

    @Test fun xmltvTimes() {
        // 2024-01-01 12:00:00 UTC
        assertEquals(1704110400000L, XmltvParser.parseTime("20240101120000 +0000"))
        assertEquals(1704110400000L, XmltvParser.parseTime("20240101130000 +0100"))
        assertEquals(1704110400000L, XmltvParser.parseTime("20240101070000 -0500"))
        assertEquals(1704110400000L, XmltvParser.parseTime("202401011200"))
        assertEquals(1704110400000L, XmltvParser.parseTime("20240101120000"))
        assertEquals(0L, XmltvParser.parseTime("garbage"))
        assertEquals(0L, XmltvParser.parseTime(null))
    }

    private val start = 1704110400000L // 2024-01-01 12:00 UTC
    private val end = start + 3_600_000L

    @Test fun catchupTemplates() {
        val utc = ZoneOffset.UTC
        assertEquals(
            "http://h/ch/index.m3u8?utc=1704110400&lutc=",
            Catchup.build("shift", "", "http://h/ch/index.m3u8", start, end, utc)!!.substringBeforeLast('=') + "=",
        )
        assertEquals(
            "http://h/ch/video-1704110400-3600.m3u8?token=a",
            Catchup.build("flussonic", "", "http://h/ch/video.m3u8?token=a", start, end, utc),
        )
        assertEquals(
            "http://h/ch/timeshift_abs-1704110400.ts?token=a",
            Catchup.build("flussonic", "", "http://h/ch/mpegts?token=a", start, end, utc),
        )
        assertEquals(
            "http://h:80/timeshift/user/pass/60/2024-01-01:12-00/123.ts",
            Catchup.build("xc", "", "http://h:80/live/user/pass/123.ts", start, end, utc),
        )
        assertEquals(
            "http://arch/1704110400/60/2024/01/01/12",
            Catchup.build("default", "http://arch/{utc}/{duration:60}/{Y}/{m}/{d}/{H}", "http://live", start, end, utc),
        )
        assertEquals(
            "http://live/stream.m3u8?start=2024-01-01:12-00&end=1704114000",
            Catchup.build("append", "?start=\${start:Y-m-d:H-M}&end=\${end}", "http://live/stream.m3u8", start, end, utc),
        )
        // London is UTC+0 in January, UTC+1 in summer: templates follow the zone given.
        assertTrue(Catchup.build("xc", "", "http://h/u/p/9", start, end, ZoneId.of("Europe/London"))!!.contains("2024-01-01:12-00"))
    }

    @Test fun errorsNeverShowCredentials() {
        val msg = io.github.lucasclgibson.nimbus.core.friendlyError(java.io.IOException("failed on http://host.example:8080/xmltv.php?username=bob&password=secret"))
        assertTrue(!msg.contains("secret") && !msg.contains("bob"))
        assertTrue(msg.contains("host.example"))
    }

    @Test fun names() {
        assertEquals("bbcone", normaliseChannelName("UK: BBC One HD"))
        assertEquals("bbcone", normaliseChannelName("[UK] BBC ONE FHD"))
        assertEquals("skysportsmainevent", normaliseChannelName("UK | Sky Sports Main Event 4K"))
        assertEquals("Dune", cleanTitle("EN | Dune (2021) [4K]"))
        assertEquals("The Batman", cleanTitle("The Batman (2022)"))
        assertEquals("Moana", cleanTitle("4K-D+ - Moana"))
        assertTrue(io.github.lucasclgibson.nimbus.core.isSeparatorChannel("#### GENERAL HD/4K ####"))
        assertTrue(io.github.lucasclgibson.nimbus.core.isSeparatorChannel("===== SPORTS ====="))
        assertTrue(!io.github.lucasclgibson.nimbus.core.isSeparatorChannel("UK: BBC One HD"))
        assertTrue(!io.github.lucasclgibson.nimbus.core.isSeparatorChannel("#1 Hits"))
        assertEquals("BBC One HD", io.github.lucasclgibson.nimbus.core.displayChannelName("UK: BBC One HD"))
        assertEquals("ITV 1 +1", io.github.lucasclgibson.nimbus.core.displayChannelName("UK| ITV 1 +1"))
        assertEquals("Frozen: The Hit Broadway Musical", cleanTitle("4K-D+ - Frozen: The Hit Broadway Musical"))
        assertEquals("The Crown", cleanTitle("NF: The Crown"))
        assertEquals("Paddington", cleanTitle("[UK] Paddington"))
        assertEquals("X-Men: Days of Future Past", cleanTitle("X-Men: Days of Future Past (2014)"))
        assertEquals("Mission: Impossible", cleanTitle("Mission: Impossible"))
        assertEquals("Spider-Man - Homecoming", cleanTitle("Spider-Man - Homecoming"))
    }
}

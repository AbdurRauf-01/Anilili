package com.miruronative.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AllAnimeBundleParserTest {
    @Test
    fun parsesBuildAndUnambiguouslyRotatedSeeds() {
        val js = """
            function table(){const values=['AQIDBA','UGBwg=','ERITFA','UGB4g=','ISIjJC','UmJyg=','MTIzND','U2Nzg=','junk']}
            function decode(index){return index=index-(0),table()[index]}
            const seeds=[decode(0)+decode(1),decode(2)+decode(3),decode(4)+decode(5),decode(6)+decode(7)];
            const build=value!=="string"?"75":"";
        """.trimIndent()

        val parsed = AllAnimeBundleParser.parse(js)

        assertNotNull(parsed)
        assertEquals("75", parsed?.buildId)
        assertEquals(listOf("AQIDBAUGBwg=", "ERITFAUGB4g=", "ISIjJCUmJyg=", "MTIzNDU2Nzg="), parsed?.seeds)
    }
}

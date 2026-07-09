package dev.pointtosky.tools.catalog.ptskcat0

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

class PtskCat0PackingTest {

    private fun writeCsv(content: String): Path {
        val path = Files.createTempFile("hyg", ".csv")
        Files.writeString(path, content.trimIndent())
        return path
    }

    private fun recordAt(bytes: ByteArray, index: Int): ByteBuffer =
        ByteBuffer.wrap(bytes, PtskCat0Writer.HEADER_SIZE + index * PtskCat0Writer.RECORD_SIZE, PtskCat0Writer.RECORD_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun `Sirius round trip catches an hours-vs-degrees RA mistake`() {
        // RA 6.7525h * 15 = 101.2875 deg — if the packer forgot to convert
        // hours to degrees this assertion fails loudly instead of silently
        // mirroring the whole sky.
        val csv = """
            id,hip,ra,dec,mag,ci,proper,bf
            1,32349,6.7525,-16.7161,-1.46,0.009,Sirius,Alp CMa
        """
        val stars = HygRealCatalogParser.read(writeCsv(csv))
        val result = PtskCat0Writer.write(stars, magLimit = 6.5)

        assertEquals(1, result.count)
        val rec = recordAt(result.bytes, 0)
        assertEquals(101.2875f, rec.float, 0.01f)
        assertEquals(-16.7161f, rec.float, 0.01f)
        assertEquals(-146, rec.short.toInt())
        rec.short // bv, unchecked here
        assertEquals(32349, rec.int)
    }

    @Test
    fun `excludes the Sun id zero`() {
        val csv = """
            id,hip,ra,dec,mag
            0,0,0.0,0.0,-26.7
            1,1,10.0,10.0,3.0
        """
        val stars = HygRealCatalogParser.read(writeCsv(csv))
        assertEquals(1, stars.size)
        assertEquals(1, stars.single().id)
    }

    @Test
    fun `missing ci becomes the bv sentinel`() {
        val csv = """
            id,hip,ra,dec,mag,ci
            1,1,10.0,10.0,3.0,
        """
        val stars = HygRealCatalogParser.read(writeCsv(csv))
        assertNull(stars.single().bv)

        val result = PtskCat0Writer.write(stars, magLimit = 6.5)
        val rec = recordAt(result.bytes, 0)
        rec.float; rec.float; rec.short // ra, dec, mag
        assertEquals(PtskCat0Writer.BV_UNKNOWN, rec.short.toInt())
    }

    @Test
    fun `missing hip becomes zero sentinel`() {
        val csv = """
            id,hip,ra,dec,mag
            1,,10.0,10.0,3.0
        """
        val stars = HygRealCatalogParser.read(writeCsv(csv))
        assertEquals(0, stars.single().hip)

        val result = PtskCat0Writer.write(stars, magLimit = 6.5)
        val rec = recordAt(result.bytes, 0)
        rec.float; rec.float; rec.short; rec.short
        assertEquals(0, rec.int)
    }

    @Test
    fun `filters stars fainter than the mag limit`() {
        val csv = """
            id,hip,ra,dec,mag
            1,1,10.0,10.0,3.0
            2,2,20.0,20.0,9.0
        """
        val stars = HygRealCatalogParser.read(writeCsv(csv))
        val result = PtskCat0Writer.write(stars, magLimit = 6.5)
        assertEquals(1, result.count)
    }

    @Test
    fun `no record exceeds the mag limit`() {
        val csv = """
            id,hip,ra,dec,mag
            1,1,1.0,10.0,3.0
            2,2,2.0,20.0,6.4
            3,3,3.0,30.0,6.6
        """
        val stars = HygRealCatalogParser.read(writeCsv(csv))
        val result = PtskCat0Writer.write(stars, magLimit = 6.5)
        for (i in 0 until result.count) {
            val rec = recordAt(result.bytes, i)
            rec.float; rec.float
            assertTrue(rec.short.toInt() <= 650)
        }
    }

    @Test
    fun `records are sorted ascending by magnitude regardless of CSV order`() {
        val csv = """
            id,hip,ra,dec,mag
            1,1,1.0,10.0,5.0
            2,2,2.0,20.0,-1.0
            3,3,3.0,30.0,2.0
        """
        val stars = HygRealCatalogParser.read(writeCsv(csv))
        val result = PtskCat0Writer.write(stars, magLimit = 6.5)

        var previous = Short.MIN_VALUE
        for (i in 0 until result.count) {
            val rec = recordAt(result.bytes, i)
            rec.float; rec.float
            val mag = rec.short
            assertTrue(mag >= previous, "record $i not in ascending mag order")
            previous = mag
        }
    }

    @Test
    fun `same input produces byte-identical output`() {
        val csv = """
            id,hip,ra,dec,mag,ci,proper
            1,1,1.0,10.0,5.0,0.5,Alpha
            2,2,2.0,20.0,-1.0,,
            3,0,3.0,30.0,2.0,1.2,Gamma
        """
        val path = writeCsv(csv)
        val a = PtskCat0Writer.write(HygRealCatalogParser.read(path), magLimit = 6.5)
        val b = PtskCat0Writer.write(HygRealCatalogParser.read(path), magLimit = 6.5)
        assertTrue(a.bytes.contentEquals(b.bytes))
    }

    @Test
    fun `name prefers proper over bf and is omitted when both blank`() {
        val csv = """
            id,hip,ra,dec,mag,proper,bf
            1,1,1.0,10.0,3.0,Alpha Star,Alp Xyz
            2,2,2.0,20.0,3.5,,Bet Xyz
            3,3,3.0,30.0,4.0,,
        """
        val stars = HygRealCatalogParser.read(writeCsv(csv)).sortedBy { it.id }
        assertEquals("Alpha Star", stars[0].name)
        assertEquals("Bet Xyz", stars[1].name)
        assertNull(stars[2].name)
    }

    @Test
    fun `rejects an ra column that is already in degrees`() {
        val csv = """
            id,hip,ra,dec,mag
            1,1,300.0,10.0,3.0
        """
        assertThrows(IllegalStateException::class.java) {
            HygRealCatalogParser.read(writeCsv(csv))
        }
    }

    @Test
    fun `rejects an empty pack when there are no input stars`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            PtskCat0Writer.write(emptyList(), magLimit = 6.5)
        }
        assertTrue(ex.message!!.contains("zero stars"))
        assertTrue(ex.message!!.contains("6.5"))
    }

    @Test
    fun `rejects an empty pack when the mag limit filters out every star`() {
        val csv = """
            id,hip,ra,dec,mag
            1,1,1.0,10.0,9.0
            2,2,2.0,20.0,10.0
        """
        val stars = HygRealCatalogParser.read(writeCsv(csv))

        val ex = assertThrows(IllegalStateException::class.java) {
            PtskCat0Writer.write(stars, magLimit = 6.5)
        }
        assertTrue(ex.message!!.contains("zero stars"))
        assertTrue(ex.message!!.contains("mag"))
        assertTrue(ex.message!!.contains("6.5"))
    }
}

package dev.pointtosky.tools.catalog

import dev.pointtosky.tools.catalog.model.CatalogSource
import dev.pointtosky.tools.catalog.model.PackRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32

class CatalogPackingServiceTest {

    private val service = CatalogPackingService()

    @Test
    fun `packs sample HYG rows`() {
        val csv = """
            id,hip,hd,proper,ra,dec,mag,con,bayer,flamsteed
            1,91262,173780,Altair,297.6958,8.8683,0.76,Aql,Alpha,53
            2,0,0,,120.5,-10.1,6.4,Mon,,14
        """.trimIndent()

        val csvPath = Files.createTempFile("hyg", ".csv")
        Files.writeString(csvPath, csv)

        val request = PackRequest(
            source = CatalogSource.HYG,
            input = csvPath,
            magLimit = 6.5,
            rdpEpsilon = 0.05,
            withConCodes = true,
        )

        val result = service.pack(request)

        val header = ByteBuffer.wrap(result.binary, 0, 32).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(8)
        header.get(magic)
        assertEquals("PTSKSTAR", String(magic))
        assertEquals(1, header.short.toInt())
        header.short // reserved
        assertEquals(2, header.int)
        val stringPoolSize = header.int
        val indexOffset = header.int
        val indexSize = header.int
        val crc = header.int

        val data = result.binary.copyOfRange(32, result.binary.size)
        assertEquals(data.size, stringPoolSize + 2 * 32 + indexSize)

        val crcCalc = CRC32().apply { update(data) }.value.toInt()
        assertEquals(crcCalc, crc)

        val poolBytes = data.copyOfRange(0, stringPoolSize)
        val firstStarRecordOffset = stringPoolSize
        val recordBuffer = ByteBuffer.wrap(data, firstStarRecordOffset, 32).order(ByteOrder.LITTLE_ENDIAN)
        val ra = recordBuffer.float
        val dec = recordBuffer.float
        val mag = recordBuffer.float
        recordBuffer.float // bv
        val hip = recordBuffer.int
        val nameOffset = recordBuffer.int
        val designationOffset = recordBuffer.int
        val flags = recordBuffer.short.toInt() and 0xFFFF
        val conIndex = recordBuffer.short.toInt() and 0xFFFF

        assertEquals(297.6958f, ra, 1e-4f)
        assertEquals(8.8683f, dec, 1e-4f)
        assertEquals(0.76f, mag, 1e-4f)
        assertEquals(91262, hip)
        assertTrue(flags and 0x1 != 0)
        assertTrue(flags and 0x2 != 0)
        assertTrue(flags and 0x4 != 0)
        assertTrue(conIndex >= 0)

        val name = readNullTerminated(poolBytes, nameOffset)
        val designation = readNullTerminated(poolBytes, designationOffset)
        assertEquals("Altair", name)
        assertEquals("ALPHA 53 AQL", designation.uppercase())

        val indexBytes = data.copyOfRange(indexOffset, indexOffset + indexSize)
        val indexBuffer = ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN)
        val bandTable = Array(180) {
            val bandId = indexBuffer.short.toInt()
            val start = indexBuffer.int
            val count = indexBuffer.int
            Triple(bandId, start, count)
        }
        val totalEntries = bandTable.sumOf { it.third }
        val ids = IntArray(totalEntries) { indexBuffer.int }
        val ras = FloatArray(totalEntries) { indexBuffer.float }
        val bandCount = indexBuffer.float
        val entryCount = indexBuffer.float

        assertEquals(180f, bandCount)
        assertEquals(2f, entryCount)
        val nonEmpty = bandTable.filter { it.third > 0 }
        assertEquals(2, nonEmpty.size)
        assertEquals(listOf(0, 1), ids.sorted())
        val minRa = ras.minOrNull() ?: 0f
        val maxRa = ras.maxOrNull() ?: 0f
        assertTrue(minRa <= maxRa)
    }

    @Test
    fun `handles RA wrap ordering within band`() {
        val csv = """
            RAdeg,DEdeg,Vmag,HIP,Bayer,Flamsteed,Name
            359.9,10.1,4.8,1,Alpha,,Star1
            0.1,10.2,4.9,2,,22,Star2
        """.trimIndent()

        val csvPath = Files.createTempFile("bsc", ".csv")
        Files.writeString(csvPath, csv)

        val request = PackRequest(
            source = CatalogSource.BSC,
            input = csvPath,
            magLimit = 6.5,
            rdpEpsilon = 0.05,
            withConCodes = false,
        )

        val result = service.pack(request)

        val header = ByteBuffer.wrap(result.binary, 0, 32).order(ByteOrder.LITTLE_ENDIAN)
        header.position(8 + 2 + 2)
        val count = header.int
        val poolSize = header.int
        val indexOffset = header.int
        val indexSize = header.int
        header.int // crc
        assertEquals(2, count)

        val data = result.binary.copyOfRange(32, result.binary.size)
        val indexBytes = data.copyOfRange(indexOffset, indexOffset + indexSize)
        val buffer = ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN)

        val bandTable = Array(180) {
            val bandId = buffer.short.toInt()
            val start = buffer.int
            val bandCount = buffer.int
            Triple(bandId, start, bandCount)
        }
        val totalEntries = bandTable.sumOf { it.third }
        val ids = IntArray(totalEntries) { buffer.int }
        val ras = FloatArray(totalEntries) { buffer.float }

        val targetBand = bandTable.first { it.third == 2 }
        val startIndex = targetBand.second
        assertEquals(1, ids[startIndex]) // 0.1° should be first after sorting
        assertEquals(0, ids[startIndex + 1])
        assertEquals(0.1f, ras[startIndex], 1e-4f)
        assertEquals(359.9f, ras[startIndex + 1], 1e-4f)
    }

    @Test
    fun `fails on empty catalog after validation`() {
        val csv = """
            RAdeg,DEdeg,Vmag,HIP
        """.trimIndent()

        val csvPath = Files.createTempFile("empty", ".csv")
        Files.writeString(csvPath, csv)

        val request = PackRequest(
            source = CatalogSource.BSC,
            input = csvPath,
            magLimit = 6.5,
            rdpEpsilon = 0.05,
            withConCodes = false,
        )

        val exception = assertThrows(IllegalStateException::class.java) {
            service.pack(request)
        }
        assertTrue(exception.message!!.contains("No valid stars"))
    }

    @Test
    fun `fails when all stars have invalid coordinates`() {
        val csv = """
            RAdeg,DEdeg,Vmag,HIP,Name
            400.0,50.0,5.0,1,Invalid RA
            180.0,100.0,5.0,2,Invalid Dec
            180.0,0.0,20.0,3,Invalid Mag
        """.trimIndent()

        val csvPath = Files.createTempFile("invalid", ".csv")
        Files.writeString(csvPath, csv)

        val request = PackRequest(
            source = CatalogSource.BSC,
            input = csvPath,
            magLimit = 25.0, // High enough to include invalid mag
            rdpEpsilon = 0.05,
            withConCodes = false,
        )

        val exception = assertThrows(IllegalStateException::class.java) {
            service.pack(request)
        }
        assertTrue(exception.message!!.contains("No valid stars"))
    }

    @Test
    fun `validates and skips stars with out-of-range values`() {
        val csv = """
            RAdeg,DEdeg,Vmag,HIP,Name
            400.0,50.0,5.0,1,InvalidRA
            180.0,0.0,3.0,2,ValidStar
            180.0,100.0,4.0,3,InvalidDec
        """.trimIndent()

        val csvPath = Files.createTempFile("mixed", ".csv")
        Files.writeString(csvPath, csv)

        val request = PackRequest(
            source = CatalogSource.BSC,
            input = csvPath,
            magLimit = 6.5,
            rdpEpsilon = 0.05,
            withConCodes = false,
        )

        val result = service.pack(request)

        // Should pack only the valid star
        val header = ByteBuffer.wrap(result.binary, 0, 32).order(ByteOrder.LITTLE_ENDIAN)
        header.position(8 + 2 + 2)
        val starCount = header.int
        assertEquals(1, starCount, "Should have exactly 1 valid star")
    }
}

private fun readNullTerminated(bytes: ByteArray, offset: Int): String {
    var end = offset
    while (end < bytes.size && bytes[end] != 0.toByte()) end++
    return String(bytes, offset, end - offset)
}

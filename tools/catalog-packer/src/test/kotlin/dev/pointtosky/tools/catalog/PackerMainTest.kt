package dev.pointtosky.tools.catalog

import dev.pointtosky.tools.catalog.model.CatalogSource
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * CLI-level failure behavior for --format=ptskcat0: it must throw (and thus fail
 * the process / the Gradle `run` task) rather than print an error and return
 * normally, which would look like success to a caller checking the exit code.
 */
class PackerMainTest {

    @Test
    fun `ptskcat0 format with a non-HYG source throws instead of returning silently`() {
        val cli = CliOptions(
            source = CatalogSource.BSC,
            input = Path.of("/tmp/ptskcat0-test-unused-input.csv"),
            output = Path.of("/tmp/ptskcat0-test-unused-output.bin"),
            magLimit = 6.5,
            rdpEpsilon = 0.05,
            withConCodes = false,
            format = PackFormat.PTSKCAT0,
        )

        val ex = assertThrows(IllegalArgumentException::class.java) {
            packPtskCat0(cli)
        }
        assert(ex.message?.contains("hyg") == true) { "Expected message to mention hyg, was: ${ex.message}" }
    }

    @Test
    fun `ptskcat0 format propagates a missing input file instead of swallowing it`() {
        val cli = CliOptions(
            source = CatalogSource.HYG,
            input = Path.of("/nonexistent-dir-ptskcat0/hyg.csv"),
            output = Path.of("/tmp/ptskcat0-test-unused-output.bin"),
            magLimit = 6.5,
            rdpEpsilon = 0.05,
            withConCodes = false,
            format = PackFormat.PTSKCAT0,
        )

        assertThrows(Exception::class.java) {
            packPtskCat0(cli)
        }
    }
}

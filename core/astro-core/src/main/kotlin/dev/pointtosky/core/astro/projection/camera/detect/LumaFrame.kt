package dev.pointtosky.core.astro.projection.camera.detect

import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaFormat
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaReference

/**
 * SKY-2: one decoded 8-bit luma plane, in the exact form the SKY-1 capture writes it.
 *
 * This is the detector's only input format, and it is deliberately the *stored* one rather than a
 * convenient one: `SkyLumaFormat.RAW_Y8` is `ImageProxy.planes[0]` written verbatim, which means
 * [rowStridePx] may exceed [widthPx] — the hardware pads rows to an alignment the image width does not
 * have to respect. A detector that assumed `width * height` packing would read padding bytes as if they
 * were the leftmost pixels of the next row, producing a diagonal smear of phantom sources that looks
 * exactly like a real star field. So the stride is carried, honoured on every access, and never derived.
 *
 * Higher byte = brighter, unsigned: `data[i]` is a Kotlin signed `Byte`, so a luma of 200 arrives as
 * `-56` and must be masked back with `and 0xFF` before any comparison. [lumaAt] is the only sanctioned
 * way to read a pixel for that reason.
 *
 * The size bound mirrors [SkyLumaReference.minimumByteLength] exactly — full rows for all but the last,
 * then one row of pixels — so a buffer this type accepts is one the SKY-1 reference would also accept.
 * Trailing padding on the final row is permitted but never read.
 */
class LumaFrame(
    private val data: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    val rowStridePx: Int = widthPx,
) {
    init {
        require(widthPx > 0) { "widthPx must be positive; was $widthPx" }
        require(heightPx > 0) { "heightPx must be positive; was $heightPx" }
        require(rowStridePx >= widthPx) { "rowStridePx ($rowStridePx) must be >= widthPx ($widthPx)" }
        require(data.size >= minimumByteLength) {
            "data (${data.size} bytes) is too small for ${widthPx}x$heightPx stride=$rowStridePx " +
                "(needs at least $minimumByteLength)"
        }
    }

    /** The smallest buffer that can hold this geometry; the same bound [SkyLumaReference] enforces. */
    val minimumByteLength: Long
        get() = rowStridePx.toLong() * (heightPx - 1L) + widthPx.toLong()

    /** Total addressable pixels — `widthPx * heightPx`, never `data.size`, which counts row padding. */
    val pixelCount: Int
        get() = widthPx * heightPx

    /**
     * The unsigned 0..255 intensity at ([x], [y]), origin top-left, x right, y down — the same image
     * axis convention `SkyPredictedStar.imageXPx`/`imageYPx` are expressed in, so a centroid computed
     * here is directly comparable to a prediction with no axis flip in between.
     */
    fun lumaAt(
        x: Int,
        y: Int,
    ): Int = data[y * rowStridePx + x].toInt() and 0xFF

    companion object {
        /**
         * Wraps [data] — the bytes of the file [reference] points at — as a frame, checking that the
         * reference actually describes what was read.
         *
         * [SkyLumaReference.byteLength] is checked against `data.size` rather than assumed: a truncated
         * or half-written frame file is a real capture outcome (the process can die mid-flush), and it
         * must fail here rather than silently detect stars in whatever the tail of the buffer happens to
         * contain.
         *
         * @throws IllegalArgumentException when the format is not [SkyLumaFormat.RAW_Y8] or [data] does
         *   not match the reference's recorded length.
         */
        fun forReference(
            reference: SkyLumaReference,
            data: ByteArray,
        ): LumaFrame {
            require(reference.format == SkyLumaFormat.RAW_Y8) {
                "only ${SkyLumaFormat.RAW_Y8} can be read as a luma frame; was ${reference.format}"
            }
            require(data.size.toLong() == reference.byteLength) {
                "data (${data.size} bytes) does not match the recorded byteLength (${reference.byteLength}) — " +
                    "the frame file at ${reference.path} is truncated or was replaced"
            }
            return LumaFrame(
                data = data,
                widthPx = reference.widthPx,
                heightPx = reference.heightPx,
                rowStridePx = reference.rowStridePx,
            )
        }
    }
}

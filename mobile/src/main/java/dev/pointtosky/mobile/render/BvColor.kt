package dev.pointtosky.mobile.render

import androidx.compose.ui.graphics.Color

/**
 * Maps a Johnson B−V color index to an approximate sRGB star color
 * (blue → white → orange). Table-driven; null/NaN → neutral white so
 * stars with unknown color match the previous look.
 *
 * Anchor RGB values approximate Mitchell Charity's bv2rgb ramp. They are
 * the single source of truth — swap in Charity's exact tabulation here if
 * pixel-exact values are wanted; the interpolation is unaffected.
 */
object BvColor {
    // (B−V, R, G, B), ascending B−V. Ends are clamped.
    private val table = arrayOf(
        floatArrayOf(-0.40f, 155f, 178f, 255f),
        floatArrayOf(-0.30f, 162f, 184f, 255f),
        floatArrayOf(-0.20f, 170f, 191f, 255f),
        floatArrayOf(-0.10f, 182f, 200f, 255f),
        floatArrayOf( 0.00f, 195f, 209f, 255f),
        floatArrayOf( 0.10f, 202f, 215f, 255f),
        floatArrayOf( 0.20f, 216f, 224f, 255f),
        floatArrayOf( 0.30f, 228f, 232f, 255f),
        floatArrayOf( 0.40f, 237f, 238f, 255f),
        floatArrayOf( 0.50f, 246f, 243f, 255f),
        floatArrayOf( 0.60f, 255f, 247f, 252f),
        floatArrayOf( 0.70f, 255f, 245f, 242f),
        floatArrayOf( 0.80f, 255f, 241f, 229f),
        floatArrayOf( 1.00f, 255f, 230f, 204f),
        floatArrayOf( 1.20f, 255f, 219f, 178f),
        floatArrayOf( 1.40f, 255f, 209f, 163f),
        floatArrayOf( 1.60f, 255f, 200f, 148f),
        floatArrayOf( 2.00f, 255f, 184f, 122f),
    )

    fun toColor(bv: Float?): Color {
        if (bv == null || bv.isNaN()) return Color.White
        if (bv <= table.first()[0]) return rgb(table.first())
        if (bv >= table.last()[0]) return rgb(table.last())
        for (i in 0 until table.size - 1) {
            val lo = table[i]
            val hi = table[i + 1]
            if (bv <= hi[0]) {
                val t = (bv - lo[0]) / (hi[0] - lo[0])
                return Color(
                    red = lerp(lo[1], hi[1], t) / 255f,
                    green = lerp(lo[2], hi[2], t) / 255f,
                    blue = lerp(lo[3], hi[3], t) / 255f,
                )
            }
        }
        return Color.White
    }

    private fun rgb(a: FloatArray) = Color(a[1] / 255f, a[2] / 255f, a[3] / 255f)

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

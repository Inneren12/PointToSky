package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3

/**
 * `internalDebug`-only. **CAMERAX_1_4_2_IMPLEMENTATION_MODEL** — a pure, Android-free reproduction of
 * how CameraX 1.4.2 constructs `ImageInfo.getSensorToBufferTransformMatrix()`, traced in
 * `docs/recon/cam_2c_sensor_to_buffer_domain_recon.md` §2.1–§2.2 to
 * `CameraUseCaseAdapter.calculateSensorToBufferTransformMatrix` (camera-core 1.4.2):
 *
 * ```java
 * sensorToUseCaseTransformation.setRectToRect(bufferRect, fullSensorRectF, Matrix.ScaleToFit.CENTER);
 * sensorToUseCaseTransformation.invert(sensorToUseCaseTransformation);
 * ```
 *
 * where `fullSensorRectF` is the **opened camera's** `SENSOR_INFO_ACTIVE_ARRAY_SIZE` rect *including*
 * its native `left`/`top` offsets. This file exists so the dual-basis diagnostic can compute, for any
 * candidate source rect, the matrix this exact CameraX version *would* construct, and compare it
 * against the device-observed matrix.
 *
 * ## Scope and honesty
 * - This is a model of one pinned implementation (`androidx.camera:camera-camera2:1.4.2` /
 *   `camera-core:1.4.2`), **not** an eternal cross-version API guarantee. A CameraX upgrade
 *   invalidates it until re-traced (the 1.3.4 behavior was materially different — recon §2.8).
 * - `android.graphics.Matrix` stores float32; every model coefficient below is therefore computed
 *   with explicit float32 rounding at each arithmetic step (`.toFloat()` between operations) and
 *   widened back to `Double`, mirroring how the real values reach this codebase. The platform's
 *   Skia inversion may sequence the translation computation differently (`-t/s` vs `-t * (1/s)`),
 *   which can differ by a few float32 ULPs — a comparison against this model must therefore use
 *   [CAMERAX_142_MODEL_MATCH_TOLERANCE_PX]-style tolerances, never bit equality.
 * - This function must never become production projection math: it predicts what CameraX *asserts*,
 *   which is not a measurement of what the HAL placed in the buffer (frame-content correspondence
 *   remains unmeasured — recon §1).
 */

/** Which buffer axis the model expects to fit the source rect exactly under `ScaleToFit.CENTER`'s
 * inverse (the axis whose ratio is the fit minimum). */
internal enum class CameraX142FitAxis {
    /** `sourceWidth/bufferWidth <= sourceHeight/bufferHeight`: the mapped source width spans the
     * buffer width exactly; any aspect excess overflows vertically. */
    WIDTH_FITS_EXACTLY,

    /** The transposed case: mapped source height spans the buffer height exactly; excess overflows
     * horizontally. */
    HEIGHT_FITS_EXACTLY,

    /** Aspect ratios match exactly: both axes fit, no overflow. */
    BOTH_FIT_EXACTLY,
}

/** Direction of the model's expected symmetric mapped-bounds overflow (the "center crop" side of the
 * inverse fit-CENTER construction). */
internal enum class CameraX142OverflowDirection {
    NONE,
    VERTICAL_TOP_BOTTOM,
    HORIZONTAL_LEFT_RIGHT,
}

/**
 * `internalDebug`-only. The model's prediction for one (source rect, buffer) pair.
 *
 * @property matrix the predicted sensor-to-buffer matrix, float32-rounded coefficients widened to
 *   `Double` (see file KDoc — model of `android.graphics.Matrix` float storage, not a native
 *   double-precision measurement).
 * @property fitAxis which axis the model expects to fit exactly.
 * @property overflowDirection where the model expects symmetric mapped-bounds overflow.
 * @property expectedOverflowPerSidePx the model's expected symmetric overflow magnitude per side, in
 *   buffer pixels, computed in double precision from the exact integer inputs (geometry, not float32
 *   storage — kept separate deliberately).
 */
internal data class CameraX142PredictedSensorToBuffer(
    val matrix: SensorToBufferMatrix3,
    val fitAxis: CameraX142FitAxis,
    val overflowDirection: CameraX142OverflowDirection,
    val expectedOverflowPerSidePx: Double,
)

/**
 * Predicts the CameraX 1.4.2 sensor-to-buffer matrix for [sourceRect] (a full native rect, offsets
 * included — non-zero `left`/`top` are handled exactly as `new RectF(fullSensorRect)` would) and a
 * [bufferWidthPx] × [bufferHeightPx] buffer. Returns `null` when any dimension is non-positive — a
 * typed absence the caller reports explicitly, never a guess.
 *
 * Model steps (each rounded to float32 like the platform's `Matrix`/Skia float pipeline):
 * 1. forward `setRectToRect(buffer, sourceRect, CENTER)`: `scale = min(srcW/bufW, srcH/bufH)`,
 *    `tx = srcLeft + (srcW − scale·bufW)/2`, `ty = srcTop + (srcH − scale·bufH)/2`;
 * 2. inversion of the pure scale+translate: `s' = 1/scale`, `tx' = −tx/scale`, `ty' = −ty/scale`.
 */
internal fun predictCameraX142SensorToBufferMatrix(
    sourceRect: CameraBasisRect,
    bufferWidthPx: Int,
    bufferHeightPx: Int,
): CameraX142PredictedSensorToBuffer? {
    if (bufferWidthPx <= 0 || bufferHeightPx <= 0) return null
    val srcW = sourceRect.widthPx.toFloat()
    val srcH = sourceRect.heightPx.toFloat()
    val bufW = bufferWidthPx.toFloat()
    val bufH = bufferHeightPx.toFloat()

    // Forward fit (buffer -> source rect), float32 at every step, mirroring
    // Matrix.setRectToRect(..., ScaleToFit.CENTER).
    val ratioX = srcW / bufW
    val ratioY = srcH / bufH
    val scale = minOf(ratioX, ratioY)
    val fittedW = scale * bufW
    val fittedH = scale * bufH
    val tx = sourceRect.leftPx.toFloat() + (srcW - fittedW) / 2.0f
    val ty = sourceRect.topPx.toFloat() + (srcH - fittedH) / 2.0f

    // Inverse of the pure scale+translate, float32. See file KDoc: the platform's own inversion may
    // compute the translation as -t * (1/s) instead of -t/s — a few-ULP difference comparisons must
    // absorb via tolerance, never bit equality.
    val invScale = (1.0f / scale)
    val invTx = (-tx / scale)
    val invTy = (-ty / scale)

    val matrix =
        SensorToBufferMatrix3(
            m00 = invScale.toDouble(),
            m01 = 0.0,
            m02 = invTx.toDouble(),
            m10 = 0.0,
            m11 = invScale.toDouble(),
            m12 = invTy.toDouble(),
            m20 = 0.0,
            m21 = 0.0,
            m22 = 1.0,
        )

    // Geometry expectations in exact double precision from the integer inputs — deliberately NOT the
    // float32 model values, so the "expected crop magnitude" is a clean geometric statement.
    val srcWd = sourceRect.widthPx.toDouble()
    val srcHd = sourceRect.heightPx.toDouble()
    val bufWd = bufferWidthPx.toDouble()
    val bufHd = bufferHeightPx.toDouble()
    val exactRatioX = srcWd / bufWd
    val exactRatioY = srcHd / bufHd
    val (fitAxis, overflowDirection, overflowPerSide) =
        when {
            exactRatioX == exactRatioY ->
                Triple(CameraX142FitAxis.BOTH_FIT_EXACTLY, CameraX142OverflowDirection.NONE, 0.0)
            exactRatioX < exactRatioY -> {
                // Width fits; mapped height = srcH / ratioX exceeds the buffer height.
                val mappedHeight = srcHd / exactRatioX
                Triple(
                    CameraX142FitAxis.WIDTH_FITS_EXACTLY,
                    CameraX142OverflowDirection.VERTICAL_TOP_BOTTOM,
                    (mappedHeight - bufHd) / 2.0,
                )
            }
            else -> {
                val mappedWidth = srcWd / exactRatioY
                Triple(
                    CameraX142FitAxis.HEIGHT_FITS_EXACTLY,
                    CameraX142OverflowDirection.HORIZONTAL_LEFT_RIGHT,
                    (mappedWidth - bufWd) / 2.0,
                )
            }
        }

    return CameraX142PredictedSensorToBuffer(
        matrix = matrix,
        fitAxis = fitAxis,
        overflowDirection = overflowDirection,
        expectedOverflowPerSidePx = overflowPerSide,
    )
}

package dev.pointtosky.mobile.ar.camera

import androidx.core.content.FileProvider

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only). A trivial [FileProvider]
 * subclass with no behavior of its own — AGP's manifest merger keys `<provider>` elements by
 * `android:name` alone, so a second `<provider android:name="androidx.core.content.FileProvider">`
 * entry (this one) collides with this app's existing `${applicationId}.logs` provider
 * (`mobile/src/main/AndroidManifest.xml`) even though their `android:authorities`/`android:resource`
 * values differ. A distinct subclass name is the standard fix for declaring more than one `FileProvider`
 * in the same app.
 */
internal class FrameContentTargetSvgFileProvider : FileProvider()

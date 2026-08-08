package dev.pointtosky.core.catalog.binary

import dev.pointtosky.core.astro.projection.camera.match.StarCatalogQuery
import dev.pointtosky.core.astro.projection.camera.match.requireValidStarCatalogQuery
import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection

/**
 * The device-side implementation of `:core:astro-core`'s [StarCatalogQuery] port, backed by the real
 * PTSKCAT0 catalog.
 *
 * ## Why the adapter lives here and not in astro-core
 * `:core:astro-core` is a pure-JVM module and `:core:catalog` is a `com.android.library`, so the
 * dependency can only run in this direction — `:core:catalog` already depends on `:core:astro-core`,
 * and reversing it is impossible, not merely undesirable. Placing the adapter here means astro-core's
 * `build.gradle.kts` is untouched by this PR and the matcher can be written against an interface that
 * has no idea a catalog file exists. (It is placed in `:core:catalog` rather than `:mobile` because
 * that is where the catalog reader it wraps already lives, and `:mobile` already depends on
 * `:core:catalog`; nothing in this class is Android-specific, so it is unit-testable as plain JVM code
 * exactly like [PtskCat0Catalog] itself.)
 *
 * ## What it translates
 *  - **Radians to degrees.** The port speaks radians (astro-core's convention); [PtskCat0Catalog]
 *    stores and queries degrees. The conversion happens here, once, on the way in and on the way out.
 *    A cone radius therefore goes through one degree/radian round trip, which moves a boundary star by
 *    a few parts in `10¹⁵` of a degree — far below the catalog's own `Float` record precision.
 *  - **Record index to catalog identity.** [EquatorialStarDirection.catalogIndex] is the PTSKCAT0
 *    record index, which is what [PtskCat0Catalog.nearby] returns and what every other accessor on that
 *    class is keyed by. It is stable for a given catalog binary and is deliberately not the Hipparcos
 *    number: a caller that needs a cross-binary identity has this catalog handle and can read
 *    [PtskCat0Catalog.hipAt] itself.
 *  - **Magnitude.** Always present — every PTSKCAT0 record carries one — so the port's nullable
 *    magnitude is never `null` here.
 *
 * Nothing else crosses: no name, no B−V colour, no Hipparcos number, no catalog object. Those are
 * renderer and UI concerns and a matcher has no use for them.
 *
 * ## What it does not do
 * No spatial index, no caching, no sorting, no filtering beyond the cone and the magnitude limit the
 * caller asked for. [PtskCat0Catalog.nearby] is a linear scan over the magnitude-sorted prefix; when
 * that becomes the bottleneck the fix belongs in the reader, behind this same port, with no change
 * here and none in astro-core.
 *
 * @property catalog an already-parsed catalog. Loading it (from an Android asset, a file, or a test
 *   fixture) is somebody else's job — see [RealStarCatalogProvider].
 */
class PtskCat0StarCatalogQuery(
    private val catalog: PtskCat0Catalog,
) : StarCatalogQuery {
    /**
     * See [StarCatalogQuery.nearby]. Determinism, distinct identities and the empty result for a
     * zero radius all follow from [PtskCat0Catalog.nearby] returning each matching record index at most
     * once, in ascending index order (which, for PTSKCAT0, is ascending magnitude — brightest first).
     * That ordering is an observation about the current reader, not a promise this port makes.
     */
    override fun nearby(
        rightAscensionRad: Double,
        declinationRad: Double,
        radiusRad: Double,
        magnitudeLimit: Double?,
    ): List<EquatorialStarDirection> {
        requireValidStarCatalogQuery(rightAscensionRad, declinationRad, radiusRad, magnitudeLimit)
        if (radiusRad == 0.0) return emptyList()

        return catalog
            .nearby(
                raDegQuery = Math.toDegrees(rightAscensionRad),
                decDegQuery = Math.toDegrees(declinationRad),
                radiusDeg = Math.toDegrees(radiusRad),
                magLimitQuery = magnitudeLimit,
            ).map { index ->
                EquatorialStarDirection.of(
                    catalogIndex = index,
                    rightAscensionRad = Math.toRadians(catalog.raDegAt(index).toDouble()),
                    declinationRad = Math.toRadians(catalog.decDegAt(index).toDouble()),
                    magnitude = catalog.magAt(index),
                )
            }
    }
}

package dev.pointtosky.core.astro.catalog

@JvmInline
value class PtskStringId(val offset: Int)

@JvmInline
value class ConstellationId(val index: Int) {
    init {
        require(index in 0..87) { "Constellation index must be in [0,87], was $index" }
    }
}

@JvmInline
value class StarId(val raw: Int) {
    fun cc(): Int = raw / 10_000
    fun pp(): Int = (raw / 100) % 100
    fun ss(): Int = raw % 100
}

data class ConstellationMeta(
    val id: ConstellationId,
    val abbreviation: String,
    val name: String,
)

data class StarRecord(
    val id: StarId,
    val rightAscensionDeg: Float,
    val declinationDeg: Float,
    val magnitude: Float,
    val constellationId: ConstellationId,
    val flags: Int,
    val name: String?,
)

data class AsterismPoly(
    val style: Int,
    val nodes: List<StarId>,
)

data class Asterism(
    val constellationId: ConstellationId,
    val flags: Int,
    val name: String,
    val polylines: List<AsterismPoly>,
    val labelStarId: StarId,
)

data class ArtOverlay(
    val constellationId: ConstellationId,
    val flags: Int,
    val artKey: String,
    val anchorStarA: StarId,
    val anchorStarB: StarId,
)

/**
 * Каталог звёздных данных, загруженный из PTSKCAT4.
 */
interface AstroCatalog {
    fun getConstellationMeta(id: ConstellationId): ConstellationMeta

    fun allStars(): List<StarRecord>

    /**
     * Быстрый доступ к звёздам по исходному идентификатору без пересборки карт.
     */
    fun starById(raw: Int): StarRecord?

    fun starsByConstellation(id: ConstellationId): List<StarRecord>

    fun asterismsByConstellation(id: ConstellationId): List<Asterism>

    fun artOverlaysByConstellation(id: ConstellationId): List<ArtOverlay>
}

object EmptyAstroCatalog : AstroCatalog {
    private val emptyConstellation = ConstellationMeta(ConstellationId(0), abbreviation = "", name = "")

    override fun getConstellationMeta(id: ConstellationId): ConstellationMeta = emptyConstellation

    override fun allStars(): List<StarRecord> = emptyList()

    override fun starById(raw: Int): StarRecord? = null

    override fun starsByConstellation(id: ConstellationId): List<StarRecord> = emptyList()

    override fun asterismsByConstellation(id: ConstellationId): List<Asterism> = emptyList()

    override fun artOverlaysByConstellation(id: ConstellationId): List<ArtOverlay> = emptyList()
}

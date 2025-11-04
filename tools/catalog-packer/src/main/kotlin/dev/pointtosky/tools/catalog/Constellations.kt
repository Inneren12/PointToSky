package dev.pointtosky.tools.catalog

object Constellations {
    private val codes: List<String> = listOf(
        "And", "Ant", "Aps", "Aql", "Aqr", "Ara", "Ari", "Aur",
        "Boo", "Cae", "Cam", "Cap", "Car", "Cas", "Cen", "Cep",
        "Cet", "Cha", "Cir", "CMa", "CMi", "Cnc", "Col", "Com",
        "CrA", "CrB", "Crt", "Cru", "Crv", "CVn", "Cyg", "Del",
        "Dor", "Dra", "Equ", "Eri", "For", "Gem", "Gru", "Her",
        "Hor", "Hya", "Hyi", "Ind", "Lac", "Leo", "Lep", "Lib",
        "Lup", "Lyn", "Lyr", "Men", "Mic", "Mon", "Mus", "Nor",
        "Oct", "Oph", "Ori", "Pav", "Peg", "Per", "Phe", "Pic",
        "PsA", "Psc", "Pup", "Pyx", "Ret", "Scl", "Sco", "Sct",
        "Ser", "Sex", "Sge", "Sgr", "Tau", "Tel", "TrA", "Tri",
        "Tuc", "UMa", "UMi", "Vel", "Vir", "Vol", "Vul"
    )

    private val codeToIndex: Map<String, Int> = codes
        .mapIndexed { index, code -> code.uppercase() to index }
        .toMap()

    fun indexOf(code: String?): Int {
        if (code == null) return -1
        return codeToIndex[code.trim().uppercase()] ?: -1
    }

    fun allCodes(): List<String> = codes
}

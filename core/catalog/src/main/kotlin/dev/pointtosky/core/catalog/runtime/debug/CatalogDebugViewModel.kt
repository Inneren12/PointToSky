package dev.pointtosky.core.catalog.runtime.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.catalog.runtime.CatalogDiagnostics
import dev.pointtosky.core.catalog.runtime.CatalogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

class CatalogDebugViewModel(private val repository: CatalogRepository) : ViewModel() {
    private val _state = MutableStateFlow(
        CatalogDebugUiState(
            diagnostics = repository.diagnostics,
            probeForm = ProbeFormState(),
            probeResults = emptyList(),
            probeError = null,
            lastProbeTimestamp = null,
            selfTestResults = emptyList(),
        ),
    )
    val state: StateFlow<CatalogDebugUiState> = _state.asStateFlow()

    fun updateRa(value: String) {
        _state.update { it.copy(probeForm = it.probeForm.copy(ra = value)) }
    }

    fun updateDec(value: String) {
        _state.update { it.copy(probeForm = it.probeForm.copy(dec = value)) }
    }

    fun updateRadius(value: String) {
        _state.update { it.copy(probeForm = it.probeForm.copy(radius = value)) }
    }

    fun updateMagLimit(value: String) {
        _state.update { it.copy(probeForm = it.probeForm.copy(magLimit = value)) }
    }

    fun runProbe() {
        val form = _state.value.probeForm
        val ra = form.ra.toDoubleOrNull()
        val dec = form.dec.toDoubleOrNull()
        val radius = form.radius.toDoubleOrNull()
        if (ra == null || dec == null || radius == null) {
            _state.update { it.copy(probeError = "Введите корректные RA/Dec/радиус") }
            return
        }
        val magLimit = form.magLimit.toDoubleOrNull()
        val center = Equatorial(ra, dec)
        val results = repository.probe(center, radius, magLimit, maxResults = 8)
        val probes = results.mapIndexed { index, result ->
            ProbeResultUi(
                index = index + 1,
                label = result.name ?: result.designation ?: "#${result.id}",
                id = result.id,
                magnitude = result.magnitude,
                separationDeg = result.separationDeg,
                constellation = result.constellation,
            )
        }
        _state.update {
            it.copy(
                probeResults = probes,
                probeError = null,
                lastProbeTimestamp = System.currentTimeMillis(),
            )
        }
    }

    fun runSelfTest() {
        val results = repository.runSelfTest()
        val mapped = results.map { result ->
            SelfTestResultUi(
                name = result.name,
                passed = result.passed,
                detail = result.detail,
            )
        }
        _state.update { it.copy(selfTestResults = mapped) }
    }
}

class CatalogDebugViewModelFactory(private val repository: CatalogRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CatalogDebugViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CatalogDebugViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class ${'$'}modelClass")
    }
}

data class CatalogDebugUiState(
    val diagnostics: CatalogDiagnostics,
    val probeForm: ProbeFormState,
    val probeResults: List<ProbeResultUi>,
    val probeError: String?,
    val lastProbeTimestamp: Long?,
    val selfTestResults: List<SelfTestResultUi>,
)

data class ProbeFormState(
    val ra: String = "90.0",
    val dec: String = "0.0",
    val radius: String = "5.0",
    val magLimit: String = "",
)

data class ProbeResultUi(
    val index: Int,
    val label: String,
    val id: Int,
    val magnitude: Double,
    val separationDeg: Double,
    val constellation: String?,
)

data class SelfTestResultUi(
    val name: String,
    val passed: Boolean,
    val detail: String,
)

fun formatMagnitude(value: Double): String = String.format("%.2f", value)
fun formatDegrees(value: Double): String = String.format("%.2f°", value)
fun formatBytes(bytes: Int): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.lastIndex) {
        size /= 1024.0
        unitIndex += 1
    }
    val rounded = (size * 10).roundToInt() / 10.0
    return "$rounded ${units[unitIndex]}"
}

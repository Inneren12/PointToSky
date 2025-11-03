package dev.pointtosky.wear.sensors.orientation

internal class ExponentialLowPassFilter(
    private val alpha: Float,
    private val dimension: Int,
) {

    init {
        require(alpha in 0f..1f) { "alpha must be within [0, 1]" }
        require(dimension > 0) { "dimension must be positive" }
    }

    private val state = FloatArray(dimension)
    private var initialized = false

    fun filter(input: FloatArray, output: FloatArray): FloatArray {
        require(input.size >= dimension) { "Input vector must have at least $dimension elements" }
        require(output.size >= dimension) { "Output vector must have at least $dimension elements" }

        if (!initialized) {
            for (i in 0 until dimension) {
                val value = input[i]
                state[i] = value
                output[i] = value
            }
            initialized = true
            return output
        }

        for (i in 0 until dimension) {
            val previous = state[i]
            val filtered = previous + alpha * (input[i] - previous)
            state[i] = filtered
            output[i] = filtered
        }

        return output
    }

    fun reset() {
        initialized = false
    }
}

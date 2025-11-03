package dev.pointtosky.core.logging

/**
 * Hook for sanitising Personally Identifiable Information (PII) before the event is persisted.
 * Currently a passthrough implementation is used. Integrators can swap the implementation at
 * runtime via [LoggerConfig].
 */
fun interface Redactor {
    fun redact(event: LogEvent): LogEvent

    object Passthrough : Redactor {
        override fun redact(event: LogEvent): LogEvent = event // TODO introduce real redaction
    }
}

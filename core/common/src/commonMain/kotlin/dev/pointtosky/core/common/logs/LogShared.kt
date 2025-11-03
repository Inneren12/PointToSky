package dev.pointtosky.core.common.logs

const val LOG_RETENTION_MAX_FILES = 7
const val LOG_RETENTION_MAX_BYTES: Long = 30L * 1024L * 1024L
const val LOG_TAIL_DEFAULT_LINES = 200
const val LOG_TRANSFER_SIZE_LIMIT_BYTES: Long = 10L * 1024L * 1024L

const val LOGS_DIRECTORY = "logs"
const val LOGS_FROM_WATCH_DIRECTORY = "from_watch"

const val LOG_MESSAGE_REQUEST_PATH = "/logs/request_push"
const val LOG_MESSAGE_ANNOUNCE_PATH = "/logs/announce_list"
const val LOG_MESSAGE_TRANSFER_FAILED_PATH = "/logs/transfer_failed"
const val LOG_MESSAGE_QUERY_FILE = "file"
const val LOG_MESSAGE_QUERY_REASON = "reason"
const val LOG_MESSAGE_REASON_TOO_LARGE = "too_large"
const val LOG_MESSAGE_REASON_MISSING = "missing"

const val LOG_DATA_ITEM_PREFIX = "/logs/file/"


data class LogSummary(
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val isCompressed: Boolean,
)

sealed interface WatchTransferEvent {
    val fileName: String

    data class Completed(override val fileName: String, val success: Boolean, val message: String?) :
        WatchTransferEvent
}

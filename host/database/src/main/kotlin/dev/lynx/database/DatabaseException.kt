package dev.lynx.database

class DatabaseException(
    val code: String,
    val operation: String,
    override val message: String,
    val retryable: Boolean = false,
    val resourceId: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

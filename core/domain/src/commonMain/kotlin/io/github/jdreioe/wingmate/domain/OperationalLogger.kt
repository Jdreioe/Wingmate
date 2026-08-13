package io.github.jdreioe.wingmate.domain

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Content-free production diagnostics.
 *
 * Values are deliberately limited to bounded operational metadata. User content,
 * configuration values, endpoints, and throwable objects do not belong here.
 */
object OperationalLogger {
    private val logger = KotlinLogging.logger("WingmateOperations")

    fun debug(
        operation: String,
        outcome: String,
        count: Int? = null,
        statusCode: Int? = null,
        enabled: Boolean? = null,
        exceptionClass: String? = null,
    ) = logger.debug { message(operation, outcome, count, statusCode, enabled, exceptionClass) }

    fun info(
        operation: String,
        outcome: String,
        count: Int? = null,
        statusCode: Int? = null,
        enabled: Boolean? = null,
        exceptionClass: String? = null,
    ) = logger.info { message(operation, outcome, count, statusCode, enabled, exceptionClass) }

    fun warn(
        operation: String,
        outcome: String,
        count: Int? = null,
        statusCode: Int? = null,
        enabled: Boolean? = null,
        exceptionClass: String? = null,
    ) = logger.warn { message(operation, outcome, count, statusCode, enabled, exceptionClass) }

    fun error(
        operation: String,
        outcome: String,
        count: Int? = null,
        statusCode: Int? = null,
        enabled: Boolean? = null,
        exceptionClass: String? = null,
    ) = logger.error { message(operation, outcome, count, statusCode, enabled, exceptionClass) }

    private fun message(
        operation: String,
        outcome: String,
        count: Int?,
        statusCode: Int?,
        enabled: Boolean?,
        exceptionClass: String?,
    ): String = buildString {
        append("operation=")
        append(operation)
        append(" outcome=")
        append(outcome)
        count?.let { append(" count=").append(it) }
        statusCode?.let { append(" statusCode=").append(it) }
        enabled?.let { append(" enabled=").append(it) }
        exceptionClass?.let { append(" exception=").append(it) }
    }
}

fun Throwable.loggingClassName(): String = this::class.simpleName ?: "UnknownThrowable"

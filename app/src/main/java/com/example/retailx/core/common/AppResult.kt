package com.example.retailx.core.common

sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

sealed class AppError(open val code: String, open val userMessage: String) {
    data class ValidationError(override val userMessage: String) : AppError("VALIDATION_ERROR", userMessage)
    data class InsufficientStock(override val userMessage: String) : AppError("INSUFFICIENT_STOCK", userMessage)
    data class DuplicateCode(override val userMessage: String) : AppError("DUPLICATE_CODE", userMessage)
    data class Unauthorized(override val userMessage: String) : AppError("UNAUTHORIZED", userMessage)
    data class NotFound(override val userMessage: String) : AppError("NOT_FOUND", userMessage)
    data class Conflict(override val userMessage: String) : AppError("CONFLICT", userMessage)
    data class HostUnavailable(override val userMessage: String) : AppError("HOST_UNAVAILABLE", userMessage)
    data class Unexpected(override val userMessage: String) : AppError("INTERNAL_ERROR", userMessage)
}

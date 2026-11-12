package com.brobehave.core.data

import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.AuthWeakPasswordException
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** What went wrong, in terms the UI can explain. Never carries server messages or user data. */
enum class DataError {
    NETWORK,
    INVALID_CREDENTIALS,
    EMAIL_IN_USE,
    EMAIL_NOT_CONFIRMED,
    WEAK_PASSWORD,
    RATE_LIMITED,
    USERNAME_TAKEN,
    USERNAME_INVALID,
    NOT_ALLOWED,
    NOT_SIGNED_IN,
    UNKNOWN,
}

class DataException(val error: DataError, cause: Throwable? = null) : Exception(error.name, cause)

private const val PG_UNIQUE_VIOLATION = "23505"
private const val PG_CHECK_VIOLATION = "23514"
private const val PG_INSUFFICIENT_PRIVILEGE = "42501"

/** True when the failure is worth retrying later (no connection, timeout). */
internal fun Throwable.isNetworkFailure(): Boolean = this is HttpRequestException || this is IOException

internal fun Throwable.toDataError(): DataError = when (this) {
    is DataException -> error
    is AuthWeakPasswordException -> DataError.WEAK_PASSWORD
    is AuthRestException -> when (errorCode) {
        AuthErrorCode.InvalidCredentials -> DataError.INVALID_CREDENTIALS
        AuthErrorCode.UserAlreadyExists, AuthErrorCode.EmailExists -> DataError.EMAIL_IN_USE
        AuthErrorCode.EmailNotConfirmed -> DataError.EMAIL_NOT_CONFIRMED
        AuthErrorCode.WeakPassword -> DataError.WEAK_PASSWORD
        AuthErrorCode.OverRequestRateLimit -> DataError.RATE_LIMITED
        else -> DataError.UNKNOWN
    }
    is PostgrestRestException -> when (code) {
        PG_UNIQUE_VIOLATION -> DataError.USERNAME_TAKEN
        PG_CHECK_VIOLATION -> DataError.USERNAME_INVALID
        PG_INSUFFICIENT_PRIVILEGE -> DataError.NOT_ALLOWED
        else -> DataError.UNKNOWN
    }
    is RestException -> DataError.UNKNOWN
    else -> if (isNetworkFailure()) DataError.NETWORK else DataError.UNKNOWN
}

/** The [DataError] behind a failed [Result] from a repository. */
fun Throwable.dataError(): DataError = (this as? DataException)?.error ?: DataError.UNKNOWN

/**
 * Runs [block] and wraps any failure as a [DataException]. Cancellation is rethrown so
 * coroutine scopes keep working.
 */
// Repositories are the boundary where every backend/library failure becomes a DataError,
// so catching broadly here is deliberate.
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(DataException(e.toDataError(), e))
}

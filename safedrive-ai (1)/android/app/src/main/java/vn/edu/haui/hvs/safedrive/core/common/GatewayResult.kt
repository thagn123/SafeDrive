package vn.edu.haui.hvs.safedrive.core.common

/** Result wrapper every [vn.edu.haui.hvs.safedrive.domain.repository.SafeDriveGateway] call returns. */
sealed interface GatewayResult<out T> {
    data class Success<out T>(val data: T) : GatewayResult<T>
    data class Failure(val error: GatewayError) : GatewayResult<Nothing>
}

inline fun <T, R> GatewayResult<T>.map(transform: (T) -> R): GatewayResult<R> = when (this) {
    is GatewayResult.Success -> GatewayResult.Success(transform(data))
    is GatewayResult.Failure -> this
}

inline fun <T> GatewayResult<T>.onSuccess(action: (T) -> Unit): GatewayResult<T> {
    if (this is GatewayResult.Success) action(data)
    return this
}

inline fun <T> GatewayResult<T>.onFailure(action: (GatewayError) -> Unit): GatewayResult<T> {
    if (this is GatewayResult.Failure) action(error)
    return this
}

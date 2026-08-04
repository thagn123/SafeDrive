package vn.edu.haui.hvs.safedrive.domain.usecase

import vn.edu.haui.hvs.safedrive.core.common.GatewayResult
import vn.edu.haui.hvs.safedrive.core.common.IdGenerator
import vn.edu.haui.hvs.safedrive.core.model.ActionConfirmRequest
import vn.edu.haui.hvs.safedrive.core.model.ActionConfirmResult
import vn.edu.haui.hvs.safedrive.core.model.SafeDriveAction

/**
 * Confirms a [SafeDriveAction] that has `requiresConfirmation = true` after the user accepted the
 * confirmation dialog. Actions that don't require confirmation never call this — they run their
 * local effect (navigate/snackbar) directly (see docs/android-mvp-plan/04-screen-specs.md,
 * "Confirmation dialog").
 */
class ConfirmActionUseCase(
    private val sessionCoordinator: SessionCoordinator,
    private val idGenerator: IdGenerator,
) {
    suspend operator fun invoke(
        action: SafeDriveAction,
        confirmed: Boolean,
        contextVersion: Long,
    ): GatewayResult<ActionConfirmResult> {
        val sessionResult = sessionCoordinator.currentSession()
        if (sessionResult is GatewayResult.Failure) return sessionResult
        val resolved = (sessionResult as GatewayResult.Success).data
        val request = ActionConfirmRequest(
            sessionId = resolved.sessionId,
            actionId = action.id,
            actionType = action.type,
            confirmed = confirmed,
            confirmationId = idGenerator.next("conf"),
            contextVersion = contextVersion,
            hvacTargetTemperatureC = action.hvacTargetTemperatureC,
        )
        return resolved.gateway.confirmAction(request)
    }
}

package vn.edu.haui.hvs.safedrive.domain.usecase

import vn.edu.haui.hvs.safedrive.core.model.EmergencyState

/**
 * The microphone check belongs only to the explicit occupant-response window. Sensor verification
 * must stay quiet, the final countdown must not be reset by a late listening session, and terminal
 * states must never reopen capture.
 */
internal fun shouldStartProactiveOccupantCheck(state: EmergencyState?): Boolean =
    state == EmergencyState.AWAITING_USER_RESPONSE

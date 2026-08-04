package vn.edu.haui.hvs.safedrive.domain.repository

/**
 * Single seam that picks Demo vs Remote [SafeDriveGateway]. Use cases call [current] instead of
 * holding a fixed gateway reference, so switching backend mode/BASE_URL at runtime never requires
 * `if (demoMode)` branching in a composable or ViewModel (see docs/android-mvp-plan/02-android-architecture.md).
 *
 * [forPreferences] resolves the gateway for an *already-captured* [AppPreferences] snapshot instead
 * of re-reading the live preferences flow. [SessionCoordinator] uses this so the gateway a session was
 * started against and the gateway a follow-up call (query/event/action) is sent to are guaranteed to
 * come from the same snapshot — [current] alone cannot guarantee that, since two independent calls to
 * it a few statements apart could each observe a different preferences value if the user changes
 * backend mode/BASE_URL in between. Defaults to [current] so existing test fakes that only implement
 * the single-method interface keep compiling unmodified; the real implementation in
 * `SafeDriveContainer` overrides it to resolve directly from the passed snapshot.
 */
interface GatewayProvider {
    fun current(): SafeDriveGateway

    fun forPreferences(prefs: AppPreferences): SafeDriveGateway = current()
}

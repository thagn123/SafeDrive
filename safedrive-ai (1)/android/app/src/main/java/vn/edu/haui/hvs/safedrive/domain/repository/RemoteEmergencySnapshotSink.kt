package vn.edu.haui.hvs.safedrive.domain.repository

import vn.edu.haui.hvs.safedrive.core.model.EmergencySnapshot

/** Receives the backend-authoritative emergency snapshot emitted with a Remote state update. */
interface RemoteEmergencySnapshotSink {
    fun publishRemoteSnapshot(snapshot: EmergencySnapshot?)
}

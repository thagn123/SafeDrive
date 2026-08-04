package vn.edu.haui.hvs.safedrive

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SafeDriveApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var container: SafeDriveContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = SafeDriveContainer(this, applicationScope)
    }
}

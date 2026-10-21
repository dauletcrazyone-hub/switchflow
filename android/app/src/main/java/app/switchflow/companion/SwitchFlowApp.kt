package app.switchflow.companion

import android.app.Application
import app.switchflow.companion.core.CompanionController

class SwitchFlowApp : Application() {
    lateinit var controller: CompanionController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = CompanionController(this)
        controller.start()
    }
}

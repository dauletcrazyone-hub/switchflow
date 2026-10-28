package app.switchflow.companion

import android.app.Application
import app.switchflow.companion.core.CompanionController
import app.switchflow.companion.direct.DirectController

class SwitchFlowApp : Application() {
    lateinit var controller: CompanionController
        private set
    lateinit var direct: DirectController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = CompanionController(this)
        direct = DirectController(this)
        controller.start()
    }
}

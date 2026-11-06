package app.switchflow.companion

import android.app.Application
import app.switchflow.companion.billing.ProStore
import app.switchflow.companion.billing.ProStores
import app.switchflow.companion.core.CompanionController
import app.switchflow.companion.direct.DirectController

class SwitchFlowApp : Application() {
    lateinit var controller: CompanionController
        private set
    lateinit var direct: DirectController
        private set
    lateinit var pro: ProStore
        private set

    override fun onCreate() {
        super.onCreate()
        pro = ProStores.create(this).also { it.start() }
        controller = CompanionController(this)
        direct = DirectController(this, pro)
        controller.start()
    }
}

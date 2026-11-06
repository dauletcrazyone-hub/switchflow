package app.switchflow.companion.billing

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Full build: everything is unlocked; there is nothing to buy. */
object ProStores {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): ProStore = object : ProStore {
        override val pro: StateFlow<Boolean> = MutableStateFlow(true)
        override val price: StateFlow<String?> = MutableStateFlow(null)
        override val busy: StateFlow<Boolean> = MutableStateFlow(false)
        override fun start() {}
        override fun buy(activity: Activity) {}
        override fun restore() {}
    }
}

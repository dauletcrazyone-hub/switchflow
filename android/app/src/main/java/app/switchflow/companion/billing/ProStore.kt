package app.switchflow.companion.billing

import android.app.Activity
import app.switchflow.companion.net.Category
import kotlinx.coroutines.flow.StateFlow

/**
 * "SwitchFlow Pro": the paid unlock of the Google Play build. The full build (PC installer,
 * website) has everything unlocked; its store is [ProStores.create] in src/full.
 */
interface ProStore {
    /** True once Pro is bought (or always, in the full build). */
    val pro: StateFlow<Boolean>
    /** The store's localized price ("2 490 ₸"), or null until the store answered. */
    val price: StateFlow<String?>
    /** Set while a purchase or restore talks to the store; shown as a spinner. */
    val busy: StateFlow<Boolean>
    fun start()
    fun buy(activity: Activity)
    /** Asks the store again for earlier purchases (new phone, reinstall). */
    fun restore()
}

/**
 * What the free version sends from phone to phone: contacts, calendars and a first look at
 * photos and videos. Receiving is always free: the person getting the data never has to pay.
 */
object FreeTier {
    const val MEDIA_LIMIT = 50
    private val FREE = setOf(Category.CONTACTS, Category.CALENDARS, Category.PHOTOS, Category.VIDEOS)

    fun isFree(category: String) = category in FREE

    /** Whether sending these categories with these item counts needs Pro. */
    fun needsPro(chosen: Map<String, Long>): Boolean {
        if (chosen.keys.any { !isFree(it) }) return true
        val media = (chosen[Category.PHOTOS] ?: 0) + (chosen[Category.VIDEOS] ?: 0)
        return media > MEDIA_LIMIT
    }
}

package app.switchflow.companion.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.switchflow.companion.BuildConfig
import app.switchflow.companion.data.DeviceNames
import app.switchflow.companion.net.Category
import app.switchflow.companion.net.DeviceInfo
import app.switchflow.companion.net.Heartbeat
import app.switchflow.companion.net.Permission
import java.util.UUID

/** Saved pairing: enough to reconnect after the app restarts while the PC keeps running. */
data class SavedPairing(val host: String, val port: Int, val fingerprint: String, val token: String, val desktopName: String)

class Store(context: Context) {
    // App-private storage, excluded from cloud backup and device transfer (data_extraction_rules.xml).
    private val prefs = context.getSharedPreferences("switchflow", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }

    /** Last command sequence handled, so a restarted app does not replay old commands. */
    var lastSeq: Long
        get() = prefs.getLong("last_seq", 0)
        set(v) = prefs.edit().putLong("last_seq", v).apply()

    /** Whether the notification permission was already asked for once. */
    var notificationsAsked: Boolean
        get() = prefs.getBoolean("notifications_asked", false)
        set(v) = prefs.edit().putBoolean("notifications_asked", v).apply()

    var pairing: SavedPairing?
        get() {
            val host = prefs.getString("host", null) ?: return null
            return SavedPairing(
                host,
                prefs.getInt("port", 0),
                prefs.getString("fp", "")!!,
                prefs.getString("token", "")!!,
                prefs.getString("desktop", "PC")!!,
            )
        }
        set(v) {
            prefs.edit().apply {
                if (v == null) {
                    remove("host"); remove("port"); remove("fp"); remove("token"); remove("desktop")
                } else {
                    putString("host", v.host); putInt("port", v.port); putString("fp", v.fingerprint)
                    putString("token", v.token); putString("desktop", v.desktopName)
                }
            }.apply()
        }
}

object DeviceEnv {
    fun storage(): Pair<Long, Long> {
        val stat = StatFs(Environment.getDataDirectory().path)
        return stat.availableBytes to stat.totalBytes
    }

    fun battery(context: Context): Pair<Int?, Boolean?> {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return null to null
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        return level to bm.isCharging
    }

    fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    fun info(context: Context, id: String): DeviceInfo {
        val (free, total) = storage()
        val (battery, charging) = battery(context)
        return DeviceInfo(
            id = id,
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.titlecase() },
            // The real model, never the user-set name (a Galaxy A25 can be named "Galaxy s23").
            model = DeviceNames.marketingName(Build.MANUFACTURER, Build.MODEL),
            modelCode = Build.MODEL,
            osVersion = Build.VERSION.RELEASE,
            name = deviceName(context),
            appVersion = BuildConfig.VERSION_NAME,
            storageFreeBytes = free,
            storageTotalBytes = total,
            batteryPercent = battery,
            charging = charging,
        )
    }

    /** Changes every time the process starts. */
    val instanceId: String = UUID.randomUUID().toString()

    fun heartbeat(context: Context, foreground: Boolean): Heartbeat {
        val (free, total) = storage()
        val (battery, charging) = battery(context)
        return Heartbeat(free, total, battery, charging, if (foreground) "foreground" else "background", instanceId)
    }
}

/** PermissionManager, phone side: the runtime permissions each category needs. */
object CategoryPermissions {
    fun forReading(category: String): List<String> = when (category) {
        Category.PHOTOS -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES) else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        Category.VIDEOS -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_VIDEO) else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        Category.MUSIC -> if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_AUDIO) else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        Category.CONTACTS -> listOf(Manifest.permission.READ_CONTACTS)
        Category.CALENDARS -> listOf(Manifest.permission.READ_CALENDAR)
        else -> emptyList() // documents use the system file picker
    }

    /** Saving media to MediaStore needs no permission on Android 10+ (Android 9: storage); contacts and calendars do. */
    fun forWriting(category: String): List<String> = when (category) {
        Category.CONTACTS -> listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        Category.CALENDARS -> listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        else -> if (Build.VERSION.SDK_INT < 29) {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else emptyList()
    }

    fun granted(context: Context, perms: List<String>) =
        perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Current read state, including Android 14's "selected photos only" access. */
    fun readState(context: Context, category: String): String {
        if (granted(context, forReading(category))) return Permission.GRANTED
        if (Build.VERSION.SDK_INT >= 34 && (category == Category.PHOTOS || category == Category.VIDEOS) &&
            granted(context, listOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        ) return Permission.LIMITED
        return Permission.DENIED
    }

    /** Extra permissions asked together with reading photos/videos on Android 14+ so "Select photos" works. */
    fun requestSet(category: String): List<String> {
        val base = forReading(category)
        return if (Build.VERSION.SDK_INT >= 34 && (category == Category.PHOTOS || category == Category.VIDEOS)) {
            base + Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        } else base
    }
}

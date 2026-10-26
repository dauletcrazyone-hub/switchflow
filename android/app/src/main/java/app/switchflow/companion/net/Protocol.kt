package app.switchflow.companion.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

// Wire types of docs/PROTOCOL.md. Keep in sync with desktop/src-tauri/src/model.rs.

val ProtocolJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

object Category {
    const val PHOTOS = "photos"
    const val VIDEOS = "videos"
    const val CONTACTS = "contacts"
    const val DOCUMENTS = "documents"
    const val MUSIC = "music"
    const val CALENDARS = "calendars"
    val ALL = listOf(PHOTOS, VIDEOS, CONTACTS, DOCUMENTS, MUSIC, CALENDARS)
}

object Permission {
    const val GRANTED = "granted"
    const val LIMITED = "limited"
    const val DENIED = "denied"
    const val UNAVAILABLE = "unavailable"
}

@Serializable
data class DeviceInfo(
    val id: String,
    val platform: String = "android",
    val manufacturer: String,
    /** Marketing name ("Galaxy A25 5G"); [name] is whatever the user called the phone. */
    val model: String,
    val modelCode: String? = null,
    val osVersion: String,
    val name: String,
    val appVersion: String,
    val storageFreeBytes: Long,
    val storageTotalBytes: Long,
    val batteryPercent: Int?,
    val charging: Boolean?,
)

@Serializable
data class PairRequest(val secret: String, val device: DeviceInfo)

@Serializable
data class PairResponse(val pairingId: String, val verificationCode: String, val expiresInSec: Long = 120)

@Serializable
data class PairStatus(val status: String, val token: String? = null, val desktopName: String? = null)

@Serializable
data class Heartbeat(
    val storageFreeBytes: Long,
    val storageTotalBytes: Long,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val appState: String,
    /** Random per process: lets the PC notice that the app was restarted and resend work. */
    val instanceId: String? = null,
)

@Serializable
data class HeartbeatResponse(val role: String = "none")

@Serializable
data class Command(val seq: Long, val type: String, val payload: JsonObject = JsonObject(emptyMap()))

@Serializable
data class CommandsResponse(val commands: List<Command> = emptyList())

@Serializable
data class SourceItem(
    val id: String,
    val category: String,
    val name: String,
    val size: Long,
    val sizeExact: Boolean = true,
    val mime: String = "",
    val createdMs: Long? = null,
    val modifiedMs: Long? = null,
    val albumIds: List<String> = emptyList(),
    val itemCount: Int? = null,
)

@Serializable
data class Album(val id: String, val name: String, val count: Long, val totalBytes: Long)

/** One contact in the exported vCard, so the PC can offer a pick list. `id` is the card's position. */
@Serializable
data class Entry(val id: String, val name: String, val detail: String = "")

@Serializable
data class CategoryInventory(
    val category: String,
    val permission: String,
    val count: Long,
    val totalBytes: Long,
    val albums: List<Album> = emptyList(),
    val items: List<SourceItem> = emptyList(),
    val note: String? = null,
    val entries: List<Entry> = emptyList(),
)

@Serializable
data class InventoryBody(val categories: List<CategoryInventory>)

@Serializable
data class PrepareResult(
    val sessionId: String,
    val ok: Boolean,
    val permissions: Map<String, String>,
    val storageFreeBytes: Long,
    val error: String? = null,
)

@Serializable
data class UploadStatus(val receivedBytes: Long, val state: String)

@Serializable
data class CompleteBody(val size: Long, val sha256: String)

@Serializable
data class FailBody(val error: String)

@Serializable
data class DeliveryItem(
    val key: String,
    val category: String,
    val name: String,
    val size: Long,
    val sha256: String,
    val mime: String = "",
    val createdMs: Long? = null,
    val modifiedMs: Long? = null,
    val albumName: String? = null,
    val itemCount: Int? = null,
    val contactsPolicy: String = "merge",
)

@Serializable
data class NextResponse(val state: String, val item: DeliveryItem? = null)

@Serializable
data class AckDetail(val imported: Long = 0, val merged: Long = 0, val skipped: Long = 0)

@Serializable
data class AckBody(
    val result: String,
    val sha256: String? = null,
    val verification: String? = null,
    val error: String? = null,
    val detail: AckDetail? = null,
)

@Serializable
data class UploadEntry(val key: String, val sourceId: String)

@Serializable
data class UploadCommand(val sessionId: String, val items: List<UploadEntry>)

@Serializable
data class PrepareCommand(
    val sessionId: String,
    val categories: List<String>,
    val totalBytes: Long,
    val contactsPolicy: String = "merge",
)

@Serializable
data class SessionCommand(val sessionId: String)

@Serializable
data class ScanCommand(val categories: List<String>)

@Serializable
data class RoleCommand(val role: String)

@Serializable
data class ErrorBody(val error: String = "", val message: String = "", @SerialName("receivedBytes") val receivedBytes: Long? = null)

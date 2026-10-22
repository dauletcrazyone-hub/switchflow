package app.switchflow.companion.core

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.net.Uri
import app.switchflow.companion.net.ApiClient
import app.switchflow.companion.net.ApiException
import app.switchflow.companion.net.CableLink
import app.switchflow.companion.net.Category
import app.switchflow.companion.net.Command
import app.switchflow.companion.net.InventoryBody
import app.switchflow.companion.net.PairRequest
import app.switchflow.companion.net.PairingTarget
import app.switchflow.companion.net.PairingUri
import app.switchflow.companion.net.Permission
import app.switchflow.companion.net.PrepareCommand
import app.switchflow.companion.net.PrepareResult
import app.switchflow.companion.net.ProtocolJson
import app.switchflow.companion.net.RoleCommand
import app.switchflow.companion.net.ScanCommand
import app.switchflow.companion.net.SessionCommand
import app.switchflow.companion.net.UploadCommand
import app.switchflow.companion.net.UploadEntry
import app.switchflow.companion.transfer.Receiver
import app.switchflow.companion.transfer.SessionControls
import app.switchflow.companion.transfer.SourceCatalog
import app.switchflow.companion.transfer.TransferService
import app.switchflow.companion.transfer.Uploader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.IOException

enum class Phase { Idle, Connecting, AwaitingApproval, Connected, Reconnecting }

data class TransferUi(
    val sessionId: String,
    val sending: Boolean,
    val itemsDone: Int = 0,
    val itemsTotal: Int = 0,
    val bytes: Long = 0,
    val currentName: String? = null,
    val paused: Boolean = false,
    val finished: String? = null, // "done" | "cancelled"
)

/** A runtime-permission prompt the Activity must show. */
data class PermissionAsk(val permissions: List<String>, val categories: List<String>, val write: Boolean, val done: CompletableDeferred<Unit>)

data class UiState(
    val phase: Phase = Phase.Idle,
    val desktopName: String? = null,
    val verificationCode: String? = null,
    val role: String = "none",
    val message: Message? = null,
    val transfer: TransferUi? = null,
    val permissionAsk: PermissionAsk? = null,
    val documentsAsk: CompletableDeferred<List<Uri>>? = null,
    val viaCable: Boolean = false,
)

enum class Message { BadQr, Unreachable, Denied, Expired, Forgotten }

/**
 * The companion's brain: pairing, heartbeat, the PC's command queue and the
 * per-session upload/download workers. UI observes [state]; nothing here touches views.
 */
private const val LOOPBACK = "127.0.0.1"

class CompanionController(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = Store(app)
    private val catalog = SourceCatalog(app)
    private val controls = SessionControls()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile private var api: ApiClient? = null
    private var connection: Job? = null
    private val uploadQueues = mutableMapOf<String, Channel<UploadEntry>>()
    private val sessionJobs = mutableMapOf<String, Job>()
    private val itemNames = mutableMapOf<String, String>()
    /** Keys waiting in an upload queue; the PC may resend the list after a pause or reconnect. */
    private val queuedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile var foreground = true

    fun start() {
        store.pairing?.let { connect(it) }
    }

    // ------------------------------------------------------------- pairing

    fun onQrScanned(raw: String) {
        val target = PairingUri.parse(raw)
        if (target == null) {
            _state.update { it.copy(message = Message.BadQr) }
            return
        }
        scope.launch { pair(target) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private suspend fun findReachable(t: PairingTarget): Pair<ApiClient, String>? = coroutineScope {
        // Try every address at once; keep the QR's priority order among those that answer.
        val results = t.hosts.map { host -> async { host.takeIf { ApiClient(host, t.port, t.fingerprint).ping() } } }.awaitAll()
        val host = results.firstOrNull { it != null } ?: return@coroutineScope null
        ApiClient(host, t.port, t.fingerprint) to host
    }

    private suspend fun pair(t: PairingTarget) {
        _state.update { UiState(phase = Phase.Connecting, desktopName = t.desktopName) }
        val (client, host) = findReachable(t) ?: run {
            _state.update { UiState(message = Message.Unreachable) }
            return
        }
        try {
            val resp = client.pair(PairRequest(t.secret, DeviceEnv.info(app, store.deviceId)))
            _state.update { it.copy(phase = Phase.AwaitingApproval, verificationCode = resp.verificationCode) }
            repeat(150) {
                delay(1000)
                val s = client.pairStatus(resp.pairingId)
                when (s.status) {
                    "approved" -> {
                        val saved = SavedPairing(host, t.port, t.fingerprint, s.token ?: return@repeat, s.desktopName ?: t.desktopName)
                        store.pairing = saved
                        store.lastSeq = 0
                        connect(saved)
                        return
                    }
                    "denied" -> { _state.update { UiState(message = Message.Denied) }; return }
                    "expired" -> { _state.update { UiState(message = Message.Expired) }; return }
                }
            }
            _state.update { UiState(message = Message.Expired) }
        } catch (e: ApiException) {
            _state.update { UiState(message = if (e.status == 403) Message.BadQr else Message.Unreachable) }
        } catch (e: IOException) {
            _state.update { UiState(message = Message.Unreachable) }
        }
    }

    // --------------------------------------------------------------- cable

    private var cable: CableLink? = null
    /** Where the PC is reachable without the cable (from the cable's HELLO), to fall back on unplug. */
    private var cableHosts: List<String> = emptyList()
    private var cableServerPort = 0

    private fun SavedPairing.viaCable() = host == LOOPBACK && port == CableLink.LOCAL_PORT

    /** The PC switched the phone into USB accessory mode and the user let SwitchFlow open it. */
    fun onAccessory(accessory: UsbAccessory) {
        if (cable?.isOpen == true) return
        cable = CableLink.open(
            app, accessory,
            onHello = { uri -> scope.launch { onCableHello(uri) } },
            onClosed = { scope.launch { onCableClosed() } },
        )
    }

    /** The cable carries what the QR code carries: pair without the camera, or reuse the pairing. */
    private suspend fun onCableHello(raw: String) {
        val t = PairingUri.parse(raw) ?: return
        cableHosts = t.hosts.filter { it != LOOPBACK }
        cableServerPort = t.port
        val saved = store.pairing
        if (saved != null && saved.fingerprint == t.fingerprint) {
            val p = saved.copy(host = LOOPBACK, port = CableLink.LOCAL_PORT)
            store.pairing = p
            connect(p)
        } else {
            pair(t.copy(hosts = listOf(LOOPBACK), port = CableLink.LOCAL_PORT))
        }
    }

    /** Unplugged: carry on over Wi-Fi when the PC is reachable there; otherwise wait for the cable. */
    private suspend fun onCableClosed() {
        cable = null
        val saved = store.pairing ?: return
        if (!saved.viaCable() || cableHosts.isEmpty()) return
        val target = PairingTarget(cableHosts, cableServerPort, saved.fingerprint, "", saved.desktopName)
        val (_, host) = findReachable(target) ?: return
        val p = saved.copy(host = host, port = cableServerPort)
        store.pairing = p
        connect(p)
    }

    // ---------------------------------------------------------- connection

    private fun connect(p: SavedPairing) {
        val client = ApiClient(p.host, p.port, p.fingerprint).also { it.token = p.token }
        api = client
        _state.update { it.copy(phase = Phase.Connected, desktopName = p.desktopName, verificationCode = null, viaCable = p.host == "127.0.0.1") }
        TransferService.start(app)
        connection?.cancel()
        connection = scope.launch {
            launch { heartbeatLoop(client) }
            launch { commandLoop(client) }
        }
    }

    private suspend fun heartbeatLoop(client: ApiClient) {
        while (scope.isActive) {
            try {
                val r = client.heartbeat(DeviceEnv.heartbeat(app, foreground))
                _state.update { it.copy(phase = Phase.Connected) }
                onRole(r.role)
            } catch (e: ApiException) {
                if (e.status == 401) return forget(Message.Forgotten)
            } catch (e: IOException) {
                _state.update { it.copy(phase = Phase.Reconnecting) }
            }
            delay(5000)
        }
    }

    private suspend fun commandLoop(client: ApiClient) {
        var after = store.lastSeq
        while (scope.isActive) {
            try {
                val r = client.commands(after)
                for (c in r.commands) {
                    after = maxOf(after, c.seq)
                    store.lastSeq = after
                    handle(c)
                }
            } catch (e: ApiException) {
                if (e.status == 401) return forget(Message.Forgotten)
                delay(2000)
            } catch (e: IOException) {
                _state.update { it.copy(phase = Phase.Reconnecting) }
                delay(2000)
            }
        }
    }

    private inline fun <reified T> decode(payload: JsonObject): T =
        ProtocolJson.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), payload)

    private fun handle(c: Command) {
        when (c.type) {
            "role" -> onRole(decode<RoleCommand>(c.payload).role)
            "scan" -> scope.launch { scan(decode<ScanCommand>(c.payload).categories) }
            "prepare" -> scope.launch { prepare(decode(c.payload)) }
            "upload" -> enqueueUploads(decode(c.payload))
            "download" -> startReceiving(decode<SessionCommand>(c.payload).sessionId)
            "pause" -> { controls.paused.value = true; _state.update { s -> s.copy(transfer = s.transfer?.copy(paused = true)) } }
            "resume" -> { controls.paused.value = false; _state.update { s -> s.copy(transfer = s.transfer?.copy(paused = false)) } }
            "cancel" -> cancelSession(decode<SessionCommand>(c.payload).sessionId, "cancelled")
            "unpair" -> scope.launch { forget(null) }
        }
    }

    // ------------------------------------------------------- permissions

    /** One prompt at a time: a second request waits instead of replacing the card on screen. */
    private val askLock = Mutex()

    private suspend fun ask(perms: List<String>, categories: List<String>, write: Boolean) = askLock.withLock {
        // Disconnected while this request waited its turn: do not bother the user.
        if (api == null) return@withLock
        // Re-check inside the lock: an earlier prompt may already have granted these.
        val missing = perms.distinct().filter { !CategoryPermissions.granted(app, listOf(it)) }
        if (missing.isEmpty()) return@withLock
        val done = CompletableDeferred<Unit>()
        _state.update { it.copy(permissionAsk = PermissionAsk(missing, categories, write, done)) }
        if (!foreground) TransferService.notifyAttention(app)
        done.await()
        _state.update { it.copy(permissionAsk = null) }
    }

    /** Called by the Activity after the system permission dialog closes. */
    fun onPermissionsAnswered() {
        _state.value.permissionAsk?.done?.complete(Unit)
    }

    fun onDocumentsPicked(uris: List<Uri>) {
        _state.value.documentsAsk?.complete(uris)
    }

    /**
     * Only the source phone picks documents. A pick still waiting when the phone becomes the
     * receiver (direction swapped on the PC) is dropped instead of keeping a stale card on screen.
     */
    private fun onRole(role: String) {
        _state.update { it.copy(role = role) }
        if (role == "target") _state.value.documentsAsk?.complete(emptyList())
    }

    // -------------------------------------------------------------- source

    /** Scans run one after another so prompts and the document picker never overlap. */
    private val scanLock = Mutex()

    private suspend fun scan(categories: List<String>): Unit = scanLock.withLock { scanLocked(categories) }

    private suspend fun scanLocked(categories: List<String>) {
        val client = api ?: return
        val cats = categories.filter { it in Category.ALL }
        ask(cats.flatMap { CategoryPermissions.requestSet(it) }, cats, write = false)
        if (Category.DOCUMENTS in cats && _state.value.role != "target") {
            val pick = CompletableDeferred<List<Uri>>()
            _state.update { it.copy(documentsAsk = pick) }
            if (!foreground) TransferService.notifyAttention(app)
            catalog.pickedDocuments = pick.await()
            _state.update { it.copy(documentsAsk = null) }
        }
        val inventory = withContext(Dispatchers.IO) {
            cats.map { cat -> catalog.scan(cat, CategoryPermissions.readState(app, cat)) }
        }
        inventory.forEach { inv -> inv.items.forEach { itemNames[it.id] = it.name } }
        runCatching { client.inventory(InventoryBody(inventory)) }
    }

    private fun enqueueUploads(cmd: UploadCommand) {
        val client = api ?: return
        val queue = synchronized(uploadQueues) {
            uploadQueues.getOrPut(cmd.sessionId) {
                Channel<UploadEntry>(Channel.UNLIMITED).also { ch -> startUploading(client, cmd.sessionId, ch) }
            }
        }
        val fresh = cmd.items.filter { queuedKeys.add("${cmd.sessionId}/${it.key}") }
        _state.update { s ->
            val t = s.transfer?.takeIf { it.sessionId == cmd.sessionId && it.sending } ?: TransferUi(cmd.sessionId, sending = true)
            s.copy(transfer = t.copy(itemsTotal = t.itemsTotal + fresh.size, finished = null))
        }
        fresh.forEach { queue.trySend(it) }
    }

    private fun startUploading(client: ApiClient, sid: String, queue: Channel<UploadEntry>) {
        controls.paused.value = false
        val uploader = Uploader(
            client, catalog, controls,
            onProgress = { n, name -> _state.update { s -> s.copy(transfer = s.transfer?.copy(bytes = s.transfer.bytes + n, currentName = name)) } },
            onItemDone = { _state.update { s -> s.copy(transfer = s.transfer?.copy(itemsDone = s.transfer.itemsDone + 1)) } },
        )
        sessionJobs["up:$sid"] = scope.launch(Dispatchers.IO) {
            for (entry in queue) {
                try {
                    uploader.upload(sid, entry, itemNames[entry.sourceId])
                } catch (e: ApiException) {
                    if (e.status == 404 || e.status == 410) break
                } catch (e: IOException) {
                    // Lost the PC for a long time: the PC re-sends the upload list after reconnecting.
                    break
                } finally {
                    queuedKeys.remove("$sid/${entry.key}")
                }
                val t = _state.value.transfer
                if (t != null && t.itemsDone >= t.itemsTotal) {
                    _state.update { s -> s.copy(transfer = s.transfer?.copy(finished = "done", currentName = null)) }
                }
            }
            synchronized(uploadQueues) { uploadQueues.remove(sid) }
            queuedKeys.removeIf { it.startsWith("$sid/") }
        }
    }

    // -------------------------------------------------------------- target

    private suspend fun prepare(cmd: PrepareCommand) {
        val client = api ?: return
        val cats = cmd.categories.filter { it in Category.ALL }
        ask(cats.flatMap { CategoryPermissions.forWriting(it) }, cats, write = true)
        val permissions = cats.associateWith { cat ->
            if (CategoryPermissions.granted(app, CategoryPermissions.forWriting(cat))) Permission.GRANTED else Permission.DENIED
        }
        val (free, _) = DeviceEnv.storage()
        runCatching {
            client.prepareResult(PrepareResult(cmd.sessionId, permissions.values.all { it == Permission.GRANTED }, permissions, free))
        }
    }

    private fun startReceiving(sid: String) {
        val client = api ?: return
        if (sessionJobs["down:$sid"]?.isActive == true) return
        controls.paused.value = false
        _state.update { it.copy(transfer = TransferUi(sid, sending = false)) }
        val receiver = Receiver(
            app, client, controls,
            onProgress = { n, name -> _state.update { s -> s.copy(transfer = s.transfer?.copy(bytes = s.transfer.bytes + n, currentName = name)) } },
            onItemDone = { _ -> _state.update { s -> s.copy(transfer = s.transfer?.copy(itemsDone = s.transfer.itemsDone + 1)) } },
        )
        sessionJobs["down:$sid"] = scope.launch(Dispatchers.IO) {
            val end = runCatching { receiver.run(sid) }.getOrDefault("cancelled")
            _state.update { s -> s.copy(transfer = s.transfer?.copy(finished = end, currentName = null)) }
        }
    }

    private fun cancelSession(sid: String, reason: String) {
        sessionJobs.remove("up:$sid")?.cancel()
        sessionJobs.remove("down:$sid")?.cancel()
        synchronized(uploadQueues) { uploadQueues.remove(sid)?.close() }
        catalog.clearExports()
        _state.update { s -> s.copy(transfer = s.transfer?.copy(finished = reason, currentName = null)) }
    }

    // -------------------------------------------------------------- ending

    /** The user disconnects on the phone. */
    fun disconnect() {
        scope.launch { forget(null) }
    }

    private fun forget(message: Message?) {
        connection?.cancel()
        sessionJobs.values.forEach { it.cancel() }
        sessionJobs.clear()
        synchronized(uploadQueues) { uploadQueues.values.forEach { it.close() }; uploadQueues.clear() }
        catalog.clearExports()
        catalog.pickedDocuments.forEach { uri ->
            runCatching { app.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        catalog.pickedDocuments = emptyList()
        api = null
        store.pairing = null
        store.lastSeq = 0
        _state.value.permissionAsk?.done?.complete(Unit)
        _state.value.documentsAsk?.complete(emptyList())
        _state.value = UiState(message = message)
        TransferService.stop(app)
    }
}

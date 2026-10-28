package app.switchflow.companion.direct

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import app.switchflow.companion.BuildConfig
import app.switchflow.companion.R
import app.switchflow.companion.core.CategoryPermissions
import app.switchflow.companion.core.DeviceEnv
import app.switchflow.companion.core.PermissionAsk
import app.switchflow.companion.core.Store
import app.switchflow.companion.data.AppInbox
import app.switchflow.companion.data.AppsRepo
import app.switchflow.companion.data.DeviceNames
import app.switchflow.companion.data.InboxApp
import app.switchflow.companion.data.InstalledApp
import app.switchflow.companion.net.Category
import app.switchflow.companion.net.CategoryInventory
import app.switchflow.companion.net.Permission
import app.switchflow.companion.transfer.SourceCatalog
import app.switchflow.companion.transfer.TransferService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class DirectStage {
    /** Nothing phone-to-phone going on. */
    Idle,
    /** Receiver shows its QR code and waits. */
    Waiting,
    Connecting,
    /** Linked by cable, neither phone chose a direction yet. */
    Undecided,
    /** Sender picks what to send; the receiver waits for that. */
    Choosing,
    Transferring,
    Reconnecting,
    Finished,
    Failed,
}

data class CategoryRow(
    val category: String,
    val count: Long,
    val bytes: Long,
    val permission: String,
    val selected: Boolean,
)

data class DirectProgress(
    val itemsDone: Int = 0,
    val itemsTotal: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val current: String? = null,
    val currentCategory: String? = null,
    val failed: Int = 0,
    val bytesPerSec: Long = 0,
)

data class InstallRow(val app: InboxApp, val state: String = "ready", val error: String? = null)

/** An app that was offered but not sent, and why ("installed", "old_android", "invalid"). */
data class NotSent(val label: String, val reason: String)

data class DirectUi(
    val stage: DirectStage = DirectStage.Idle,
    /** "send" or "receive" once known. */
    val role: String? = null,
    val qr: String? = null,
    val peer: PeerDevice? = null,
    val viaCable: Boolean = false,
    val categories: List<CategoryRow> = emptyList(),
    val scanning: Boolean = false,
    val needsReadAccess: Boolean = false,
    /** Null until loaded; empty when the receiver is an iPhone (apps cannot go there). */
    val apps: List<InstalledApp>? = null,
    val appsAllowed: Boolean = true,
    val selectedApps: Set<String> = emptySet(),
    val progress: DirectProgress = DirectProgress(),
    val result: Finish? = null,
    val error: String? = null,
    val inbox: List<InstallRow> = emptyList(),
    val canInstall: Boolean = true,
    val permissionAsk: PermissionAsk? = null,
    /** Host side: a phone is on the cable and Android asked the user to allow access to it. */
    val usbWaiting: Boolean = false,
    /** The photo/video grid is open for this category. */
    val picking: String? = null,
    /** Hand-picked items per category (source ids); a category missing here sends everything. */
    val picked: Map<String, Set<String>> = emptyMap(),
    val pickedBytes: Map<String, Long> = emptyMap(),
    /** Apps the receiver did not need or cannot run; shown on the finish screen of both phones. */
    val notSent: List<NotSent> = emptyList(),
) {
    fun chosenCount(row: CategoryRow): Long = picked[row.category]?.size?.toLong() ?: row.count
    fun chosenBytes(row: CategoryRow): Long = pickedBytes[row.category] ?: row.bytes
    val selectedBytes: Long get() = categories.filter { it.selected }.sumOf { chosenBytes(it) } + (apps.orEmpty().filter { it.pkg in selectedApps }.sumOf { it.size })
    val selectedCount: Long get() = categories.filter { it.selected }.sumOf { chosenCount(it) } + selectedApps.size
}

/**
 * Phone-to-phone transfer (docs/DIRECT.md): QR + Wi-Fi in both directions between Android and iPhone,
 * and a Type-C cable between two Androids. UI observes [state]; the work runs on IO threads.
 */
class DirectController(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = Store(app)
    private val catalog = SourceCatalog(app)
    private val appsRepo = AppsRepo(app)
    private val inbox = AppInbox(app)
    private val _state = MutableStateFlow(DirectUi(inbox = inbox.list().map { InstallRow(it) }))
    val state: StateFlow<DirectUi> = _state.asStateFlow()

    @Volatile private var listener: WifiTransport.Listener? = null
    @Volatile private var peer: DirectPeer? = null
    @Volatile private var isClient = false
    @Volatile private var target: DirectTarget? = null
    @Volatile private var sender: DirectSender? = null
    private var sendJob: Job? = null
    /** A replacement link for a send that lost its connection (cable re-plugged, or receiver side). */
    @Volatile private var relink: CompletableDeferred<DirectPeer>? = null
    private val receiveStates = ConcurrentHashMap<String, ReceiveState>()
    @Volatile private var receiver: DirectReceiver? = null
    private var inventories: Map<String, CategoryInventory> = emptyMap()
    private val wakeLocks = TransferLocks(app)

    private fun me(intent: String) = Hello(
        device = PeerDevice(
            id = store.deviceId,
            name = DeviceEnv.deviceName(app),
            model = DeviceNames.marketingName(Build.MANUFACTURER, Build.MODEL),
            platform = "android",
            os = Build.VERSION.RELEASE,
            app = BuildConfig.VERSION_NAME,
        ),
        intent = intent,
    )

    private fun myIntent() = when (_state.value.role) {
        DirectIntent.SEND -> DirectIntent.SEND
        DirectIntent.RECEIVE -> DirectIntent.RECEIVE
        else -> DirectIntent.ANY
    }

    // ------------------------------------------------------------ receive

    /** "Receive" tapped: listen on Wi-Fi and show the QR code. A cable works too. */
    fun startReceive() {
        reset()
        val key = DirectCrypto.randomBytes(DirectCrypto.KEY_BYTES)
        val l = try {
            WifiTransport.Listener(key) { link -> scope.launch(Dispatchers.IO) { onLink(link) } }
        } catch (e: IOException) {
            _state.update { it.copy(stage = DirectStage.Failed, error = "listen_failed") }
            return
        }
        listener = l
        val hosts = WifiTransport.localAddresses()
        val qr = if (hosts.isEmpty()) null else DirectQr.build(DirectTarget(hosts, l.port, key, DeviceEnv.deviceName(app), "android"))
        _state.update { it.copy(stage = DirectStage.Waiting, role = DirectIntent.RECEIVE, qr = qr, error = if (qr == null) "no_network" else null) }
    }

    /** Wi-Fi came up after the receive screen opened: show a code with the new address. */
    fun refreshQr() {
        if (_state.value.stage == DirectStage.Waiting) startReceive()
    }

    private fun becomeReceiver(p: DirectPeer) {
        val r = DirectReceiver(
            p, AndroidSink(app) { cats -> runBlocking { askWrite(cats) } }, receiveListener,
            stateFor = { sid -> receiveStates.getOrPut(sid) { ReceiveState(sid) } },
            onFinished = { f -> onReceiveFinished(f) },
            onCancelled = { _ -> fail("peer_cancelled") },
        )
        receiver = r
        r.attach()
        p.onLost = { onReceiverLost(p) }
        _state.update { it.copy(stage = DirectStage.Choosing, role = DirectIntent.RECEIVE, peer = p.hello.device, viaCable = isCable(p)) }
    }

    private val cablePeers = java.util.Collections.newSetFromMap(java.util.WeakHashMap<DirectPeer, Boolean>())

    private fun isCable(p: DirectPeer) = p in cablePeers

    /** The sender reconnects by itself over Wi-Fi; on the cable the user plugs it back in. */
    private fun onReceiverLost(p: DirectPeer) {
        if (peer !== p) return
        receiver?.abortCurrent()
        val s = _state.value.stage
        if (s == DirectStage.Transferring) {
            _state.update { it.copy(stage = DirectStage.Reconnecting) }
            scope.launch {
                delay(RECONNECT_MS)
                if (peer === p && _state.value.stage == DirectStage.Reconnecting) fail("lost")
            }
        } else if (s == DirectStage.Choosing || s == DirectStage.Undecided) {
            peer = null
            if (listener != null) {
                // Still showing a code: wait for the sender to scan again.
                _state.update { it.copy(stage = DirectStage.Waiting, peer = null) }
            } else {
                fail("lost")
            }
        }
    }

    private fun onReceiveFinished(f: Finish) {
        listener?.stop()
        listener = null
        wakeLocks.release()
        TransferService.stop(app)
        val received = inbox.list()
        _state.update {
            it.copy(
                stage = DirectStage.Finished, result = f, qr = null,
                progress = it.progress.copy(current = null),
                inbox = received.map { a -> InstallRow(a) }, canInstall = inbox.canInstall(),
            )
        }
        // Apps arrived: start installing right away (Android asks to confirm each one).
        if (received.isNotEmpty()) {
            if (inbox.canInstall()) installAll() else autoInstall = true
        }
    }

    /** Waiting for "Install unknown apps" to be allowed in Settings; install as soon as it is. */
    @Volatile private var autoInstall = false

    private val receiveListener = object : TransferListener {
        override fun onPlanned(all: List<OfferItem>, skip: Set<String>, why: Map<String, String>) = planned(all, skip, why)
        override fun onBytes(n: Long, item: OfferItem) = bytes(n, item)
        override fun onItemDone(item: OfferItem, ack: ItemAck) = itemDone(item, ack)
    }

    // --------------------------------------------------------------- send

    /** "Send" tapped: the UI opens the scanner; the cable works too. */
    fun startSend() {
        reset()
        _state.update { it.copy(stage = DirectStage.Waiting, role = DirectIntent.SEND) }
    }

    /** A phone's QR code was scanned. */
    fun connectTo(raw: String) {
        val t = DirectQr.parse(raw)
        if (t == null) {
            _state.update { it.copy(stage = DirectStage.Failed, role = DirectIntent.SEND, error = "bad_qr") }
            return
        }
        reset()
        target = t
        _state.update { it.copy(stage = DirectStage.Connecting, role = DirectIntent.SEND) }
        scope.launch(Dispatchers.IO) {
            val link = WifiTransport.connect(t)
            if (link == null) {
                fail("unreachable")
                return@launch
            }
            onLink(link)
        }
    }

    private fun becomeSender(p: DirectPeer) {
        p.onLost = {
            if (peer === p && _state.value.stage in setOf(DirectStage.Choosing, DirectStage.Undecided)) fail("lost")
        }
        p.handler = { r ->
            when (r.type) {
                // Both tapped "Send" on the cable at the same moment: the host keeps sending.
                DirectType.ROLE -> if (!isClient && r.decode(RoleMsg.serializer()).intent == DirectIntent.SEND) becomeReceiver(p)
                DirectType.CANCEL -> onPeerCancel(p, r)
            }
        }
        val peerIsAndroid = p.hello.device.platform == "android"
        _state.update {
            it.copy(
                stage = DirectStage.Choosing, role = DirectIntent.SEND, peer = p.hello.device, viaCable = isCable(p),
                appsAllowed = peerIsAndroid, apps = if (peerIsAndroid) it.apps else emptyList(),
            )
        }
        scope.launch(Dispatchers.IO) { scanAll() }
    }

    private val readable = listOf(Category.PHOTOS, Category.VIDEOS, Category.MUSIC, Category.CONTACTS, Category.CALENDARS)

    /** Counts what this phone can send. Categories without access are listed with zero. */
    private suspend fun scanAll() {
        _state.update { it.copy(scanning = true) }
        val missing = readable.filter { CategoryPermissions.readState(app, it) == Permission.DENIED }
        val inv = readable.associateWith { cat -> runCatching { catalog.scan(cat, CategoryPermissions.readState(app, cat)) }.getOrElse { CategoryInventory(cat, Permission.UNAVAILABLE, 0, 0) } }
        val docs = runCatching { catalog.scan(Category.DOCUMENTS, Permission.GRANTED) }.getOrElse { CategoryInventory(Category.DOCUMENTS, Permission.GRANTED, 0, 0) }
        inventories = inv + (Category.DOCUMENTS to docs)
        val prev = _state.value.categories.associateBy { it.category }
        val rows = DirectCategory.ORDER.filter { it in inventories }.map { c ->
            val i = inventories.getValue(c)
            CategoryRow(c, i.count, i.totalBytes, i.permission, selected = prev[c]?.selected ?: (i.count > 0 && c != Category.DOCUMENTS))
        }
        _state.update { it.copy(categories = rows, scanning = false, needsReadAccess = missing.isNotEmpty()) }
        if (_state.value.appsAllowed && _state.value.apps == null) {
            val apps = runCatching { appsRepo.list() }.getOrDefault(emptyList())
            _state.update { it.copy(apps = apps) }
        }
    }

    /** "Allow access" on the choose screen: one round of system dialogs, then count again. */
    fun requestReadAccess() {
        scope.launch {
            ask(readable.flatMap { CategoryPermissions.requestSet(it) }, readable, write = false)
            withIo { scanAll() }
        }
    }

    // ------------------------------------------------------- photo/video grid

    /** Everything listed in [category] and its albums, for the grid. */
    fun media(category: String): Pair<List<app.switchflow.companion.net.SourceItem>, List<app.switchflow.companion.net.Album>> {
        val inv = inventories[category] ?: return emptyList<app.switchflow.companion.net.SourceItem>() to emptyList()
        return inv.items to inv.albums
    }

    fun openPicker(category: String) = _state.update { it.copy(picking = category) }

    /** Grid closed with this choice; choosing everything is the same as no choice. */
    fun setPicked(category: String, ids: Set<String>) {
        val items = inventories[category]?.items.orEmpty()
        val all = ids.size == items.size
        val bytes = items.filter { it.id in ids }.sumOf { it.size }
        _state.update { s ->
            s.copy(
                picking = null,
                picked = if (all) s.picked - category else s.picked + (category to ids),
                pickedBytes = if (all) s.pickedBytes - category else s.pickedBytes + (category to bytes),
                categories = s.categories.map { if (it.category == category) it.copy(selected = ids.isNotEmpty()) else it },
            )
        }
    }

    fun closePicker() = _state.update { it.copy(picking = null) }

    private val thumbs = android.util.LruCache<String, android.graphics.Bitmap>(400)

    /** A small preview of a photo or video, cached; null when the phone cannot make one. */
    fun thumbnail(id: String, sizePx: Int): android.graphics.Bitmap? {
        thumbs.get(id)?.let { return it }
        val uri = catalog.uri(id) ?: return null
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                app.contentResolver.loadThumbnail(uri, android.util.Size(sizePx, sizePx), null)
            } else {
                val rowId = android.content.ContentUris.parseId(uri)
                @Suppress("DEPRECATION")
                if (id.startsWith(Category.VIDEOS)) {
                    android.provider.MediaStore.Video.Thumbnails.getThumbnail(app.contentResolver, rowId, android.provider.MediaStore.Video.Thumbnails.MINI_KIND, null)
                } else {
                    android.provider.MediaStore.Images.Thumbnails.getThumbnail(app.contentResolver, rowId, android.provider.MediaStore.Images.Thumbnails.MINI_KIND, null)
                }
            }
        }.getOrNull() ?: return null
        thumbs.put(id, bmp)
        return bmp
    }

    fun toggleCategory(c: String) = _state.update { s ->
        s.copy(categories = s.categories.map { if (it.category == c && it.count > 0) it.copy(selected = !it.selected) else it })
    }

    fun toggleApp(pkg: String) = _state.update { s -> s.copy(selectedApps = if (pkg in s.selectedApps) s.selectedApps - pkg else s.selectedApps + pkg) }

    fun selectAllApps(all: Boolean) = _state.update { s -> s.copy(selectedApps = if (all) s.apps.orEmpty().map { it.pkg }.toSet() else emptySet()) }

    /** Documents come from the system picker (Android shows apps no file list). */
    fun onDocumentsPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        catalog.pickedDocuments = (catalog.pickedDocuments + uris).distinct()
        scope.launch(Dispatchers.IO) {
            val docs = catalog.scan(Category.DOCUMENTS, Permission.GRANTED)
            inventories = inventories + (Category.DOCUMENTS to docs)
            _state.update { s ->
                s.copy(categories = s.categories.map { if (it.category == Category.DOCUMENTS) it.copy(count = docs.count, bytes = docs.totalBytes, selected = docs.count > 0) else it })
            }
        }
    }

    /** "Send" on the choose screen. */
    fun send() {
        val p = peer ?: return fail("lost")
        val s = _state.value
        val sources = HashMap<String, () -> java.io.InputStream?>()
        var n = 0
        val next = { (++n).toString() }
        val items = ArrayList<OfferItem>()
        for (row in s.categories.filter { it.selected }) {
            val inv = inventories[row.category] ?: continue
            val albums = inv.albums.associate { it.id to it.name }
            val only = s.picked[row.category]
            for (it in inv.items) {
                if (only != null && it.id !in only) continue
                val k = next()
                items += OfferItem(k, it.category, it.name, it.size, it.mime.ifBlank { null }, it.createdMs, it.albumIds.firstOrNull()?.let(albums::get), it.itemCount)
                val id = it.id
                sources[k] = { catalog.open(id) }
            }
        }
        if (s.appsAllowed) {
            for (a in s.apps.orEmpty().filter { it.pkg in s.selectedApps }) {
                for ((item, file) in appItems(a, next)) {
                    items += item
                    sources[item.k] = { file.inputStream() }
                }
            }
        }
        if (items.isEmpty()) return
        val offer = Offer(UUID.randomUUID().toString(), items)
        val source = AndroidSource { item -> sources[item.k]?.invoke() }
        startTransferUi(DirectIntent.SEND)
        sendJob = scope.launch(Dispatchers.IO) { runSend(p, offer, source) }
    }

    private suspend fun runSend(first: DirectPeer, offer: Offer, source: DirectSource) {
        var p = first
        while (true) {
            val sd = DirectSender(p, offer, source, sendListener)
            sender = sd
            try {
                val f = sd.run()
                finishSend(f)
                return
            } catch (e: DirectCancelled) {
                return fail(if (e.reason == "user") "cancelled" else "peer_cancelled")
            } catch (e: DirectRefused) {
                return fail(e.code)
            } catch (e: IOException) {
                if (sd.cancelled) return fail("cancelled")
                _state.update { it.copy(stage = DirectStage.Reconnecting) }
                p = reconnect(p) ?: return fail("lost")
                peer = p
                _state.update { it.copy(stage = DirectStage.Transferring) }
            }
        }
    }

    /** Wi-Fi: dial the same code again. Cable: wait for the link to come back. Gives up after 2 minutes. */
    private suspend fun reconnect(old: DirectPeer): DirectPeer? {
        val t = target
        val waiter = CompletableDeferred<DirectPeer>().also { relink = it }
        val deadline = System.currentTimeMillis() + RECONNECT_MS
        while (System.currentTimeMillis() < deadline) {
            if (sender?.cancelled == true) return null
            if (waiter.isCompleted) return waiter.await()
            if (t != null && !isCable(old)) {
                val link = withIo { WifiTransport.connect(t, 3000) }
                if (link != null) {
                    val p = runCatching { withIo { DirectPeer.greet(link.channel, me(DirectIntent.SEND), true) } }.getOrNull()
                    if (p != null) {
                        p.startReading()
                        relink = null
                        return p
                    }
                    link.channel.close()
                }
            }
            withTimeoutOrNull(2000) { waiter.await() }?.let { return it }
        }
        relink = null
        return null
    }

    private fun finishSend(f: Finish) {
        wakeLocks.release()
        TransferService.stop(app)
        catalog.clearExports()
        _state.update { it.copy(stage = DirectStage.Finished, result = f, progress = it.progress.copy(current = null)) }
        peer?.channel?.close()
    }

    private val sendListener = object : TransferListener {
        override fun onPlanned(all: List<OfferItem>, skip: Set<String>, why: Map<String, String>) = planned(all, skip, why)
        override fun onBytes(n: Long, item: OfferItem) = bytes(n, item)
        override fun onItemDone(item: OfferItem, ack: ItemAck) = itemDone(item, ack)
    }

    fun cancel() {
        sender?.cancelled = true
        val p = peer
        if (_state.value.role == DirectIntent.RECEIVE && p != null) {
            runCatching { p.channel.sendMsg(DirectType.CANCEL, Cancel("user")) }
            p.channel.close()
            fail("cancelled")
        }
    }

    // ------------------------------------------------------------ progress

    private val doneKeys = ConcurrentHashMap.newKeySet<String>()
    private var sizes: Map<String, Long> = emptyMap()
    @Volatile private var currentKey: String? = null
    @Volatile private var currentBytes = 0L
    private var speedMark = 0L to 0L

    private fun startTransferUi(role: String) {
        doneKeys.clear()
        currentKey = null
        currentBytes = 0
        speedMark = System.currentTimeMillis() to 0L
        wakeLocks.acquire(viaWifi = !_state.value.viaCable)
        TransferService.start(app, app.getString(R.string.notif_direct))
        _state.update { it.copy(stage = DirectStage.Transferring, role = role, progress = DirectProgress(), result = null, error = null) }
    }

    private fun planned(all: List<OfferItem>, skip: Set<String>, why: Map<String, String>) {
        if (_state.value.role == DirectIntent.RECEIVE && _state.value.stage != DirectStage.Transferring) startTransferUi(DirectIntent.RECEIVE)
        sizes = all.associate { it.k to it.s }
        doneKeys.addAll(skip)
        // One line per app (an app is several installer files), with the receiver's reason.
        val notSent = all.filter { it.k in skip && it.app != null && why[it.k] != null && why[it.k] != "done" }
            .distinctBy { it.app!!.pkg }
            .map { NotSent(it.app!!.label, why.getValue(it.k)) }
        _state.update { it.copy(notSent = notSent) }
        publish(null, null)
    }

    private fun bytes(n: Long, item: OfferItem) {
        if (currentKey != item.k) {
            currentKey = item.k
            currentBytes = 0
        }
        currentBytes += n
        publish(item.n, item.c)
    }

    private val failedKeys = ConcurrentHashMap.newKeySet<String>()

    private fun itemDone(item: OfferItem, ack: ItemAck) {
        doneKeys += item.k
        if (ack.result == "failed") failedKeys += item.k else failedKeys -= item.k
        if (currentKey == item.k) { currentKey = null; currentBytes = 0 }
        publish(_state.value.progress.current, _state.value.progress.currentCategory)
    }

    private fun publish(name: String?, category: String?) {
        val done = sizes.filterKeys { it in doneKeys }.values.sum() + currentBytes
        val now = System.currentTimeMillis()
        var speed = _state.value.progress.bytesPerSec
        val (t0, b0) = speedMark
        if (now - t0 >= 1500) {
            val inst = (done - b0) * 1000 / (now - t0)
            speed = if (speed == 0L) inst else (speed * 2 + inst) / 3
            speedMark = now to done
        }
        _state.update {
            it.copy(
                progress = DirectProgress(
                    itemsDone = doneKeys.size.coerceAtMost(sizes.size), itemsTotal = sizes.size,
                    bytesDone = done, bytesTotal = sizes.values.sum(),
                    current = name, currentCategory = category, failed = failedKeys.size, bytesPerSec = speed.coerceAtLeast(0),
                ),
            )
        }
    }

    // --------------------------------------------------------------- links

    /** Every new authenticated link, from Wi-Fi or either end of the cable. */
    private suspend fun onLink(link: DirectLink) {
        val mine = myIntent()
        val p = try {
            withIo { DirectPeer.greet(link.channel, me(mine), link.isClient) }
        } catch (e: IOException) {
            link.channel.close()
            // The receiver closed without a word: the code belongs to an earlier screen (new key).
            if (link.isClient && !link.viaCable && _state.value.stage == DirectStage.Connecting) fail("old_qr")
            return
        }
        if (link.viaCable) cablePeers += p

        // A sender coming back after a dropped link.
        val waiting = relink
        if (waiting != null && _state.value.stage == DirectStage.Reconnecting && p.hello.device.id == peer?.hello?.device?.id) {
            p.startReading()
            isClient = link.isClient
            waiting.complete(p)
            return
        }
        if (_state.value.stage == DirectStage.Reconnecting && _state.value.role == DirectIntent.RECEIVE && p.hello.device.id == peer?.hello?.device?.id) {
            peer = p
            isClient = link.isClient
            becomeReceiver(p)
            _state.update { it.copy(stage = DirectStage.Transferring) }
            p.startReading()
            return
        }
        // Busy with another phone: say no.
        if (peer != null && _state.value.stage !in setOf(DirectStage.Waiting, DirectStage.Connecting, DirectStage.Idle, DirectStage.Finished, DirectStage.Failed)) {
            runCatching { link.channel.sendMsg(DirectType.CANCEL, Cancel("busy")) }
            link.channel.close()
            return
        }
        peer?.channel?.close()
        peer = p
        isClient = link.isClient
        when (DirectPeer.resolve(mine, p.hello.intent)) {
            DirectIntent.SEND -> becomeSender(p)
            DirectIntent.RECEIVE -> becomeReceiver(p)
            else -> if (mine == DirectIntent.ANY && p.hello.intent == DirectIntent.ANY) {
                p.handler = { r ->
                    when (r.type) {
                        DirectType.ROLE -> when (r.decode(RoleMsg.serializer()).intent) {
                            DirectIntent.SEND -> becomeReceiver(p)
                            DirectIntent.RECEIVE -> becomeSender(p)
                        }
                        DirectType.CANCEL -> onPeerCancel(p, r)
                    }
                }
                p.onLost = { if (peer === p) fail("lost") }
                _state.update { it.copy(stage = DirectStage.Undecided, peer = p.hello.device, viaCable = link.viaCable) }
            } else {
                runCatching { link.channel.sendMsg(DirectType.CANCEL, Cancel("same_direction")) }
                link.channel.close()
                peer = null
                return fail(if (mine == DirectIntent.SEND) "both_send" else "both_receive")
            }
        }
        p.startReading()
    }

    /** The other phone said no before any transfer started (busy with another phone, or the user left). */
    private fun onPeerCancel(p: DirectPeer, r: DirectRecord) {
        if (peer !== p) return
        val reason = runCatching { r.decode(Cancel.serializer()).reason }.getOrDefault("")
        fail(when (reason) {
            "busy" -> "busy"
            "same_direction" -> if (_state.value.role == DirectIntent.RECEIVE) "both_receive" else "both_send"
            else -> "peer_cancelled"
        })
    }

    /** Cable, nobody chose yet: the user picks on this phone. */
    fun chooseRole(intent: String) {
        val p = peer ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { p.channel.sendMsg(DirectType.ROLE, RoleMsg(intent)) }
            if (intent == DirectIntent.SEND) becomeSender(p) else becomeReceiver(p)
        }
    }

    // ---------------------------------------------------------------- cable

    private var usbReceiver: BroadcastReceiver? = null

    /** Watches the cable while the app is open. */
    fun registerUsb() {
        if (usbReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val device = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                when (i.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> device?.let(::onUsbDevice)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        device?.let { askedUsb.remove(it.deviceName) }
                        _state.update { it.copy(usbWaiting = false) }
                    }
                    ACTION_USB_PERMISSION -> {
                        _state.update { it.copy(usbWaiting = false) }
                        if (device != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) onUsbDevice(device)
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, f)
        usbReceiver = r
    }

    /** Phones already on the cable when the app opened. */
    fun checkUsb() {
        val usb = app.getSystemService(UsbManager::class.java) ?: return
        usb.deviceList.values.firstOrNull(UsbDirect::looksLikePhone)?.let(::onUsbDevice)
    }

    private val switching = ConcurrentHashMap.newKeySet<String>()
    /** Android's access dialog is shown once per plug-in; a "no" is not asked again on every resume. */
    private val askedUsb = ConcurrentHashMap.newKeySet<String>()

    /** Host side: ask for access, switch the other phone into accessory mode, then open it. */
    fun onUsbDevice(d: UsbDevice) {
        if (!UsbDirect.looksLikePhone(d)) return
        val busy = _state.value.stage in setOf(DirectStage.Transferring, DirectStage.Choosing, DirectStage.Undecided) && peer != null
        if (busy && _state.value.stage != DirectStage.Reconnecting) return
        val usb = app.getSystemService(UsbManager::class.java) ?: return
        if (!usb.hasPermission(d)) {
            if (!askedUsb.add(d.deviceName)) return
            val back = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            usb.requestPermission(d, PendingIntent.getBroadcast(app, 7, back, flags))
            _state.update { it.copy(usbWaiting = true) }
            return
        }
        scope.launch(Dispatchers.IO) {
            if (UsbDirect.isInAccessoryMode(d)) {
                // The attach broadcast and the activity intent can both bring the same device.
                if (!switching.add("open:" + d.deviceName)) return@launch
                try {
                    val link = UsbDirect.openHost(usb, d) ?: return@launch
                    onLink(link)
                } finally {
                    switching.remove("open:" + d.deviceName)
                }
            } else if (switching.add(d.deviceName)) {
                try {
                    UsbDirect.switchToAccessory(usb, d)
                } finally {
                    delay(3000)
                    switching.remove(d.deviceName)
                }
            }
        }
    }

    /** Accessory side: the other phone switched this one into SwitchFlow accessory mode. */
    fun onAccessory(a: UsbAccessory) {
        scope.launch(Dispatchers.IO) {
            val link = UsbDirect.openAccessory(app, a) ?: return@launch
            onLink(link)
        }
    }

    // ------------------------------------------------------------- apps inbox

    fun refreshInbox() {
        _state.update { it.copy(inbox = mergeInbox(it.inbox), canInstall = inbox.canInstall()) }
        if (autoInstall && inbox.canInstall()) {
            autoInstall = false
            installAll()
        }
    }

    private fun mergeInbox(old: List<InstallRow>): List<InstallRow> {
        val prev = old.associateBy { it.app.pkg }
        return inbox.list().map { a -> prev[a.pkg]?.copy(app = a) ?: InstallRow(a) }
    }

    fun unknownSourcesIntent(): Intent = inbox.unknownSourcesSettings()

    private val installQueue = ArrayDeque<String>()

    fun installAll() {
        val ready = _state.value.inbox.filter { it.state == "ready" || it.state == "failed" }.map { it.app.pkg }
        synchronized(installQueue) {
            installQueue.clear()
            installQueue.addAll(ready)
        }
        installNext()
    }

    fun install(pkg: String) {
        synchronized(installQueue) { installQueue.clear(); installQueue.add(pkg) }
        installNext()
    }

    private fun installNext() {
        val pkg = synchronized(installQueue) { installQueue.removeFirstOrNull() } ?: return
        val row = _state.value.inbox.firstOrNull { it.app.pkg == pkg } ?: return installNext()
        setInstall(pkg, "installing", null)
        scope.launch(Dispatchers.IO) {
            runCatching { inbox.install(row.app) }.onFailure { onInstallResult(pkg, false, "failed") }
        }
    }

    fun onInstallResult(pkg: String, ok: Boolean, error: String?) {
        if (ok) inbox.remove(pkg)
        setInstall(pkg, if (ok) "installed" else "failed", error)
        installNext()
    }

    private fun setInstall(pkg: String, st: String, error: String?) = _state.update { s ->
        s.copy(inbox = s.inbox.map { if (it.app.pkg == pkg) it.copy(state = st, error = error) else it })
    }

    fun removeFromInbox(pkg: String) {
        inbox.remove(pkg)
        _state.update { s -> s.copy(inbox = s.inbox.filter { it.app.pkg != pkg }) }
    }

    /** App icon for the picker (decoded off the main thread by the caller). */
    fun appIcon(pkg: String, sizePx: Int) = appsRepo.icon(pkg, sizePx)

    fun inboxIcon(pkg: String, sizePx: Int) = inbox.icon(pkg, sizePx)

    // ---------------------------------------------------------- permissions

    private val askLock = Mutex()

    private suspend fun ask(perms: List<String>, categories: List<String>, write: Boolean) = askLock.withLock {
        val missing = perms.distinct().filter { !CategoryPermissions.granted(app, listOf(it)) }
        if (missing.isEmpty()) return@withLock
        val done = CompletableDeferred<Unit>()
        _state.update { it.copy(permissionAsk = PermissionAsk(missing, categories, write, done)) }
        done.await()
        _state.update { it.copy(permissionAsk = null) }
    }

    private suspend fun askWrite(categories: List<String>) =
        ask(categories.flatMap { CategoryPermissions.forWriting(it) }, categories, write = true)

    fun onPermissionsAnswered() {
        _state.value.permissionAsk?.done?.complete(Unit)
    }

    // --------------------------------------------------------------- ending

    private fun fail(code: String) {
        peer?.channel?.close()
        wakeLocks.release()
        TransferService.stop(app)
        listener?.stop()
        listener = null
        _state.update { it.copy(stage = DirectStage.Failed, error = code, qr = null, progress = it.progress.copy(current = null)) }
    }

    /** Back to the start screen: closes links and stops listening. */
    fun leave() {
        reset()
        _state.update { DirectUi(inbox = mergeInbox(it.inbox), canInstall = inbox.canInstall()) }
    }

    private fun reset() {
        sender?.cancelled = true
        sender = null
        sendJob?.cancel()
        sendJob = null
        relink?.cancel()
        relink = null
        receiver?.abortCurrent()
        receiver = null
        peer?.channel?.close()
        peer = null
        target = null
        listener?.stop()
        listener = null
        catalog.clearExports()
        wakeLocks.release()
        _state.value.permissionAsk?.done?.complete(Unit)
        _state.update {
            it.copy(
                stage = DirectStage.Idle, role = null, qr = null, peer = null, error = null, result = null, permissionAsk = null, usbWaiting = false,
                picking = null, picked = emptyMap(), pickedBytes = emptyMap(), notSent = emptyList(),
            )
        }
    }

    private suspend fun <T> withIo(block: suspend () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    companion object {
        const val RECONNECT_MS = 120_000L
        private const val ACTION_USB_PERMISSION = "app.switchflow.companion.DIRECT_USB_PERMISSION"
    }
}

/** Keeps the CPU and Wi-Fi awake while a transfer runs with the screen off. */
private class TransferLocks(context: Context) {
    private val power = context.getSystemService(android.os.PowerManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var cpu: android.os.PowerManager.WakeLock? = null
    private var net: WifiManager.WifiLock? = null

    @Synchronized
    fun acquire(viaWifi: Boolean) {
        if (cpu == null) {
            cpu = power?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SwitchFlow:direct")?.apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        if (viaWifi && net == null) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            net = runCatching { wifi?.createWifiLock(mode, "SwitchFlow:direct")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        }
    }

    @Synchronized
    fun release() {
        runCatching { cpu?.release() }
        runCatching { net?.release() }
        cpu = null
        net = null
    }
}

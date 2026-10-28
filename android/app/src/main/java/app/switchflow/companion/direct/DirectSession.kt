package app.switchflow.companion.direct

import app.switchflow.companion.net.ProtocolJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Messages of docs/DIRECT.md. Keep in sync with ios/SwitchFlowCompanion/Direct/DirectSession.swift.

@Serializable
data class PeerDevice(val id: String, val name: String, val model: String, val platform: String, val os: String, val app: String)

@Serializable
data class Hello(val v: Int = 1, val device: PeerDevice, val intent: String)

@Serializable
data class RoleMsg(val intent: String)

@Serializable
data class AppMeta(val pkg: String, val label: String, val versionCode: Long, val part: Int, val parts: Int, val minSdk: Int = 0)

@Serializable
data class OfferItem(
    val k: String,
    val c: String,
    val n: String,
    val s: Long,
    val m: String? = null,
    val t: Long? = null,
    val a: String? = null,
    val cnt: Int? = null,
    val app: AppMeta? = null,
)

@Serializable
data class Offer(val sessionId: String, val items: List<OfferItem>)

@Serializable
data class Accept(
    val ok: Boolean,
    val skip: List<String> = emptyList(),
    val freeBytes: Long = 0,
    val error: String? = null,
    /** Why a key is skipped: "exists" (already on the phone), "installed" (same or newer app), "old_android". */
    val why: Map<String, String> = emptyMap(),
)

@Serializable
data class ItemStart(val k: String)

@Serializable
data class ItemEnd(val k: String, val size: Long, val sha256: String, val error: String? = null)

@Serializable
data class ItemAck(val k: String, val result: String, val error: String? = null)

@Serializable
data class Finish(val saved: Int, val skipped: Int, val failed: Int)

@Serializable
data class Cancel(val reason: String)

object DirectIntent {
    const val SEND = "send"
    const val RECEIVE = "receive"
    const val ANY = "any"
}

internal inline fun <reified T> DirectChannel.sendMsg(type: Int, body: T) = send(type, ProtocolJson.encodeToString(serializer<T>(), body))

internal fun <T> DirectRecord.decode(ser: KSerializer<T>): T = ProtocolJson.decodeFromString(ser, text())

/** The other phone, after both sides said HELLO. Owns the single reader thread of the channel. */
class DirectPeer(val channel: DirectChannel, val hello: Hello) {
    /** Where records go; swapped when the session moves from waiting to sending or receiving. */
    @Volatile var handler: (DirectRecord) -> Unit = {}
    @Volatile var onLost: (IOException) -> Unit = {}

    fun startReading() {
        channel.startKeepAlive()
        thread(name = "sf-direct-read", isDaemon = true) {
            try {
                while (true) {
                    // Read first, then look the handler up: it may change while the read blocks.
                    val r = channel.receive()
                    handler(r)
                }
            } catch (e: IOException) {
                channel.close()
                onLost(e)
            } catch (e: RuntimeException) {
                // A malformed message: treat like a broken link rather than crashing the app.
                channel.close()
                onLost(IOException(e))
            }
        }
    }

    companion object {
        /** HELLO exchange; the connecting side speaks first. */
        fun greet(channel: DirectChannel, mine: Hello, isClient: Boolean): DirectPeer {
            if (isClient) channel.sendMsg(DirectType.HELLO, mine)
            val r = channel.receive()
            if (r.type != DirectType.HELLO) throw IOException("direct: expected HELLO")
            val theirs = r.decode(Hello.serializer())
            if (!isClient) channel.sendMsg(DirectType.HELLO, mine)
            return DirectPeer(channel, theirs)
        }

        /** Who sends, from both intents. Null: undecided (cable, nobody chose yet) or a conflict. */
        fun resolve(mine: String, theirs: String): String? = when {
            mine == DirectIntent.SEND && theirs != DirectIntent.SEND -> DirectIntent.SEND
            mine == DirectIntent.RECEIVE && theirs != DirectIntent.RECEIVE -> DirectIntent.RECEIVE
            mine == DirectIntent.ANY && theirs == DirectIntent.SEND -> DirectIntent.RECEIVE
            mine == DirectIntent.ANY && theirs == DirectIntent.RECEIVE -> DirectIntent.SEND
            else -> null
        }
    }
}

/** What the sending phone reads from. */
fun interface DirectSource {
    fun open(item: OfferItem): InputStream
}

/** Where one received file goes until it is verified. */
interface ItemWriter {
    fun write(b: ByteArray, off: Int, len: Int)
    /** All bytes arrived and their SHA-256 matched: store, verify, and say how it went. */
    fun commit(sha256: String): ItemAck
    fun abort()
}

/** What the receiving phone saves into. */
interface DirectSink {
    /** On OFFER: ask for permissions and space; [done] are keys already confirmed in this session. */
    fun plan(offer: Offer, done: Set<String>): Accept
    fun begin(item: OfferItem): ItemWriter
}

interface TransferListener {
    /** [all] is the whole offer; [skip] what the receiver already has or cannot use, [why] the reasons. */
    fun onPlanned(all: List<OfferItem>, skip: Set<String>, why: Map<String, String>) {}
    fun onBytes(n: Long, item: OfferItem) {}
    fun onItemDone(item: OfferItem, ack: ItemAck) {}
}

class DirectCancelled(val reason: String) : IOException("direct: cancelled ($reason)")

/** Refusal from the receiver: no space, permissions denied. */
class DirectRefused(val code: String) : IOException("direct: refused ($code)")

/**
 * Sending phone. [run] blocks the calling thread until the receiver reports FINISH,
 * and throws when the link drops (the caller reconnects and runs again with the same offer).
 */
class DirectSender(
    private val peer: DirectPeer,
    private val offer: Offer,
    private val source: DirectSource,
    private val listener: TransferListener,
) {
    private val events = LinkedBlockingQueue<DirectRecord>()
    @Volatile private var failure: IOException? = null
    @Volatile var cancelled = false

    fun run(): Finish {
        peer.onLost = { e -> failure = e; events.offer(DirectRecord(-1, ByteArray(0), 0, 0)) }
        peer.handler = { r ->
            when (r.type) {
                DirectType.ACK -> {
                    val ack = r.decode(ItemAck.serializer())
                    byKey[ack.k]?.let { listener.onItemDone(it, ack) }
                }
                DirectType.ACCEPT, DirectType.FINISH, DirectType.CANCEL -> events.offer(r)
            }
        }
        peer.channel.sendMsg(DirectType.OFFER, offer)
        val accept = await(DirectType.ACCEPT).decode(Accept.serializer())
        if (!accept.ok) throw DirectRefused(accept.error ?: "refused")
        val skip = accept.skip.toSet()
        val todo = offer.items.filter { it.k !in skip }
        listener.onPlanned(offer.items, skip, accept.why)

        for (item in todo) {
            if (cancelled) throw cancel("user")
            failure?.let { throw it }
            sendItem(item)
        }
        peer.channel.send(DirectType.DONE, "{}")
        return await(DirectType.FINISH).decode(Finish.serializer())
    }

    private val byKey = offer.items.associateBy { it.k }

    private fun sendItem(item: OfferItem) {
        val ch = peer.channel
        ch.sendMsg(DirectType.ITEM, ItemStart(item.k))
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        var error: String? = null
        try {
            source.open(item).use { input ->
                // Reading the next megabyte overlaps with encrypting and sending this one.
                ReadAhead(input).use { ahead ->
                    while (true) {
                        if (cancelled) throw cancel("user")
                        val c = ahead.next() ?: break
                        ch.sendData(c.buf, 0, c.len)
                        md.update(c.buf, 0, c.len)
                        size += c.len
                        listener.onBytes(c.len.toLong(), item)
                        ahead.recycle(c.buf)
                    }
                }
            }
        } catch (e: SourceFailure) {
            error = e.code
        }
        ch.sendMsg(DirectType.END, ItemEnd(item.k, size, Sha256.hex(md), error))
    }

    /** Reading the original failed: the item is reported, the transfer goes on. */
    class SourceFailure(val code: String) : Exception(code)

    /** A background reader a few chunks ahead of the sender, with a small pool of reused buffers. */
    private class ReadAhead(private val input: InputStream, depth: Int = 3) : AutoCloseable {
        class Chunk(val buf: ByteArray, val len: Int)

        private val free = java.util.concurrent.ArrayBlockingQueue<ByteArray>(depth + 1)
        private val full = java.util.concurrent.ArrayBlockingQueue<Any>(depth + 2)
        @Volatile private var stopped = false
        private val reader: Thread

        init {
            repeat(depth + 1) { free.add(ByteArray(DirectChannel.CHUNK)) }
            reader = kotlin.concurrent.thread(name = "sf-direct-readahead", isDaemon = true) {
                try {
                    while (!stopped) {
                        val buf = free.poll(500, TimeUnit.MILLISECONDS) ?: continue
                        val n = fill(buf)
                        if (n > 0) full.put(Chunk(buf, n))
                        if (n < buf.size) {
                            full.put(EOF)
                            return@thread
                        }
                    }
                } catch (e: SourceFailure) {
                    full.offer(e)
                } catch (_: InterruptedException) {
                }
            }
        }

        private fun fill(buf: ByteArray): Int {
            var total = 0
            try {
                while (total < buf.size) {
                    val n = input.read(buf, total, buf.size - total)
                    if (n < 0) break
                    total += n
                }
            } catch (e: IOException) {
                throw SourceFailure("source_read_failed")
            } catch (e: SecurityException) {
                throw SourceFailure("permission_denied")
            }
            return total
        }

        /** The next chunk, or null at the end of the file. */
        fun next(): Chunk? = when (val x = full.take()) {
            is Chunk -> x
            is SourceFailure -> throw x
            else -> null
        }

        fun recycle(buf: ByteArray) {
            free.offer(buf)
        }

        override fun close() {
            stopped = true
            reader.interrupt()
            reader.join(2000)
        }

        private companion object {
            val EOF = Any()
        }
    }

    private fun cancel(reason: String): DirectCancelled {
        runCatching { peer.channel.sendMsg(DirectType.CANCEL, Cancel(reason)) }
        peer.channel.close()
        return DirectCancelled(reason)
    }

    private fun await(type: Int): DirectRecord {
        while (true) {
            failure?.let { throw it }
            if (cancelled) throw cancel("user")
            val r = events.poll(500, TimeUnit.MILLISECONDS) ?: continue
            when (r.type) {
                type -> return r
                DirectType.CANCEL -> throw DirectCancelled(r.decode(Cancel.serializer()).reason)
                -1 -> throw failure ?: IOException("direct: lost")
            }
        }
    }
}

/** What the receiving phone knows about a session; survives reconnects. Key → saved|duplicate|skipped|failed. */
class ReceiveState(val sessionId: String) {
    val results = java.util.concurrent.ConcurrentHashMap<String, String>()
    val done: Set<String> get() = results.filterValues { it != "failed" }.keys
    fun finish() = Finish(
        saved = results.values.count { it == "saved" },
        skipped = results.values.count { it == "skipped" || it == "duplicate" },
        failed = results.values.count { it == "failed" },
    )
}

/**
 * Receiving phone: a record handler run on the peer's reader thread, so a slow save
 * (contacts import, read-back of a large video) holds the sender back instead of filling memory.
 */
class DirectReceiver(
    private val peer: DirectPeer,
    private val sink: DirectSink,
    private val listener: TransferListener,
    /** Gives the state of this session (new, or the one interrupted by a dropped link). */
    private val stateFor: (sessionId: String) -> ReceiveState,
    private val onFinished: (Finish) -> Unit,
    private val onCancelled: (String) -> Unit,
) {
    private var state: ReceiveState? = null
    private var items: Map<String, OfferItem> = emptyMap()
    private var current: OfferItem? = null
    private var writer: ItemWriter? = null
    private var md: MessageDigest? = null
    private var received = 0L
    /**
     * Saving and the read-back check of a finished file run here, in order, while the next file
     * is already arriving. At most [SAVING_AHEAD] files wait, so memory and disk stay bounded.
     */
    private val saver = java.util.concurrent.ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue()) { r ->
        Thread(r, "sf-direct-save").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
    private val saving = java.util.concurrent.Semaphore(SAVING_AHEAD)

    fun attach() {
        peer.handler = ::handle
    }

    /** Waits until every file handed to the saver is stored and acknowledged. */
    private fun drainSaver() {
        runCatching { saver.submit {}.get() }
    }

    private companion object {
        const val SAVING_AHEAD = 3
    }

    /** The link dropped: throw away the half-received file; the sender starts it again. */
    fun abortCurrent() {
        writer?.abort()
        writer = null
        current = null
    }

    private fun handle(r: DirectRecord) {
        when (r.type) {
            DirectType.OFFER -> {
                val offer = r.decode(Offer.serializer())
                // A file still being saved from before a reconnect must count as done in the plan.
                drainSaver()
                val st = stateFor(offer.sessionId)
                state = st
                items = offer.items.associateBy { it.k }
                val accept = try {
                    sink.plan(offer, st.done)
                } catch (e: Exception) {
                    Accept(false, error = "io_error")
                }
                if (accept.ok) {
                    accept.skip.forEach { k -> st.results.putIfAbsent(k, "skipped") }
                    listener.onPlanned(offer.items, accept.skip.toSet(), accept.why)
                }
                peer.channel.sendMsg(DirectType.ACCEPT, accept)
            }
            DirectType.ITEM -> {
                abortCurrent()
                val item = items[r.decode(ItemStart.serializer()).k] ?: return
                current = item
                received = 0
                md = MessageDigest.getInstance("SHA-256")
                writer = try {
                    sink.begin(item)
                } catch (e: Exception) {
                    null // the bytes are still read and dropped; END reports the failure
                }
            }
            DirectType.DATA -> {
                val item = current ?: return
                md?.update(r.body, r.offset, r.length)
                received += r.length
                val w = writer
                if (w != null) {
                    try {
                        w.write(r.body, r.offset, r.length)
                    } catch (e: Exception) {
                        w.abort()
                        writer = null
                    }
                }
                listener.onBytes(r.length.toLong(), item)
            }
            DirectType.END -> {
                val end = r.decode(ItemEnd.serializer())
                val item = current?.takeIf { it.k == end.k } ?: return
                val sha = md?.let(Sha256::hex)
                val got = received
                val w = writer
                writer = null
                current = null
                val st = state
                saving.acquire()
                saver.execute {
                    try {
                        val ack = when {
                            end.error != null -> { w?.abort(); ItemAck(item.k, "failed", end.error) }
                            w == null -> ItemAck(item.k, "failed", "io_error")
                            got != end.size || sha != end.sha256 -> { w.abort(); ItemAck(item.k, "failed", "checksum_mismatch") }
                            else -> try {
                                w.commit(end.sha256).copy(k = item.k)
                            } catch (e: Exception) {
                                w.abort()
                                ItemAck(item.k, "failed", if (e.message?.contains("ENOSPC") == true) "insufficient_storage" else "io_error")
                            }
                        }
                        st?.results?.put(item.k, ack.result)
                        listener.onItemDone(item, ack)
                        runCatching { peer.channel.sendMsg(DirectType.ACK, ack) }
                    } finally {
                        saving.release()
                    }
                }
            }
            DirectType.DONE -> {
                val st = state
                // FINISH goes out only after every file before it is saved and acknowledged.
                saver.execute {
                    val f = st?.finish() ?: Finish(0, 0, 0)
                    runCatching { peer.channel.sendMsg(DirectType.FINISH, f) }
                    onFinished(f)
                }
            }
            DirectType.CANCEL -> {
                abortCurrent()
                onCancelled(r.decode(Cancel.serializer()).reason)
                peer.channel.close()
            }
        }
    }
}

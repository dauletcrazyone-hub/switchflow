package app.switchflow.companion

import app.switchflow.companion.direct.Accept
import app.switchflow.companion.direct.DirectAuthException
import app.switchflow.companion.direct.DirectChannel
import app.switchflow.companion.direct.DirectCrypto
import app.switchflow.companion.direct.DirectHandshake
import app.switchflow.companion.direct.DirectIntent
import app.switchflow.companion.direct.DirectPeer
import app.switchflow.companion.direct.DirectQr
import app.switchflow.companion.direct.DirectReceiver
import app.switchflow.companion.direct.DirectSender
import app.switchflow.companion.direct.DirectSink
import app.switchflow.companion.direct.DirectSource
import app.switchflow.companion.direct.DirectTarget
import app.switchflow.companion.direct.DirectType
import app.switchflow.companion.direct.Finish
import app.switchflow.companion.direct.Hello
import app.switchflow.companion.direct.ItemAck
import app.switchflow.companion.direct.ItemWriter
import app.switchflow.companion.direct.Offer
import app.switchflow.companion.direct.OfferItem
import app.switchflow.companion.direct.PeerDevice
import app.switchflow.companion.direct.ReceiveState
import app.switchflow.companion.direct.TransferListener
import app.switchflow.companion.direct.UsbDirect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.random.Random

class DirectTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test(timeout = 30_000)
    fun hkdfMatchesRfc5869() {
        val okm = DirectCrypto.hkdf(ByteArray(22) { 0x0b }, unhex("000102030405060708090a0b0c"), unhex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", hex(okm))
    }

    /** The same numbers are checked by the iOS app (DirectTests.swift): both ends must agree byte for byte. */
    @Test(timeout = 30_000)
    fun crossPlatformVector() {
        val key = ByteArray(32) { it.toByte() }
        val session = DirectCrypto.sessionKey(key, ByteArray(16) { 0x10 }, ByteArray(16) { 0x20 })
        assertEquals(SESSION_HEX, hex(session))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(session, "AES"), GCMParameterSpec(128, DirectCrypto.nonce('C'.code.toByte(), 0)))
        assertEquals(RECORD_HEX, hex(c.doFinal(byteArrayOf(DirectType.HELLO.toByte()) + "{}".toByteArray())))
    }

    private class Pair(val client: DirectChannel, val server: DirectChannel)

    /** A loopback TCP connection with both handshakes done. */
    private fun connect(serverKey: ByteArray? = null, clientKey: ByteArray = serverKey ?: ByteArray(32) { 7 }, cable: Boolean = false): Pair {
        ServerSocket().apply { receiveBufferSize = 4 shl 20; bind(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1) }.use { ss ->
            val serverSide = CompletableFuture<DirectChannel>()
            thread {
                try {
                    val s = ss.accept().apply { sendBufferSize = 4 shl 20 }
                    val k = DirectHandshake.server(s.getInputStream(), s.getOutputStream(), if (cable) null else serverKey ?: clientKey)
                    serverSide.complete(DirectChannel(s.getInputStream(), s.getOutputStream(), k, false) { s.close() })
                } catch (e: Exception) {
                    serverSide.completeExceptionally(e)
                }
            }
            // Same socket buffers as the app (WifiTransport).
            val c = Socket().apply { sendBufferSize = 4 shl 20; receiveBufferSize = 4 shl 20; connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), ss.localPort)) }
            val k = DirectHandshake.client(c.getInputStream(), c.getOutputStream(), clientKey, if (cable) DirectHandshake.MODE_CABLE else DirectHandshake.MODE_QR)
            val client = DirectChannel(c.getInputStream(), c.getOutputStream(), k, true) { c.close() }
            return Pair(client, serverSide.get(5, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 30_000)
    fun recordsGoBothWaysInOrder() {
        val p = connect()
        val big = Random(1).nextBytes(DirectChannel.CHUNK)
        // The writer blocks once the socket buffer is full, so it runs beside the reader.
        thread {
            p.client.send(DirectType.HELLO, """{"a":1}""")
            p.client.sendData(big, 0, big.size)
        }
        p.server.send(DirectType.ACK, """{"b":2}""")
        val r1 = p.server.receive()
        assertEquals(DirectType.HELLO, r1.type)
        assertEquals("""{"a":1}""", r1.text())
        val r2 = p.server.receive()
        assertEquals(DirectType.DATA, r2.type)
        assertArrayEquals(big, r2.body.copyOfRange(r2.offset, r2.offset + r2.length))
        assertEquals("""{"b":2}""", p.client.receive().text())
        p.client.close(); p.server.close()
    }

    @Test(timeout = 30_000)
    fun cableModeCarriesTheKey() {
        val p = connect(clientKey = ByteArray(32) { 3 }, cable = true)
        p.client.send(DirectType.PING, "{}")
        p.client.send(DirectType.ROLE, """{"intent":"send"}""")
        assertEquals(DirectType.ROLE, p.server.receive().type) // PING is skipped
        p.client.close(); p.server.close()
    }

    @Test(timeout = 30_000)
    fun wrongKeyIsRejected() {
        val p = connect(serverKey = ByteArray(32) { 1 }, clientKey = ByteArray(32) { 2 })
        p.client.send(DirectType.HELLO, "{}")
        try {
            p.server.receive()
            fail("a record under another key was accepted")
        } catch (e: DirectAuthException) {
        }
        assertTrue(!p.server.isOpen)
        p.client.close()
    }

    @Test(timeout = 30_000)
    fun tamperedRecordIsRejected() {
        val out = ByteArrayOutputStream()
        val key = ByteArray(32) { 9 }
        DirectChannel(ByteArrayInputStream(ByteArray(0)), out, key, true) {}.send(DirectType.OFFER, """{"x":1}""")
        val bytes = out.toByteArray()
        bytes[10] = (bytes[10].toInt() xor 1).toByte()
        try {
            DirectChannel(ByteArrayInputStream(bytes), ByteArrayOutputStream(), key, false) {}.receive()
            fail("tampered record accepted")
        } catch (e: DirectAuthException) {
        }
    }

    @Test(timeout = 30_000)
    fun replayedRecordIsRejected() {
        val out = ByteArrayOutputStream()
        val key = ByteArray(32) { 9 }
        val tx = DirectChannel(ByteArrayInputStream(ByteArray(0)), out, key, true) {}
        tx.send(DirectType.ACK, "{}")
        val one = out.toByteArray()
        val rx = DirectChannel(ByteArrayInputStream(one + one), ByteArrayOutputStream(), key, false) {}
        rx.receive()
        try {
            rx.receive()
            fail("the same record was accepted twice")
        } catch (e: DirectAuthException) {
        }
    }

    @Test(timeout = 30_000)
    fun qrCodeRoundTrip() {
        val t = DirectTarget(listOf("192.168.1.5", "192.168.43.1"), 47830, ByteArray(32) { (it * 7).toByte() }, "Galaxy A10 & Co", "android")
        val raw = DirectQr.build(t)
        assertTrue(DirectQr.isDirect(raw))
        val back = DirectQr.parse(raw)!!
        assertEquals(t.hosts, back.hosts)
        assertEquals(t.port, back.port)
        assertArrayEquals(t.key, back.key)
        assertEquals(t.name, back.name)
        assertNull(DirectQr.parse("switchflow://pair?v=1&hosts=1.2.3.4&port=1&fp=00&s=abc"))
        assertNull(DirectQr.parse(raw.replace("k=", "k=x")))
        assertNull(DirectQr.parse(raw.replace("h=192.168.1.5,192.168.43.1", "h=evil.example")))
        assertEquals("ios", DirectQr.parse(raw.replace("o=android", "o=ios"))!!.platform)
    }

    @Test(timeout = 30_000)
    fun directionsResolve() {
        assertEquals(DirectIntent.SEND, DirectPeer.resolve(DirectIntent.SEND, DirectIntent.RECEIVE))
        assertEquals(DirectIntent.SEND, DirectPeer.resolve(DirectIntent.SEND, DirectIntent.ANY))
        assertEquals(DirectIntent.RECEIVE, DirectPeer.resolve(DirectIntent.ANY, DirectIntent.SEND))
        assertEquals(DirectIntent.SEND, DirectPeer.resolve(DirectIntent.ANY, DirectIntent.RECEIVE))
        assertNull(DirectPeer.resolve(DirectIntent.ANY, DirectIntent.ANY))
        assertNull(DirectPeer.resolve(DirectIntent.SEND, DirectIntent.SEND))
        assertNull(DirectPeer.resolve(DirectIntent.RECEIVE, DirectIntent.RECEIVE))
    }

    @Test(timeout = 30_000)
    fun usbWritesNeverEndOnAPacketBoundary() {
        for (max in listOf(UsbDirect.HOST_WRITE_MAX, UsbDirect.ACCESSORY_WRITE_MAX)) {
            for (n in (1..70_000) + (max - 70..max + 70)) {
                val len = UsbDirect.chunkLength(n, max)
                assertTrue(len in 1..max)
                assertTrue("length $len for $n", len % 64 != 0)
            }
        }
    }

    // ---------------------------------------------------------------- sessions

    private fun device(id: String) = PeerDevice(id, id, "Test", "android", "16", "0.1")

    private class MemorySink(val failOn: String? = null) : DirectSink {
        val saved = ConcurrentHashMap<String, ByteArray>()
        var plans = 0
        override fun plan(offer: Offer, done: Set<String>) = Accept(true, offer.items.map { it.k }.filter { it in done }, 1L shl 40).also { plans++ }
        override fun begin(item: OfferItem): ItemWriter {
            if (item.k == failOn) throw IOException("disk full")
            val buf = ByteArrayOutputStream()
            return object : ItemWriter {
                override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
                override fun commit(sha256: String): ItemAck {
                    saved[item.n] = buf.toByteArray()
                    return ItemAck(item.k, "saved")
                }
                override fun abort() {}
            }
        }
    }

    private class Session(val sender: DirectPeer, val receiver: DirectPeer)

    private fun session(): Session {
        val p = connect()
        val r = CompletableFuture<DirectPeer>()
        thread { r.complete(DirectPeer.greet(p.server, Hello(device = device("rx"), intent = DirectIntent.RECEIVE), false)) }
        val s = DirectPeer.greet(p.client, Hello(device = device("tx"), intent = DirectIntent.SEND), true)
        val rx = r.get(5, TimeUnit.SECONDS)
        assertEquals("rx", s.hello.device.id)
        assertEquals("tx", rx.hello.device.id)
        s.startReading()
        return Session(s, rx)
    }

    private fun runReceiver(peer: DirectPeer, sink: DirectSink, states: MutableMap<String, ReceiveState>): CompletableFuture<Finish> {
        val finished = CompletableFuture<Finish>()
        DirectReceiver(peer, sink, object : TransferListener {}, { sid -> states.getOrPut(sid) { ReceiveState(sid) } }, { finished.complete(it) }, { finished.completeExceptionally(IOException(it)) }).attach()
        peer.onLost = { e -> finished.completeExceptionally(e) }
        peer.startReading()
        return finished
    }

    @Test(timeout = 30_000)
    fun wholeTransferIsVerifiedAndReported() {
        val files = mapOf(
            "a.jpg" to Random(2).nextBytes(700_000),
            "b.mp4" to Random(3).nextBytes(3 * DirectChannel.CHUNK + 17),
            "empty.txt" to ByteArray(0),
            "gone.jpg" to ByteArray(10),
            "nospace.bin" to Random(4).nextBytes(1000),
        )
        val items = files.entries.mapIndexed { i, (n, b) -> OfferItem("${i + 1}", "photos", n, b.size.toLong()) }
        val s = session()
        val sink = MemorySink(failOn = items.first { it.n == "nospace.bin" }.k)
        val done = runReceiver(s.receiver, sink, HashMap())
        val acks = ConcurrentHashMap<String, String>()
        val source = DirectSource { item -> if (item.n == "gone.jpg") throw DirectSender.SourceFailure("not_found") else ByteArrayInputStream(files.getValue(item.n)) }
        val f = DirectSender(s.sender, Offer("s1", items), source, object : TransferListener {
            override fun onItemDone(item: OfferItem, ack: ItemAck) { acks[item.n] = ack.result }
        }).run()
        assertEquals(Finish(saved = 3, skipped = 0, failed = 2), f)
        assertEquals(f, done.get(5, TimeUnit.SECONDS))
        for (n in listOf("a.jpg", "b.mp4", "empty.txt")) assertArrayEquals(n, files.getValue(n), sink.saved.getValue(n))
        assertEquals("failed", acks["gone.jpg"])
        assertEquals("failed", acks["nospace.bin"])
        s.sender.channel.close()
    }

    @Test(timeout = 30_000)
    fun interruptedTransferResumesWithoutDuplicates() {
        val files = (1..6).associate { "f$it.jpg" to Random(it).nextBytes(200_000 + it) }
        val items = files.entries.mapIndexed { i, (n, b) -> OfferItem("${i + 1}", "photos", n, b.size.toLong()) }
        val offer = Offer("resume", items)
        val states = HashMap<String, ReceiveState>()
        val sink = MemorySink()

        // First link drops right after the third file is confirmed.
        val first = session()
        runReceiver(first.receiver, sink, states)
        var confirmed = 0
        val dropping = object : TransferListener {
            override fun onItemDone(item: OfferItem, ack: ItemAck) {
                if (++confirmed == 3) first.sender.channel.close()
            }
        }
        try {
            DirectSender(first.sender, offer, { ByteArrayInputStream(files.getValue(it.n)) }, dropping).run()
            fail("the dropped link was not noticed")
        } catch (e: IOException) {
        }
        val savedBefore = sink.saved.size
        assertTrue(savedBefore >= 3)

        // Second link: same offer, the receiver skips what it already confirmed.
        val second = session()
        val done = runReceiver(second.receiver, sink, states)
        var sent = 0
        val f = DirectSender(second.sender, offer, { sent++; ByteArrayInputStream(files.getValue(it.n)) }, object : TransferListener {}).run()
        assertEquals(6, f.saved)
        assertEquals(0, f.failed)
        assertNotNull(done.get(5, TimeUnit.SECONDS))
        assertEquals(6, sink.saved.size)
        for ((n, b) in files) assertArrayEquals(n, b, sink.saved.getValue(n))
        assertTrue("re-sent files that were already confirmed", sent <= 6 - 3)
        second.sender.channel.close()
    }

    /** Not a pass/fail check: prints how fast the protocol itself moves data (crypto, hashing, copies). */
    @Test(timeout = 120_000)
    fun throughputOverLoopback() {
        val size = 256 * 1024 * 1024
        val block = Random(9).nextBytes(4 * 1024 * 1024)
        val items = (1..4).map { OfferItem("$it", "videos", "v$it.mp4", (size / 4).toLong()) }
        val s = session()
        val sink = object : DirectSink {
            override fun plan(offer: Offer, done: Set<String>) = Accept(true, emptyList(), 1L shl 40)
            override fun begin(item: OfferItem) = object : ItemWriter {
                override fun write(b: ByteArray, off: Int, len: Int) {}
                override fun commit(sha256: String) = ItemAck(item.k, "saved")
                override fun abort() {}
            }
        }
        runReceiver(s.receiver, sink, HashMap())
        val source = DirectSource { _ ->
            object : java.io.InputStream() {
                var left = size / 4
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (left == 0) return -1
                    val n = minOf(len, left, block.size)
                    System.arraycopy(block, 0, b, off, n)
                    left -= n
                    return n
                }
            }
        }
        val t0 = System.nanoTime()
        val f = DirectSender(s.sender, Offer("speed", items), source, object : TransferListener {}).run()
        val sec = (System.nanoTime() - t0) / 1e9
        assertEquals(4, f.saved)
        println("DIRECT_THROUGHPUT ${"%.0f".format(size / 1048576.0 / sec)} MB/s")
        s.sender.channel.close()
    }

    companion object {
        const val SESSION_HEX = "15acf1cf04e20b6f0763851f3003cb3b4f2c81f1b255d36e833a62526df99966"
        const val RECORD_HEX = "2ffeeb7e78c8305d5d82517ee1cab4e8215145"
    }
}

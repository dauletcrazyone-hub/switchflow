package app.switchflow.companion.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** HTTP error from the PC with its protocol error code (docs/PROTOCOL.md). */
class ApiException(val status: Int, val code: String, val receivedBytes: Long? = null) : IOException("HTTP $status $code")

/**
 * SecureLocalTransport, phone side. Trusts exactly one certificate: the one whose
 * SHA-256 came from the QR code. No CA, no system trust store, no hostname games.
 */
class ApiClient(host: String, port: Int, private val fingerprint: String) {
    val baseUrl = "https://$host:$port"
    @Volatile var token: String? = null

    private val trustManager = object : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("empty chain")
            if (!pinMatches(leaf)) throw CertificateException("certificate pin mismatch")
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("client certificates are not used")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun pinMatches(cert: java.security.cert.Certificate): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val hex = digest.joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(hex.toByteArray(), fingerprint.toByteArray())
    }

    private val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }

    private val base: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(ssl.socketFactory, trustManager)
        // The PC is addressed by IP; its identity is proven by the pinned certificate instead.
        .hostnameVerifier { _, session -> session.peerCertificates.firstOrNull()?.let(::pinMatches) == true }
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val quick = base.newBuilder().readTimeout(5, TimeUnit.SECONDS).build()

    private val jsonType = "application/json".toMediaType()
    private val binaryType = "application/octet-stream".toMediaType()

    private fun request(path: String): Request.Builder {
        val b = Request.Builder().url(baseUrl + path)
        token?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    private fun check(resp: Response) {
        if (resp.isSuccessful) return
        val body = runCatching { ProtocolJson.decodeFromString(ErrorBody.serializer(), resp.body?.string().orEmpty()) }.getOrNull()
        resp.close()
        throw ApiException(resp.code, body?.error ?: "http_${resp.code}", body?.receivedBytes)
    }

    private suspend fun <T> call(client: OkHttpClient, req: Request, ser: KSerializer<T>?): T? = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { resp ->
            check(resp)
            if (ser == null) null else ProtocolJson.decodeFromString(ser, resp.body?.string().orEmpty())
        }
    }

    private inline fun <reified B> json(body: B): RequestBody =
        ProtocolJson.encodeToString(serializer<B>(), body).toRequestBody(jsonType)

    suspend fun ping(): Boolean = runCatching {
        call<Unit>(quick, request("/v1/ping").build(), null)
        true
    }.getOrDefault(false)

    suspend fun pair(body: PairRequest): PairResponse =
        call(quick, request("/v1/pair").post(json(body)).build(), PairResponse.serializer())!!

    suspend fun pairStatus(id: String): PairStatus =
        call(quick, request("/v1/pair/$id").build(), PairStatus.serializer())!!

    suspend fun heartbeat(hb: Heartbeat): HeartbeatResponse =
        call(quick, request("/v1/heartbeat").post(json(hb)).build(), HeartbeatResponse.serializer())!!

    suspend fun commands(after: Long, wait: Int = 25): CommandsResponse =
        call(base, request("/v1/commands?after=$after&wait=$wait").build(), CommandsResponse.serializer())!!

    suspend fun inventory(body: InventoryBody) {
        call<Unit>(base, request("/v1/inventory").post(json(body)).build(), null)
    }

    suspend fun prepareResult(body: PrepareResult) {
        call<Unit>(base, request("/v1/prepare-result").post(json(body)).build(), null)
    }

    suspend fun uploadStatus(sid: String, key: String): UploadStatus =
        call(base, request("/v1/sessions/$sid/items/$key/upload-status").build(), UploadStatus.serializer())!!

    /** Returns the PC's received byte count after this chunk. */
    suspend fun putChunk(sid: String, key: String, offset: Long, data: ByteArray, len: Int): Long {
        val body = data.toRequestBody(binaryType, 0, len)
        val req = request("/v1/sessions/$sid/items/$key/chunk?offset=$offset").put(body).build()
        return call(base, req, ChunkAck.serializer())!!.receivedBytes
    }

    suspend fun complete(sid: String, key: String, size: Long, sha256: String) {
        call<Unit>(base, request("/v1/sessions/$sid/items/$key/complete").post(json(CompleteBody(size, sha256))).build(), null)
    }

    suspend fun fail(sid: String, key: String, error: String) {
        call<Unit>(base, request("/v1/sessions/$sid/items/$key/fail").post(json(FailBody(error))).build(), null)
    }

    suspend fun next(sid: String, wait: Int = 25): NextResponse =
        call(base, request("/v1/sessions/$sid/next?wait=$wait").build(), NextResponse.serializer())!!

    /** Opens the item's bytes starting at [from] (HTTP Range). The caller closes the stream. */
    suspend fun openContent(sid: String, key: String, from: Long): InputStream = withContext(Dispatchers.IO) {
        val b = request("/v1/sessions/$sid/items/$key/content")
        if (from > 0) b.header("Range", "bytes=$from-")
        val resp = base.newCall(b.build()).execute()
        check(resp)
        if (from > 0 && resp.code != 206) {
            resp.close()
            throw ApiException(resp.code, "range_not_supported")
        }
        resp.body?.byteStream() ?: throw IOException("empty body")
    }

    suspend fun ack(sid: String, key: String, body: AckBody) {
        call<Unit>(base, request("/v1/sessions/$sid/items/$key/ack").post(json(body)).build(), null)
    }
}

@kotlinx.serialization.Serializable
data class ChunkAck(val receivedBytes: Long)

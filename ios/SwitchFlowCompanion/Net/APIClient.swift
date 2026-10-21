import CryptoKit
import Foundation

/// HTTP error from the PC with its protocol error code (docs/PROTOCOL.md).
struct APIError: Error {
    let status: Int
    let code: String
    var receivedBytes: Int64?
}

/// Trusts exactly one certificate: the one whose SHA-256 came from the QR code.
final class PinningDelegate: NSObject, URLSessionDelegate {
    let fingerprint: String
    init(fingerprint: String) { self.fingerprint = fingerprint }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust,
              let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let leaf = chain.first else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        let der = SecCertificateCopyData(leaf) as Data
        let hex = SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
        if hex == fingerprint {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}

/// SecureLocalTransport, iPhone side.
final class APIClient {
    let baseURL: URL
    var token: String?
    private let session: URLSession
    private let quick: URLSession

    init(host: String, port: Int, fingerprint: String) {
        baseURL = URL(string: "https://\(host):\(port)")!
        let delegate = PinningDelegate(fingerprint: fingerprint)
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 45
        cfg.waitsForConnectivity = false
        cfg.urlCache = nil
        session = URLSession(configuration: cfg, delegate: delegate, delegateQueue: nil)
        let q = URLSessionConfiguration.ephemeral
        q.timeoutIntervalForRequest = 5
        quick = URLSession(configuration: q, delegate: delegate, delegateQueue: nil)
    }

    private func request(_ path: String, method: String = "GET") -> URLRequest {
        var r = URLRequest(url: URL(string: path, relativeTo: baseURL)!)
        r.httpMethod = method
        if let token { r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        return r
    }

    @discardableResult
    private func send(_ r: URLRequest, body: Data? = nil, on s: URLSession? = nil) async throws -> Data {
        var req = r
        if let body {
            req.httpBody = body
            req.setValue(req.value(forHTTPHeaderField: "Content-Type") ?? "application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, resp) = try await (s ?? session).data(for: req)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let e = try? JSONDecoder().decode(ErrorBody.self, from: data)
            throw APIError(status: status, code: e?.error ?? "http_\(status)", receivedBytes: e?.receivedBytes)
        }
        return data
    }

    private func json<T: Encodable>(_ v: T) throws -> Data { try JSONEncoder().encode(v) }
    private func decode<T: Decodable>(_ t: T.Type, _ d: Data) throws -> T { try JSONDecoder().decode(t, from: d) }

    func ping() async -> Bool {
        (try? await send(request("/v1/ping"), on: quick)) != nil
    }

    func pair(_ body: PairRequest) async throws -> PairResponse {
        try decode(PairResponse.self, await send(request("/v1/pair", method: "POST"), body: json(body), on: quick))
    }

    func pairStatus(_ id: String) async throws -> PairStatus {
        try decode(PairStatus.self, await send(request("/v1/pair/\(id)"), on: quick))
    }

    func heartbeat(_ hb: Heartbeat) async throws -> HeartbeatResponse {
        try decode(HeartbeatResponse.self, await send(request("/v1/heartbeat", method: "POST"), body: json(hb), on: quick))
    }

    func commands(after: Int64) async throws -> CommandsResponse {
        try decode(CommandsResponse.self, await send(request("/v1/commands?after=\(after)&wait=25")))
    }

    func inventory(_ body: InventoryBody) async throws {
        try await send(request("/v1/inventory", method: "POST"), body: json(body))
    }

    func prepareResult(_ body: PrepareResult) async throws {
        try await send(request("/v1/prepare-result", method: "POST"), body: json(body))
    }

    func uploadStatus(_ sid: String, _ key: String) async throws -> UploadStatus {
        try decode(UploadStatus.self, await send(request("/v1/sessions/\(sid)/items/\(key)/upload-status")))
    }

    func putChunk(_ sid: String, _ key: String, offset: Int64, data: Data) async throws -> Int64 {
        var r = request("/v1/sessions/\(sid)/items/\(key)/chunk?offset=\(offset)", method: "PUT")
        r.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        return try decode(ChunkAck.self, await send(r, body: data)).receivedBytes
    }

    func complete(_ sid: String, _ key: String, size: Int64, sha256: String) async throws {
        try await send(request("/v1/sessions/\(sid)/items/\(key)/complete", method: "POST"), body: json(CompleteBody(size: size, sha256: sha256)))
    }

    func fail(_ sid: String, _ key: String, error: String) async throws {
        try await send(request("/v1/sessions/\(sid)/items/\(key)/fail", method: "POST"), body: json(FailBody(error: error)))
    }

    func next(_ sid: String) async throws -> NextResponse {
        try decode(NextResponse.self, await send(request("/v1/sessions/\(sid)/next?wait=25")))
    }

    /// Bytes [from, to] inclusive of an item (bounded HTTP Range, so downloads resume cheaply).
    func content(_ sid: String, _ key: String, from: Int64, to: Int64) async throws -> Data {
        var r = request("/v1/sessions/\(sid)/items/\(key)/content")
        r.setValue("bytes=\(from)-\(to)", forHTTPHeaderField: "Range")
        return try await send(r)
    }

    func ack(_ sid: String, _ key: String, _ body: AckBody) async throws {
        try await send(request("/v1/sessions/\(sid)/items/\(key)/ack", method: "POST"), body: json(body))
    }
}

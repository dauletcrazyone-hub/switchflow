//! SecureLocalTransport, part 2: the HTTPS API phones talk to (docs/PROTOCOL.md).

use crate::core::{Core, Heartbeat, PairStatus, ServerStatus};
use crate::engine::{AckBody, NextResult, MAX_CHUNK};
use crate::error::Fault;
use crate::model::{CategoryInventory, DeviceInfo, PrepareResult};
use axum::body::{Body, Bytes};
use axum::extract::{ConnectInfo, DefaultBodyLimit, FromRequestParts, Path, Query, State};
use axum::http::{header, request::Parts, HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post, put};
use axum::{Json, Router};
use futures_util::StreamExt;
use serde::Deserialize;
use serde_json::json;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};

type Shared = Arc<Core>;

impl IntoResponse for Fault {
    fn into_response(self) -> Response {
        let status = StatusCode::from_u16(self.http_status()).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
        let mut body = json!({ "error": self.code(), "message": self.to_string() });
        if let Fault::OffsetMismatch(n) = self {
            body["receivedBytes"] = json!(n);
        }
        (status, Json(body)).into_response()
    }
}

/// Extractor for requests carrying a valid `Authorization: Bearer <token>`.
pub struct Authed(pub String);

impl FromRequestParts<Shared> for Authed {
    type Rejection = Fault;

    async fn from_request_parts(parts: &mut Parts, core: &Shared) -> Result<Self, Self::Rejection> {
        let token = parts
            .headers
            .get(header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .and_then(|s| s.strip_prefix("Bearer "))
            .ok_or(Fault::Unauthorized)?;
        core.authenticate(token.trim()).map(Authed)
    }
}

pub fn router(core: Shared) -> Router {
    Router::new()
        .route("/v1/ping", get(ping))
        .route("/v1/pair", post(pair))
        .route("/v1/pair/{id}", get(pair_status))
        .route("/v1/heartbeat", post(heartbeat))
        .route("/v1/commands", get(commands))
        .route("/v1/inventory", post(inventory).layer(DefaultBodyLimit::max(96 * 1024 * 1024)))
        .route("/v1/prepare-result", post(prepare_result))
        .route("/v1/sessions/{sid}/items/{key}/upload-status", get(upload_status))
        .route(
            "/v1/sessions/{sid}/items/{key}/chunk",
            put(chunk).layer(DefaultBodyLimit::max(MAX_CHUNK as usize + 1024)),
        )
        .route("/v1/sessions/{sid}/items/{key}/complete", post(complete))
        .route("/v1/sessions/{sid}/items/{key}/fail", post(fail))
        .route("/v1/sessions/{sid}/next", get(next))
        .route("/v1/sessions/{sid}/items/{key}/content", get(content))
        .route("/v1/sessions/{sid}/items/{key}/ack", post(ack))
        .layer(DefaultBodyLimit::max(1024 * 1024))
        .with_state(core)
}

async fn ping() -> Json<serde_json::Value> {
    Json(json!({ "app": "switchflow", "protocol": 1 }))
}

#[derive(Deserialize)]
struct PairBody {
    secret: String,
    device: DeviceInfo,
}

async fn pair(
    State(core): State<Shared>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    Json(body): Json<PairBody>,
) -> Result<Json<serde_json::Value>, Fault> {
    let via_usb = addr.ip().is_loopback();
    let (id, code) = core.handle_pair(&body.secret, body.device, via_usb)?;
    Ok(Json(json!({ "pairingId": id, "verificationCode": code, "expiresInSec": crate::core::PAIR_REQUEST_TTL.as_secs() })))
}

async fn pair_status(State(core): State<Shared>, Path(id): Path<String>) -> Result<Json<serde_json::Value>, Fault> {
    let (status, token) = core.pair_status(&id)?;
    let token = if status == PairStatus::Approved { token } else { None };
    Ok(Json(json!({ "status": status, "token": token, "desktopName": core.desktop_name })))
}

async fn heartbeat(State(core): State<Shared>, Authed(dev): Authed, Json(hb): Json<Heartbeat>) -> Result<Json<serde_json::Value>, Fault> {
    let role = core.heartbeat(&dev, hb)?;
    Ok(Json(json!({ "role": role })))
}

#[derive(Deserialize)]
struct CommandsQuery {
    #[serde(default)]
    after: u64,
    #[serde(default)]
    wait: Option<u64>,
}

async fn commands(State(core): State<Shared>, Authed(dev): Authed, Query(q): Query<CommandsQuery>) -> Result<Json<serde_json::Value>, Fault> {
    let wait = Duration::from_secs(q.wait.unwrap_or(25).min(30));
    let cmds = core.wait_commands(&dev, q.after, wait).await?;
    Ok(Json(json!({ "commands": cmds })))
}

#[derive(Deserialize)]
struct InventoryBody {
    categories: Vec<CategoryInventory>,
}

async fn inventory(State(core): State<Shared>, Authed(dev): Authed, Json(body): Json<InventoryBody>) -> Result<StatusCode, Fault> {
    core.handle_inventory(&dev, body.categories)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn prepare_result(State(core): State<Shared>, Authed(dev): Authed, Json(body): Json<PrepareResult>) -> Result<StatusCode, Fault> {
    core.handle_prepare_result(&dev, body)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn upload_status(
    State(core): State<Shared>,
    Authed(dev): Authed,
    Path((sid, key)): Path<(String, String)>,
) -> Result<Json<serde_json::Value>, Fault> {
    let (received, state) = core.upload_status(&dev, &sid, &key)?;
    Ok(Json(json!({ "receivedBytes": received, "state": state })))
}

#[derive(Deserialize)]
struct ChunkQuery {
    offset: u64,
}

async fn write_chunk(path: &std::path::Path, offset: u64, data: &[u8]) -> std::io::Result<()> {
    let mut f = tokio::fs::OpenOptions::new().create(true).write(true).truncate(false).open(path).await?;
    // Drop anything past `offset` left by an interrupted write, then append.
    f.set_len(offset).await?;
    f.seek(std::io::SeekFrom::Start(offset)).await?;
    f.write_all(data).await?;
    f.flush().await?;
    Ok(())
}

async fn chunk(
    State(core): State<Shared>,
    Authed(dev): Authed,
    Path((sid, key)): Path<(String, String)>,
    Query(q): Query<ChunkQuery>,
    body: Bytes,
) -> Result<Json<serde_json::Value>, Fault> {
    let len = body.len() as u64;
    let path = core.begin_chunk(&dev, &sid, &key, q.offset, len)?;
    let res = write_chunk(&path, q.offset, &body).await;
    let received = core.end_chunk(&sid, &key, q.offset, len, res.is_ok());
    res?;
    Ok(Json(json!({ "receivedBytes": received })))
}

#[derive(Deserialize)]
struct CompleteBody {
    size: u64,
    sha256: String,
}

async fn complete(
    State(core): State<Shared>,
    Authed(dev): Authed,
    Path((sid, key)): Path<(String, String)>,
    Json(body): Json<CompleteBody>,
) -> Result<Json<serde_json::Value>, Fault> {
    core.complete_upload(&dev, &sid, &key, body.size, &body.sha256).await?;
    Ok(Json(json!({ "state": "staged" })))
}

#[derive(Deserialize)]
struct FailBody {
    #[serde(default)]
    error: Option<String>,
}

async fn fail(
    State(core): State<Shared>,
    Authed(dev): Authed,
    Path((sid, key)): Path<(String, String)>,
    Json(body): Json<FailBody>,
) -> Result<StatusCode, Fault> {
    core.fail_upload(&dev, &sid, &key, body.error)?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
struct NextQuery {
    #[serde(default)]
    wait: Option<u64>,
}

async fn next(
    State(core): State<Shared>,
    Authed(dev): Authed,
    Path(sid): Path<String>,
    Query(q): Query<NextQuery>,
) -> Result<Json<serde_json::Value>, Fault> {
    let wait = Duration::from_secs(q.wait.unwrap_or(25).min(30));
    Ok(Json(match core.next_item(&dev, &sid, wait).await? {
        NextResult::Item(item) => json!({ "state": "item", "item": item }),
        NextResult::Wait => json!({ "state": "wait" }),
        NextResult::Paused => json!({ "state": "paused" }),
        NextResult::Done => json!({ "state": "done" }),
        NextResult::Cancelled => json!({ "state": "cancelled" }),
    }))
}

/// `bytes=N-` or `bytes=N-M` (inclusive end). Suffix ranges and multi-ranges are not used by SwitchFlow clients.
fn parse_range(headers: &HeaderMap) -> Option<(u64, Option<u64>)> {
    let v = headers.get(header::RANGE)?.to_str().ok()?;
    let spec = v.strip_prefix("bytes=")?;
    let (start, end) = spec.split_once('-')?;
    let start = start.trim().parse().ok()?;
    let end = match end.trim() {
        "" => None,
        e => Some(e.parse().ok()?),
    };
    Some((start, end))
}

async fn content(
    State(core): State<Shared>,
    Authed(dev): Authed,
    Path((sid, key)): Path<(String, String)>,
    headers: HeaderMap,
) -> Result<Response, Fault> {
    let (path, size) = core.open_content(&dev, &sid, &key)?;
    let (start, end) = parse_range(&headers).unwrap_or((0, None));
    let last = end.map_or(size.saturating_sub(1), |e| e.min(size.saturating_sub(1)));
    let partial = start > 0 || end.is_some();
    if start > size || (size > 0 && start > last) || (end.is_some() && end < Some(start)) {
        return Ok((StatusCode::RANGE_NOT_SATISFIABLE, [(header::CONTENT_RANGE, format!("bytes */{size}"))]).into_response());
    }
    let len = if size == 0 || start == size { 0 } else { last + 1 - start };
    let mut file = tokio::fs::File::open(&path).await?;
    file.seek(std::io::SeekFrom::Start(start)).await?;
    let mut pos = start;
    let counter = core.clone();
    let stream = tokio_util::io::ReaderStream::with_capacity(file.take(len), 256 * 1024).map(move |chunk| {
        if let Ok(b) = &chunk {
            pos += b.len() as u64;
            counter.add_served(&sid, &key, pos, b.len() as u64);
        }
        chunk
    });
    let mut resp = Response::new(Body::from_stream(stream));
    if partial {
        *resp.status_mut() = StatusCode::PARTIAL_CONTENT;
        let range = format!("bytes {start}-{last}/{size}");
        resp.headers_mut().insert(header::CONTENT_RANGE, HeaderValue::from_str(&range).expect("ascii"));
    }
    let h = resp.headers_mut();
    h.insert(header::CONTENT_TYPE, HeaderValue::from_static("application/octet-stream"));
    h.insert(header::ACCEPT_RANGES, HeaderValue::from_static("bytes"));
    h.insert(header::CONTENT_LENGTH, HeaderValue::from(len));
    Ok(resp)
}

async fn ack(
    State(core): State<Shared>,
    Authed(dev): Authed,
    Path((sid, key)): Path<(String, String)>,
    Json(body): Json<AckBody>,
) -> Result<StatusCode, Fault> {
    core.ack(&dev, &sid, &key, body)?;
    Ok(StatusCode::NO_CONTENT)
}

/// Binds the preferred port (or any free one) on all interfaces and serves HTTPS forever.
pub async fn serve(core: Shared, identity: crate::tls::Identity) {
    let preferred = core.settings().preferred_port;
    let listener = std::net::TcpListener::bind(("0.0.0.0", preferred)).or_else(|_| std::net::TcpListener::bind(("0.0.0.0", 0)));
    let listener = match listener {
        Ok(l) => l,
        Err(e) => {
            tracing::error!("cannot bind server socket: {}", e.kind());
            core.set_server_status(ServerStatus { error: Some("bind_failed".into()), ..Default::default() });
            return;
        }
    };
    let _ = listener.set_nonblocking(true);
    let port = listener.local_addr().map(|a| a.port()).unwrap_or(0);
    let tls = match crate::tls::server_config(&identity) {
        Ok(c) => axum_server::tls_rustls::RustlsConfig::from_config(c),
        Err(e) => {
            tracing::error!("tls config failed: {e}");
            core.set_server_status(ServerStatus { error: Some("tls_failed".into()), ..Default::default() });
            return;
        }
    };
    core.set_server_status(ServerStatus {
        running: true,
        port,
        hosts: crate::net::lan_hosts(),
        fingerprint: identity.fingerprint.clone(),
        error: None,
    });
    tracing::info!(port, "server listening");
    let app = router(core.clone()).into_make_service_with_connect_info::<SocketAddr>();
    if let Err(e) = axum_server::from_tcp_rustls(listener, tls).serve(app).await {
        tracing::error!("server stopped: {}", e.kind());
        core.mutate(|st| {
            st.server.running = false;
            st.server.error = Some("stopped".into());
        });
    }
}

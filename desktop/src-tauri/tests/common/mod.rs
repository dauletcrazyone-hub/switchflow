//! Shared by the integration tests: the real server, and an HTTPS client that pins its
//! certificate exactly as the Android/iOS apps do.
#![allow(dead_code)]

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, SignatureScheme};
use serde_json::{json, Value};
use std::sync::Arc;
use std::time::Duration;
use switchflow_lib::core::{Core, Paths};

#[derive(Debug)]
pub struct Pinned(String);

impl ServerCertVerifier for Pinned {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _: &[CertificateDer<'_>],
        _: &ServerName<'_>,
        _: &[u8],
        _: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        if switchflow_lib::checksum::sha256_bytes(end_entity.as_ref()) == self.0 {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General("certificate pin mismatch".into()))
        }
    }
    fn verify_tls12_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(m, c, d, &rustls::crypto::ring::default_provider().signature_verification_algorithms)
    }
    fn verify_tls13_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(m, c, d, &rustls::crypto::ring::default_provider().signature_verification_algorithms)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        rustls::crypto::ring::default_provider().signature_verification_algorithms.supported_schemes()
    }
}

pub fn pinned_client(fingerprint: &str) -> reqwest::Client {
    let cfg = rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()
        .unwrap()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(Pinned(fingerprint.to_string())))
        .with_no_client_auth();
    reqwest::Client::builder()
        .use_preconfigured_tls(cfg)
        .timeout(Duration::from_secs(40))
        .build()
        .unwrap()
}

pub fn device(id: &str, platform: &str) -> Value {
    json!({
        "id": id, "platform": platform, "manufacturer": "Test", "model": "Test Model",
        "osVersion": "1", "name": format!("Test {id}"), "appVersion": "0.1.0",
        "storageFreeBytes": 50_000_000_000u64, "storageTotalBytes": 64_000_000_000u64,
        "batteryPercent": 80, "charging": false
    })
}

pub async fn start_server() -> (Arc<Core>, String, String, tempfile::TempDir) {
    let dir = tempfile::tempdir().unwrap();
    let core = Arc::new(Core::new(Paths::new(dir.path().to_path_buf(), Some(dir.path().join("Documents"))), "TEST-PC".into()));
    let identity = switchflow_lib::tls::generate().unwrap();
    let fp = identity.fingerprint.clone();
    tokio::spawn(switchflow_lib::server::serve(core.clone(), identity));
    switchflow_lib::spawn_background(core.clone());
    for _ in 0..100 {
        if core.snapshot().server.running {
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    let port = core.snapshot().server.port;
    assert!(port > 0, "server did not start");
    (core, format!("https://127.0.0.1:{port}"), fp, dir)
}


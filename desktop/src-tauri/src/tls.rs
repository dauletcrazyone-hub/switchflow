//! SecureLocalTransport, part 1: the desktop's self-signed TLS identity.
//!
//! Phones never trust it through a CA. They pin the SHA-256 of the DER certificate,
//! which reaches them only through the QR code shown on this PC's screen.

use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use std::path::Path;
use std::sync::Arc;

pub struct Identity {
    pub cert_der: Vec<u8>,
    pub key_der: Vec<u8>,
    pub fingerprint: String,
}

pub fn load_or_create(dir: &Path) -> crate::error::BoxResult<Identity> {
    let cert_path = dir.join("cert.der");
    let key_path = dir.join("key.der");
    if let (Ok(cert_der), Ok(key_der)) = (std::fs::read(&cert_path), std::fs::read(&key_path)) {
        if !cert_der.is_empty() && !key_der.is_empty() {
            let fingerprint = crate::checksum::sha256_bytes(&cert_der);
            return Ok(Identity { cert_der, key_der, fingerprint });
        }
    }
    let id = generate()?;
    std::fs::create_dir_all(dir)?;
    crate::persist::write_atomic(&cert_path, &id.cert_der)?;
    crate::persist::write_atomic(&key_path, &id.key_der)?;
    Ok(id)
}

/// Deletes the stored identity; the next start generates a new one and every phone
/// has to pair again.
pub fn reset(dir: &Path) -> std::io::Result<()> {
    for f in ["cert.der", "key.der"] {
        match std::fs::remove_file(dir.join(f)) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => return Err(e),
            _ => {}
        }
    }
    Ok(())
}

pub fn generate() -> crate::error::BoxResult<Identity> {
    let mut params = rcgen::CertificateParams::new(vec!["switchflow.local".to_string()])?;
    params
        .distinguished_name
        .push(rcgen::DnType::CommonName, "SwitchFlow Desktop");
    params.not_before = rcgen::date_time_ymd(2025, 1, 1);
    params.not_after = rcgen::date_time_ymd(2045, 1, 1);
    let key = rcgen::KeyPair::generate()?;
    let cert = params.self_signed(&key)?;
    let cert_der = cert.der().to_vec();
    let fingerprint = crate::checksum::sha256_bytes(&cert_der);
    Ok(Identity { cert_der, key_der: key.serialize_der(), fingerprint })
}

pub fn server_config(id: &Identity) -> crate::error::BoxResult<Arc<rustls::ServerConfig>> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut cfg = rustls::ServerConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()?
        .with_no_client_auth()
        .with_single_cert(
            vec![CertificateDer::from(id.cert_der.clone())],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(id.key_der.clone())),
        )?;
    cfg.alpn_protocols = vec![b"http/1.1".to_vec()];
    Ok(Arc::new(cfg))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_is_stable_across_loads() {
        let dir = tempfile::tempdir().unwrap();
        let a = load_or_create(dir.path()).unwrap();
        let b = load_or_create(dir.path()).unwrap();
        assert_eq!(a.fingerprint, b.fingerprint);
        assert_eq!(a.fingerprint.len(), 64);
        server_config(&a).unwrap();
        reset(dir.path()).unwrap();
        let c = load_or_create(dir.path()).unwrap();
        assert_ne!(a.fingerprint, c.fingerprint);
    }
}

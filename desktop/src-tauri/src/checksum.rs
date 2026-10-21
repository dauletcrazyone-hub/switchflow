//! ChecksumVerifier: streaming SHA-256 for files of any size.

use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::{self, Read, Write};
use std::path::Path;

const BUF: usize = 1024 * 1024;

pub fn sha256_file(path: &Path) -> io::Result<(String, u64)> {
    let mut file = File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; BUF];
    let mut total = 0u64;
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
        total += n as u64;
    }
    Ok((hex::encode(hasher.finalize()), total))
}

/// Copies `from` into `to` and returns the SHA-256 of the bytes written.
/// The destination is flushed to disk before returning.
pub fn copy_with_sha256(from: &Path, to: &Path) -> io::Result<(String, u64)> {
    let mut src = File::open(from)?;
    let mut dst = File::create(to)?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; BUF];
    let mut total = 0u64;
    loop {
        let n = src.read(&mut buf)?;
        if n == 0 {
            break;
        }
        dst.write_all(&buf[..n])?;
        hasher.update(&buf[..n]);
        total += n as u64;
    }
    dst.sync_all()?;
    Ok((hex::encode(hasher.finalize()), total))
}

pub fn sha256_bytes(data: &[u8]) -> String {
    hex::encode(Sha256::digest(data))
}

pub fn is_valid_sha256_hex(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn known_vector() {
        assert_eq!(
            sha256_bytes(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn file_and_copy_agree() {
        let dir = tempfile::tempdir().unwrap();
        let a = dir.path().join("a.bin");
        let data: Vec<u8> = (0..3_000_000u32).map(|i| (i % 251) as u8).collect();
        std::fs::write(&a, &data).unwrap();
        let (h1, n1) = sha256_file(&a).unwrap();
        let (h2, n2) = copy_with_sha256(&a, &dir.path().join("b.bin")).unwrap();
        assert_eq!(h1, sha256_bytes(&data));
        assert_eq!((h1, n1), (h2, n2));
    }

    #[test]
    fn hex_validation() {
        assert!(is_valid_sha256_hex(&"a".repeat(64)));
        assert!(!is_valid_sha256_hex("xyz"));
    }
}

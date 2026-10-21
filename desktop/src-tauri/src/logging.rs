//! Logging without personal data.
//!
//! Rule for the whole codebase: never log file names, contact fields, event titles,
//! file contents, pairing secrets or tokens. Log item keys, sizes, categories and
//! error codes only. `file_kind` is the one helper allowed to touch a file name.

use std::path::Path;
use tracing_subscriber::{fmt, prelude::*, EnvFilter};

pub fn init(logs_dir: &Path) -> Option<tracing_appender::non_blocking::WorkerGuard> {
    std::fs::create_dir_all(logs_dir).ok()?;
    let appender = tracing_appender::rolling::daily(logs_dir, "switchflow.log");
    let (writer, guard) = tracing_appender::non_blocking(appender);
    let filter = EnvFilter::try_from_env("SWITCHFLOW_LOG").unwrap_or_else(|_| EnvFilter::new("info"));
    let registry = tracing_subscriber::registry()
        .with(filter)
        .with(fmt::layer().with_writer(writer).with_ansi(false).with_target(false));
    #[cfg(debug_assertions)]
    let registry = registry.with(fmt::layer().with_target(false));
    registry.try_init().ok()?;
    Some(guard)
}

/// Reduces a file name to its extension for diagnostics: "IMG_0042.HEIC" -> "*.heic".
pub fn file_kind(name: &str) -> String {
    match Path::new(name).extension().and_then(|e| e.to_str()) {
        Some(ext) if ext.len() <= 8 => format!("*.{}", ext.to_ascii_lowercase()),
        _ => "*".into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn file_kind_hides_names() {
        assert_eq!(file_kind("Мама день рождения.JPG"), "*.jpg");
        assert_eq!(file_kind("noext"), "*");
    }
}

//! One error type for both the HTTPS protocol and the UI commands.
//! `code()` is stable: the UI translates it (errors.<code>) and phones branch on it.

use serde::ser::SerializeStruct;
use serde::{Serialize, Serializer};

pub type BoxError = Box<dyn std::error::Error + Send + Sync>;
pub type BoxResult<T> = Result<T, BoxError>;

#[derive(Debug, Clone, PartialEq, thiserror::Error)]
pub enum Fault {
    #[error("unauthorized")]
    Unauthorized,
    #[error("not found")]
    NotFound,
    #[error("bad pairing secret")]
    BadSecret,
    #[error("too many requests")]
    TooManyRequests,
    #[error("transfer is paused")]
    Paused,
    #[error("transfer was cancelled")]
    Cancelled,
    #[error("offset mismatch")]
    OffsetMismatch(u64),
    #[error("PC buffer is full, retry later")]
    Busy,
    #[error("checksum mismatch")]
    ChecksumMismatch,
    #[error("payload too large")]
    TooLarge,
    #[error("bad request: {0}")]
    BadRequest(&'static str),
    /// A UI action that is not allowed right now; the code names the reason.
    #[error("not allowed: {0}")]
    State(&'static str),
    #[error("i/o error: {0}")]
    Io(String),
}

impl Fault {
    pub fn code(&self) -> &'static str {
        match self {
            Fault::Unauthorized => "unauthorized",
            Fault::NotFound => "not_found",
            Fault::BadSecret => "bad_secret",
            Fault::TooManyRequests => "too_many_requests",
            Fault::Paused => "paused",
            Fault::Cancelled => "cancelled",
            Fault::OffsetMismatch(_) => "offset_mismatch",
            Fault::Busy => "busy",
            Fault::ChecksumMismatch => "checksum_mismatch",
            Fault::TooLarge => "too_large",
            Fault::BadRequest(_) => "bad_request",
            Fault::State(code) => code,
            Fault::Io(_) => "io_error",
        }
    }

    pub fn http_status(&self) -> u16 {
        match self {
            Fault::Unauthorized => 401,
            Fault::NotFound => 404,
            Fault::BadSecret => 403,
            Fault::TooManyRequests | Fault::Busy => 429,
            Fault::Paused => 423,
            Fault::Cancelled => 410,
            Fault::OffsetMismatch(_) | Fault::State(_) => 409,
            Fault::ChecksumMismatch => 422,
            Fault::TooLarge => 413,
            Fault::BadRequest(_) => 400,
            Fault::Io(_) => 500,
        }
    }
}

impl From<std::io::Error> for Fault {
    fn from(e: std::io::Error) -> Self {
        Fault::Io(e.kind().to_string())
    }
}

/// UI commands receive `{ code, message }`.
impl Serialize for Fault {
    fn serialize<S: Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        let mut st = s.serialize_struct("Fault", 2)?;
        st.serialize_field("code", self.code())?;
        st.serialize_field("message", &self.to_string())?;
        st.end()
    }
}

pub type FResult<T> = Result<T, Fault>;

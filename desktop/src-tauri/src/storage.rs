//! StorageAnalyzer: free space on PC volumes and required-space estimates.

use std::path::Path;

/// Free bytes available to the current user on the volume containing `path`.
#[cfg(windows)]
pub fn available_space(path: &Path) -> Option<u64> {
    use std::os::windows::ffi::OsStrExt;
    use windows::core::PCWSTR;
    use windows::Win32::Storage::FileSystem::GetDiskFreeSpaceExW;

    // Walk up to an existing ancestor so a not-yet-created folder still resolves.
    let mut probe = path.to_path_buf();
    while !probe.exists() {
        if !probe.pop() {
            return None;
        }
    }
    let wide: Vec<u16> = probe.as_os_str().encode_wide().chain(std::iter::once(0)).collect();
    let mut free = 0u64;
    unsafe { GetDiskFreeSpaceExW(PCWSTR(wide.as_ptr()), Some(&mut free), None, None).ok()? };
    Some(free)
}

#[cfg(not(windows))]
pub fn available_space(_path: &Path) -> Option<u64> {
    None
}

/// Space a phone should have before receiving `total` bytes: 5% headroom plus 300 MB
/// so the OS never runs completely out of storage.
pub fn required_on_target(total: u64) -> u64 {
    total + total / 20 + 300 * 1024 * 1024
}

/// Staging only holds files that are in flight, but we still ask for the largest
/// file plus a working buffer.
pub fn required_on_pc(largest_item: u64, total: u64) -> u64 {
    let buffer = total.min(2 * 1024 * 1024 * 1024);
    largest_item.max(buffer) + 200 * 1024 * 1024
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn estimates_have_headroom() {
        assert!(required_on_target(1000) > 1000);
        assert_eq!(required_on_pc(10, 10), 10 + 200 * 1024 * 1024);
    }

    #[cfg(windows)]
    #[test]
    fn temp_dir_has_space() {
        let free = available_space(&std::env::temp_dir().join("does/not/exist")).unwrap();
        assert!(free > 0);
    }
}

//! Keeps only the contacts the user picked. The phone exports all contacts as one vCard file and
//! lists them in the same order (inventory `entries`); the PC drops the other cards before the
//! file is stored in a backup or delivered to the other phone.

use std::collections::BTreeSet;

/// Keeps the cards at the given 0-based positions; everything outside cards is dropped.
/// Returns the new file and how many cards it holds.
pub fn keep_cards(text: &str, keep: &BTreeSet<u32>) -> (String, u32) {
    let mut out = String::new();
    let mut index: u32 = 0;
    let mut inside = false;
    let mut kept = 0;
    for line in text.split_inclusive('\n') {
        let bare = line.trim_end_matches(['\r', '\n']).trim();
        if !inside && bare.eq_ignore_ascii_case("BEGIN:VCARD") {
            inside = true;
            if keep.contains(&index) {
                kept += 1;
            }
        }
        if inside && keep.contains(&index) {
            out.push_str(line);
            if !line.ends_with('\n') {
                out.push_str("\r\n");
            }
        }
        if inside && bare.eq_ignore_ascii_case("END:VCARD") {
            inside = false;
            index += 1;
        }
    }
    (out, kept)
}

#[cfg(test)]
mod tests {
    use super::*;

    const THREE: &str = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Алия\r\nTEL;TYPE=CELL:+77030000001\r\nEND:VCARD\r\n\
                         BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Бахыт\r\nNOTE:line one\r\n  folded on\r\nEND:VCARD\r\n\
                         begin:vcard\nVERSION:3.0\nFN:Гульнар\nend:vcard";

    #[test]
    fn keeps_only_the_picked_cards_in_order() {
        let (out, n) = keep_cards(THREE, &[0, 2].into_iter().collect());
        assert_eq!(n, 2);
        assert!(out.contains("FN:Алия") && out.contains("FN:Гульнар"));
        assert!(!out.contains("Бахыт") && !out.contains("folded on"));
        assert!(out.find("Алия").unwrap() < out.find("Гульнар").unwrap());
        assert!(out.ends_with("end:vcard\r\n"), "a last line without a newline gets one");
    }

    #[test]
    fn keeps_folded_lines_with_their_card() {
        let (out, n) = keep_cards(THREE, &[1].into_iter().collect());
        assert_eq!(n, 1);
        assert_eq!(out, "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Бахыт\r\nNOTE:line one\r\n  folded on\r\nEND:VCARD\r\n");
    }

    #[test]
    fn positions_past_the_end_keep_nothing() {
        assert_eq!(keep_cards(THREE, &[7].into_iter().collect()), (String::new(), 0));
    }
}

import Contacts
import Foundation

/// Contacts framework ↔ vCard (CNContactVCardSerialization). Import never deletes anything; the
/// contacts it creates are remembered so the user can remove exactly those in one tap.
enum ContactsService {
    static let store = CNContactStore()
    private static let addedKey = "added_contacts"

    /// Identifiers of contacts this app created. Contacts it merged numbers into are not listed:
    /// removing those would delete the owner's own contact.
    static var added: [String] {
        get { UserDefaults.standard.stringArray(forKey: addedKey) ?? [] }
        set { UserDefaults.standard.set(newValue, forKey: addedKey) }
    }

    static func authorize() async -> String {
        let granted = (try? await store.requestAccess(for: .contacts)) ?? false
        return granted ? state : PermissionState.denied
    }

    static var state: String {
        switch CNContactStore.authorizationStatus(for: .contacts) {
        case .authorized: return PermissionState.granted
        case .notDetermined, .denied, .restricted: return PermissionState.denied
        default: return PermissionState.limited // iOS 18 limited access
        }
    }

    static func exportAll() throws -> (Data, [Entry]) {
        let keys = [CNContactVCardSerialization.descriptorForRequiredKeys(), CNContactFormatter.descriptorForRequiredKeys(for: .fullName)]
        var contacts: [CNContact] = []
        try store.enumerateContacts(with: CNContactFetchRequest(keysToFetch: keys)) { c, _ in contacts.append(c) }
        return (try CNContactVCardSerialization.data(with: contacts), entries(contacts))
    }

    /// The pick list for the PC: entry `i` is the i-th card of the exported vCard.
    static func entries(_ contacts: [CNContact]) -> [Entry] {
        contacts.enumerated().map { i, c in
            let name = CNContactFormatter.string(from: c, style: .fullName) ?? ""
            let detail = c.phoneNumbers.first?.value.stringValue ?? c.emailAddresses.first.map { $0.value as String } ?? ""
            return Entry(id: String(i), name: name.isEmpty ? (c.organizationName.isEmpty ? detail : c.organizationName) : name, detail: detail)
        }
    }

    /// Deletes the contacts this app created (only those; never one it merged into). Returns how many.
    static func removeAdded() throws -> Int {
        let ids = added
        guard !ids.isEmpty else { return 0 }
        let save = CNSaveRequest()
        var removed = 0
        for start in stride(from: 0, to: ids.count, by: 200) {
            let request = CNContactFetchRequest(keysToFetch: [CNContactIdentifierKey as CNKeyDescriptor])
            request.predicate = CNContact.predicateForContacts(withIdentifiers: Array(ids[start..<min(start + 200, ids.count)]))
            // Not unified: a linked card from another account must not be deleted with ours.
            request.unifyResults = false
            try store.enumerateContacts(with: request) { c, _ in
                save.delete(c.mutableCopy() as! CNMutableContact)
                removed += 1
            }
        }
        if removed > 0 { try store.execute(save) }
        added = []
        return removed
    }

    private static func norm(_ phone: String) -> String { String(phone.filter(\.isNumber).suffix(10)) }

    /// merge — add missing numbers/emails to the matching contact; skip — leave it; keepBoth — always add.
    static func importVCards(_ data: Data, policy: String) throws -> AckDetail {
        let incoming = try CNContactVCardSerialization.contacts(with: data)
        let keys: [CNKeyDescriptor] = [
            CNContactPhoneNumbersKey as CNKeyDescriptor, CNContactEmailAddressesKey as CNKeyDescriptor,
            CNContactFormatter.descriptorForRequiredKeys(for: .fullName),
        ]
        var detail = AckDetail()
        let save = CNSaveRequest()
        var pending = 0
        var created: [CNMutableContact] = []
        for c in incoming {
            let name = CNContactFormatter.string(from: c, style: .fullName) ?? ""
            let phones = Set(c.phoneNumbers.map { norm($0.value.stringValue) }.filter { $0.count >= 5 })
            let emails = Set(c.emailAddresses.map { ($0.value as String).lowercased() })
            var match: CNContact?
            if policy != "keepBoth", !name.isEmpty {
                let existing = (try? store.unifiedContacts(matching: CNContact.predicateForContacts(matchingName: name), keysToFetch: keys)) ?? []
                match = existing.first { e in
                    let ep = Set(e.phoneNumbers.map { norm($0.value.stringValue) })
                    let ee = Set(e.emailAddresses.map { ($0.value as String).lowercased() })
                    return (phones.isEmpty && emails.isEmpty) || !ep.isDisjoint(with: phones) || !ee.isDisjoint(with: emails) || (ep.isEmpty && ee.isEmpty)
                }
            }
            if let match {
                if policy == "skip" { detail.skipped += 1; continue }
                let m = match.mutableCopy() as! CNMutableContact
                let have = Set(m.phoneNumbers.map { norm($0.value.stringValue) })
                let haveMail = Set(m.emailAddresses.map { ($0.value as String).lowercased() })
                let newPhones = c.phoneNumbers.filter { !have.contains(norm($0.value.stringValue)) }
                let newMails = c.emailAddresses.filter { !haveMail.contains(($0.value as String).lowercased()) }
                if newPhones.isEmpty && newMails.isEmpty { detail.skipped += 1; continue }
                m.phoneNumbers += newPhones
                m.emailAddresses += newMails
                save.update(m)
                detail.merged += 1
            } else {
                let new = c.mutableCopy() as! CNMutableContact
                save.add(new, toContainerWithIdentifier: nil)
                created.append(new)
                detail.imported += 1
            }
            pending += 1
        }
        if pending > 0 {
            try store.execute(save)
            added += created.map(\.identifier)
        }
        return detail
    }
}

import Contacts
import Foundation

/// Contacts framework ↔ vCard (CNContactVCardSerialization). Import never deletes anything.
enum ContactsService {
    static let store = CNContactStore()

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

    static func exportAll() throws -> (Data, Int) {
        let keys = [CNContactVCardSerialization.descriptorForRequiredKeys()]
        var contacts: [CNContact] = []
        try store.enumerateContacts(with: CNContactFetchRequest(keysToFetch: keys)) { c, _ in contacts.append(c) }
        return (try CNContactVCardSerialization.data(with: contacts), contacts.count)
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
                save.add(c.mutableCopy() as! CNMutableContact, toContainerWithIdentifier: nil)
                detail.imported += 1
            }
            pending += 1
        }
        if pending > 0 { try store.execute(save) }
        return detail
    }
}

import Contacts
import XCTest
@testable import SwitchFlowCompanion

final class ContactsServiceTests: XCTestCase {
    private func contact(_ given: String, _ family: String = "", phone: String? = nil, email: String? = nil, org: String = "") -> CNContact {
        let c = CNMutableContact()
        c.givenName = given
        c.familyName = family
        c.organizationName = org
        if let phone { c.phoneNumbers = [CNLabeledValue(label: CNLabelPhoneNumberMobile, value: CNPhoneNumber(stringValue: phone))] }
        if let email { c.emailAddresses = [CNLabeledValue(label: CNLabelHome, value: email as NSString)] }
        return c
    }

    /// Entry `i` must be the i-th card of the exported vCard: the PC keeps cards by position.
    func testPickListFollowsTheExportOrder() throws {
        let contacts = [
            contact("Алия", "Нурланова", phone: "+77030000001"),
            contact("", email: "info@example.kz", org: "ТОО Ромашка"),
            contact("Бахыт"),
        ]
        let entries = ContactsService.entries(contacts)
        XCTAssertEqual(entries.map(\.id), ["0", "1", "2"])
        XCTAssertEqual(entries[0].detail, "+77030000001")
        XCTAssertEqual(entries[1].name, "ТОО Ромашка", "a company card shows its company name")
        XCTAssertEqual(entries[1].detail, "info@example.kz")
        XCTAssertEqual(entries[2].detail, "")

        let cards = try CNContactVCardSerialization.contacts(with: CNContactVCardSerialization.data(with: contacts))
        XCTAssertEqual(cards.count, entries.count)
        XCTAssertEqual(cards[0].givenName, "Алия")
        XCTAssertEqual(cards[2].givenName, "Бахыт")
    }

    func testInventoryCarriesThePickList() throws {
        let inv = CategoryInventory(category: Category.contacts, permission: PermissionState.granted, count: 1, totalBytes: 10,
                                    entries: [Entry(id: "0", name: "Алия", detail: "+77030000001")])
        let json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(inv)) as? [String: Any]
        let first = (json?["entries"] as? [[String: Any]])?.first
        XCTAssertEqual(first?["name"] as? String, "Алия")
        XCTAssertEqual(first?["id"] as? String, "0")
    }
}

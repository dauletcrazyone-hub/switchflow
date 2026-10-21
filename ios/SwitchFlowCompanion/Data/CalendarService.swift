import EventKit
import Foundation

/// Platform-neutral calendar export (calendar-events.json), shared with the Android app.
struct CalendarEvent: Codable {
    var title: String
    var startMs: Int64
    var endMs: Int64
    var allDay = false
    var timeZone: String?
    var location = ""
    var notes = ""
    var rrule: String?
    var calendarName = ""
}

struct CalendarExport: Codable {
    var version = 1
    var events: [CalendarEvent]
}

/// EventKit. Exports two years back and two years ahead; recurring series are exported once.
enum CalendarService {
    static let store = EKEventStore()

    static func authorize() async -> String {
        if #available(iOS 17.0, *) {
            let ok = (try? await store.requestFullAccessToEvents()) ?? false
            return ok ? PermissionState.granted : PermissionState.denied
        } else {
            let ok = (try? await store.requestAccess(to: .event)) ?? false
            return ok ? PermissionState.granted : PermissionState.denied
        }
    }

    static var state: String {
        let s = EKEventStore.authorizationStatus(for: .event)
        if #available(iOS 17.0, *) { return s == .fullAccess ? PermissionState.granted : PermissionState.denied }
        return s == .authorized ? PermissionState.granted : PermissionState.denied
    }

    private static func ms(_ d: Date) -> Int64 { Int64(d.timeIntervalSince1970 * 1000) }

    static func exportAll() -> [CalendarEvent] {
        let now = Date()
        var seen = Set<String>()
        var out: [CalendarEvent] = []
        // EventKit limits a predicate to four years.
        for (from, to) in [(-730, 0), (0, 730)] {
            let start = Calendar.current.date(byAdding: .day, value: from, to: now)!
            let end = Calendar.current.date(byAdding: .day, value: to, to: now)!
            for e in store.events(matching: store.predicateForEvents(withStart: start, end: end, calendars: nil)) {
                let key = e.hasRecurrenceRules ? (e.calendarItemExternalIdentifier ?? e.eventIdentifier ?? UUID().uuidString) : (e.eventIdentifier ?? UUID().uuidString)
                guard seen.insert(key).inserted else { continue }
                out.append(CalendarEvent(
                    title: e.title ?? "",
                    startMs: ms(e.startDate),
                    endMs: ms(e.endDate),
                    allDay: e.isAllDay,
                    timeZone: e.timeZone?.identifier,
                    location: e.location ?? "",
                    notes: e.notes ?? "",
                    rrule: e.recurrenceRules?.first.flatMap(RRule.string(from:)),
                    calendarName: e.calendar.title
                ))
            }
        }
        return out
    }

    static func importEvents(_ events: [CalendarEvent]) throws -> AckDetail {
        guard let calendar = store.defaultCalendarForNewEvents else { throw TransferError.permissionDenied }
        var detail = AckDetail()
        for ev in events {
            let start = Date(timeIntervalSince1970: TimeInterval(ev.startMs) / 1000)
            let end = Date(timeIntervalSince1970: TimeInterval(max(ev.endMs, ev.startMs)) / 1000)
            let existing = store.events(matching: store.predicateForEvents(withStart: start, end: start.addingTimeInterval(60), calendars: [calendar]))
            if existing.contains(where: { $0.title == ev.title && $0.startDate == start }) {
                detail.skipped += 1
                continue
            }
            let e = EKEvent(eventStore: store)
            e.calendar = calendar
            e.title = ev.title
            e.startDate = start
            e.endDate = end
            e.isAllDay = ev.allDay
            if let tz = ev.timeZone, !ev.allDay { e.timeZone = TimeZone(identifier: tz) }
            e.location = ev.location.isEmpty ? nil : ev.location
            e.notes = ev.notes.isEmpty ? nil : ev.notes
            if let r = ev.rrule.flatMap(RRule.parse) { e.addRecurrenceRule(r) }
            try store.save(e, span: .thisEvent, commit: false)
            detail.imported += 1
        }
        try store.commit()
        return detail
    }
}

/// The common subset of RFC 5545 RRULE: FREQ, INTERVAL, COUNT, UNTIL, BYDAY.
enum RRule {
    private static let days: [(String, EKWeekday)] = [("SU", .sunday), ("MO", .monday), ("TU", .tuesday), ("WE", .wednesday), ("TH", .thursday), ("FR", .friday), ("SA", .saturday)]

    static func string(from r: EKRecurrenceRule) -> String? {
        let freq: String
        switch r.frequency {
        case .daily: freq = "DAILY"
        case .weekly: freq = "WEEKLY"
        case .monthly: freq = "MONTHLY"
        case .yearly: freq = "YEARLY"
        @unknown default: return nil
        }
        var parts = ["FREQ=\(freq)"]
        if r.interval > 1 { parts.append("INTERVAL=\(r.interval)") }
        if let end = r.recurrenceEnd {
            if end.occurrenceCount > 0 { parts.append("COUNT=\(end.occurrenceCount)") }
            else if let d = end.endDate { parts.append("UNTIL=\(untilFormatter.string(from: d))") }
        }
        if let wd = r.daysOfTheWeek, !wd.isEmpty {
            parts.append("BYDAY=" + wd.compactMap { d in days.first { $0.1 == d.dayOfTheWeek }?.0 }.joined(separator: ","))
        }
        return parts.joined(separator: ";")
    }

    static func parse(_ s: String) -> EKRecurrenceRule? {
        var kv: [String: String] = [:]
        for p in s.replacingOccurrences(of: "RRULE:", with: "").split(separator: ";") {
            let pair = p.split(separator: "=", maxSplits: 1).map(String.init)
            if pair.count == 2 { kv[pair[0].uppercased()] = pair[1] }
        }
        let freq: EKRecurrenceFrequency
        switch kv["FREQ"] {
        case "DAILY": freq = .daily
        case "WEEKLY": freq = .weekly
        case "MONTHLY": freq = .monthly
        case "YEARLY": freq = .yearly
        default: return nil
        }
        var end: EKRecurrenceEnd?
        if let c = kv["COUNT"].flatMap(Int.init) { end = EKRecurrenceEnd(occurrenceCount: c) }
        else if let u = kv["UNTIL"].flatMap(parseUntil) { end = EKRecurrenceEnd(end: u) }
        let byDay = kv["BYDAY"]?.split(separator: ",").compactMap { token -> EKRecurrenceDayOfWeek? in
            let code = String(token.suffix(2))
            return days.first { $0.0 == code }.map { EKRecurrenceDayOfWeek($0.1) }
        }
        return EKRecurrenceRule(recurrenceWith: freq, interval: max(1, kv["INTERVAL"].flatMap(Int.init) ?? 1),
                                daysOfTheWeek: byDay?.isEmpty == false ? byDay : nil, daysOfTheMonth: nil, monthsOfTheYear: nil,
                                weeksOfTheYear: nil, daysOfTheYear: nil, setPositions: nil, end: end)
    }

    private static let untilFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        return f
    }()

    private static func parseUntil(_ s: String) -> Date? {
        if let d = untilFormatter.date(from: s) { return d }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyyMMdd"
        return f.date(from: s)
    }
}

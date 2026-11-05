import XCTest
@testable import PodsCore

/// Те же реальные пакеты и строки, что в тестах Android-приложения и программы для Windows.
final class PodsCoreTests: XCTestCase {
    private func parse(_ hex: String) -> ProximityPairingMessage {
        let clean = hex.replacingOccurrences(of: " ", with: "")
        var bytes: [UInt8] = []
        var index = clean.startIndex
        while index < clean.endIndex {
            let next = clean.index(index, offsetBy: 2)
            bytes.append(UInt8(clean[index..<next], radix: 16)!)
            index = next
        }
        return ProximityPairingParser.parse(bytes)!
    }

    func testAirPods4AncBothInEars() {
        let msg = parse("07 19 01 1B 20 2B AA 8F 10 00 05 FF 16 AA 81 5B 82 AC 9D 48 35 32 15 4C 62 EF 55")
        XCTAssertEqual(msg.model?.displayName, "AirPods 4 (ANC)")
        XCTAssertEqual(msg.left, PodState(battery: 100, charging: false, inEar: true))
        XCTAssertEqual(msg.right, PodState(battery: 100, charging: false, inEar: true))
        XCTAssertNil(msg.caseBattery)
        XCTAssertTrue(msg.worn)
    }

    func testAirPods4AncRightInCase() {
        let msg = parse("07 19 01 1B 20 53 9A 95 3A 00 04 25 2D 6E 55 47 30 EC 44 3F 48 99 E3 6D F8 D6 4F")
        XCTAssertEqual(msg.right, PodState(battery: 100, charging: true, inEar: false, inCase: true))
        XCTAssertEqual(msg.left, PodState(battery: 90, charging: false, inEar: false))
        XCTAssertEqual(msg.caseBattery, 50)
    }

    func testAirPodsMaxOnAndOffHead() {
        let on = parse("07 19 01 1F 20 2B 0A 80 04 56 44 6D 43 28 CB 88 F3 EE EA 7D 1C 82 BE C6 F4 1E B7")
        XCTAssertEqual(on.model?.kind, .single)
        XCTAssertEqual(on.primary, PodState(battery: 100, charging: false, inEar: true))
        XCTAssertTrue(on.worn)
        let off = parse("07 19 01 1F 20 01 09 80 04 56 44 3E 0B 61 8E 7D D8 92 72 35 C2 41 8A CA 5E FE 5E")
        XCTAssertEqual(off.primary, PodState(battery: 90, charging: false, inEar: false))
        XCTAssertFalse(off.worn)
        XCTAssertEqual(off.lowestBattery, 90)
    }

    func testManufacturerDataWithCompanyId() {
        let data = Data([0x4C, 0x00, 0x07, 0x19, 0x01, 0x1F, 0x20, 0x2B, 0x0A, 0x80, 0x04, 0x56, 0x44, 0x6D, 0x43, 0x28,
                         0xCB, 0x88, 0xF3, 0xEE, 0xEA, 0x7D, 0x1C, 0x82, 0xBE, 0xC6, 0xF4, 0x1E, 0xB7])
        XCTAssertEqual(ProximityPairingParser.parseManufacturerData(data)?.model?.displayName, "AirPods Max (USB-C)")
        XCTAssertNil(ProximityPairingParser.parseManufacturerData(Data([0x06, 0x00] + [UInt8](data[2...]))))
        XCTAssertNil(ProximityPairingParser.parse([0x10, 0x05, 0x03, 0x1C]))
    }

    func testNameMatcherUsesProductLine() {
        func has(_ name: String, _ model: String) -> Bool { NameMatcher.models(forName: name).contains { $0.displayName == model } }
        XCTAssertTrue(has("AirPods", "AirPods 4 (ANC)"))
        XCTAssertFalse(has("AirPods", "AirPods Max (USB-C)"))
        XCTAssertTrue(has("AirPods Max", "AirPods Max (USB-C)"))
        XCTAssertFalse(has("AirPods Max", "AirPods Pro 2"))
        XCTAssertTrue(has("AirPods Pro Beyren", "AirPods Pro 2"))
        XCTAssertFalse(NameMatcher.looksLikePods("Logitech Mouse"))
    }

    func testNearbyTrackerPicksStrongestFresh() {
        let msg = parse("07 19 01 1F 20 2B 0A 80 04 56 44 6D 43 28 CB 88 F3 EE EA 7D 1C 82 BE C6 F4 1E B7")
        let tracker = NearbyTracker()
        let now = Date()
        tracker.onPacket(id: "a", message: msg, rssi: -70, now: now)
        tracker.onPacket(id: "b", message: msg, rssi: -50, now: now.addingTimeInterval(-30))
        tracker.onPacket(id: "c", message: msg, rssi: -90, now: now)
        XCTAssertEqual(tracker.best(models: [msg.model!], now: now)?.id, "a")
    }

    func testPhoneSignalSameBytesAsPhone() {
        // Тот же пример в PcSignalTest.kt и ParserTests.cs.
        XCTAssertEqual(PhoneSignal.androidAddress(0x70F94A9D3392), "70:F9:4A:9D:33:92")
        XCTAssertEqual(PhoneSignal.parseAddress("70-f9-4a-9d-33-92"), 0x70F94A9D3392)
        let hex = PhoneSignal.payload(address: 0x70F94A9D3392, nonce: 0x1234).map { String(format: "%02X", $0) }.joined()
        XCTAssertEqual(hex, "504353570101E5D3F5161234")
    }

    func testLinkMessageSameTextAsPhone() {
        let text = "PCLINK 1 hello 3f9a12c0 pc e5d3f516:1,0a0b0c0d:0 DESKTOP ASUS"
        let message = LinkMessage.parse(text)!
        XCTAssertEqual(message.type, .hello)
        XCTAssertEqual(message.kind, .pc)
        XCTAssertEqual(message.pods, [LinkPods(hash: "e5d3f516", here: true), LinkPods(hash: "0a0b0c0d", here: false)])
        XCTAssertEqual(message.name, "DESKTOP ASUS")
        XCTAssertEqual(message.encode(), text)
        XCTAssertEqual(LinkMessage.hash(of: 0x70F94A9D3392), "e5d3f516")
        XCTAssertNil(LinkMessage.parse("PCLINK 1 jump 1 pc - X"))
        XCTAssertEqual(LinkMessage(type: .hi, id: "1", kind: .phone, pods: [], name: "Pixel 9").encode(), "PCLINK 1 hi 1 phone - Pixel 9")
    }

    func testLinkMessageBatteryModeAndCall() {
        let line = "PCLINK 1 hello 1 phone e5d3f516:1:96:anc,0a0b0c0d:0:80/-/60:- Pixel 9"
        let message = LinkMessage.parse(line)!
        XCTAssertEqual(message.pods, [LinkPods(hash: "e5d3f516", here: true, battery: "96", mode: "anc"),
                                      LinkPods(hash: "0a0b0c0d", here: false, battery: "80/-/60")])
        XCTAssertEqual(message.encode(), line)
        XCTAssertEqual(LinkMessage.parse("PCLINK 1 mode 2 pc e5d3f516:0:-:transparency DESKTOP")?.type, .mode)
        let call = "PCLINK 1 hello 3f9a12c0 pc e5d3f516:2 MacBook Air"
        XCTAssertEqual(LinkMessage.parse(call)?.pods.first, LinkPods(hash: "e5d3f516", here: true, inCall: true))
        XCTAssertEqual(LinkMessage.parse(call)?.encode(), call)
    }

    func testAutoSwitchRule() {
        var rule = PlaybackStartDetector(sustain: 2)
        let t0 = Date()
        XCTAssertFalse(rule.update(running: true, now: t0))
        XCTAssertFalse(rule.update(running: true, now: t0.addingTimeInterval(1)))
        XCTAssertTrue(rule.update(running: true, now: t0.addingTimeInterval(2.1)))
        XCTAssertFalse(rule.update(running: true, now: t0.addingTimeInterval(5)), "один раз на каждое начало звука")
        XCTAssertFalse(rule.update(running: false, now: t0.addingTimeInterval(6)))
        XCTAssertFalse(rule.update(running: true, now: t0.addingTimeInterval(7)))
        XCTAssertFalse(rule.update(running: false, now: t0.addingTimeInterval(7.5)), "короткий звук уведомления")
        XCTAssertFalse(rule.update(running: true, now: t0.addingTimeInterval(8)))
        XCTAssertTrue(rule.update(running: true, now: t0.addingTimeInterval(10.5)))
    }
}

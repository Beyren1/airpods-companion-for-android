import Darwin
import Foundation

/// IPv4-адрес в порядке байт сети (как в sockaddr_in).
struct IPv4: Hashable, CustomStringConvertible {
    let raw: in_addr_t

    static let broadcast = IPv4(raw: 0xFFFF_FFFF)

    var description: String {
        let host = UInt32(bigEndian: raw)
        return "\(host >> 24 & 0xFF).\(host >> 16 & 0xFF).\(host >> 8 & 0xFF).\(host & 0xFF)"
    }

    /// Широковещательные адреса включённых сетей IPv4 (Wi-Fi, Ethernet), например 192.168.1.255.
    /// Общий 255.255.255.255 macOS шлёт только в одну сеть, и это может быть VPN, а не Wi-Fi с телефоном.
    static func broadcastAddresses() -> [IPv4] {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return [] }
        defer { freeifaddrs(list) }
        var result: [IPv4] = []
        var item: UnsafeMutablePointer<ifaddrs>? = first
        while let current = item {
            let entry = current.pointee
            let flags = Int32(entry.ifa_flags)
            if flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0, flags & IFF_BROADCAST != 0,
               let addr = entry.ifa_addr, addr.pointee.sa_family == sa_family_t(AF_INET),
               let broadcast = entry.ifa_dstaddr {
                let value = broadcast.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
                if value != 0 { result.append(IPv4(raw: value)) }
            }
            item = entry.ifa_next
        }
        return Array(Set(result))
    }
}

/// UDP-сокет на порту связи: принимает на всех сетях, умеет слать широковещательно.
@MainActor
final class UdpSocket {
    private var fd: Int32 = -1
    private var source: DispatchSourceRead?

    /// Пришёл пакет: байты и откуда.
    var onReceive: (([UInt8], IPv4) -> Void)?

    /// nil — открылся; иначе текст ошибки.
    func open(port: UInt16) -> String? {
        fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return "socket: \(String(cString: strerror(errno)))" }
        var yes: Int32 = 1
        let size = socklen_t(MemoryLayout<Int32>.size)
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, size)
        setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &yes, size)
        setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &yes, size)
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr = in_addr(s_addr: 0)
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard bound == 0 else {
            let error = String(cString: strerror(errno))
            Darwin.close(fd)
            fd = -1
            return "bind \(port): \(error)"
        }
        let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: .main)
        source.setEventHandler { [weak self] in
            MainActor.assumeIsolated { self?.readAll() }
        }
        source.resume()
        self.source = source
        return nil
    }

    private func readAll() {
        var buffer = [UInt8](repeating: 0, count: 2048)
        while true {
            var from = sockaddr_in()
            var fromSize = socklen_t(MemoryLayout<sockaddr_in>.size)
            let count = withUnsafeMutablePointer(to: &from) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    recvfrom(fd, &buffer, buffer.count, MSG_DONTWAIT, $0, &fromSize)
                }
            }
            guard count > 0 else { return }
            onReceive?(Array(buffer[0..<count]), IPv4(raw: from.sin_addr.s_addr))
        }
    }

    /// 0 — отправлено, иначе errno.
    @discardableResult
    func send(_ bytes: [UInt8], to address: IPv4, port: UInt16) -> Int32 {
        guard fd >= 0 else { return EBADF }
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr = in_addr(s_addr: address.raw)
        let sent = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                sendto(fd, bytes, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        return sent < 0 ? errno : 0
    }

    func close() {
        source?.cancel()
        source = nil
        if fd >= 0 { Darwin.close(fd) }
        fd = -1
    }
}

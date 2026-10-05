import Foundation
import Darwin

/// Wake-on-LAN. Увага: на iOS 14+ broadcast потребує entitlement
/// com.apple.developer.networking.multicast від Apple — без нього sendto
/// повертає помилку. Тоді увімкнути ТВ з iPhone не вийде.
enum WakeOnLan {
    struct WolError: LocalizedError {
        let errorDescription: String?
    }

    static func send(mac: String, tvIp: String) throws {
        let bytes = mac.split(whereSeparator: { $0 == ":" || $0 == "-" })
            .compactMap { UInt8($0, radix: 16) }
        guard bytes.count == 6 else { throw WolError(errorDescription: "Невірна MAC-адреса") }

        var packet = [UInt8](repeating: 0xFF, count: 6)
        for _ in 0..<16 { packet += bytes }

        var targets = ["255.255.255.255"]
        let octets = tvIp.split(separator: ".")
        if octets.count == 4 { targets.append(octets.prefix(3).joined(separator: ".") + ".255") }

        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { throw WolError(errorDescription: "Не вдалося відкрити сокет") }
        defer { close(fd) }

        var on: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &on, socklen_t(MemoryLayout<Int32>.size))

        var sent = false
        var lastErr = ""
        for target in targets {
            var addr = sockaddr_in()
            addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            addr.sin_family = sa_family_t(AF_INET)
            addr.sin_port = in_port_t(9).bigEndian
            addr.sin_addr.s_addr = inet_addr(target)
            for _ in 0..<3 {
                let n = packet.withUnsafeBytes { buf -> Int in
                    withUnsafePointer(to: &addr) { ptr in
                        ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                            sendto(fd, buf.baseAddress, buf.count, 0, $0,
                                   socklen_t(MemoryLayout<sockaddr_in>.size))
                        }
                    }
                }
                if n > 0 { sent = true } else { lastErr = String(cString: strerror(errno)) }
            }
        }
        if !sent {
            throw WolError(errorDescription: "iOS заблокував broadcast (\(lastErr)). Увімкніть ТВ пультом.")
        }
    }
}

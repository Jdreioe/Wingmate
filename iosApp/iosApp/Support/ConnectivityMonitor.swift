import Foundation
import Network

final class ConnectivityMonitor {
    static let shared = ConnectivityMonitor()

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "ConnectivityMonitor")
    private(set) var isOnline: Bool = true

    private var listeners: [(Bool) -> Void] = []

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }
            let online = path.status == .satisfied
            if online != self.isOnline {
                self.isOnline = online
                let callbacks = self.listeners
                DispatchQueue.main.async { callbacks.forEach { $0(online) } }
            } else {
                self.isOnline = online
            }
        }
        monitor.start(queue: queue)
    }

    func onChange(_ block: @escaping (Bool) -> Void) {
        listeners.append(block)
        // Emit current state immediately on main
        DispatchQueue.main.async { block(self.isOnline) }
    }
}

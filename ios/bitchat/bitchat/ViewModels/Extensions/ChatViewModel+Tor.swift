import Foundation
import Combine
import Tor

extension ChatViewModel {

    @objc func handleTorWillStart() {
        Task { @MainActor in
            if !self.torStatusAnnounced && TorManager.shared.torEnforced {
                self.torStatusAnnounced = true

                self.addGeohashOnlySystemMessage(
                    String(localized: "system.tor.starting", comment: "System message when Tor is starting")
                )
            }
        }
    }

    @objc func handleTorWillRestart() {
        Task { @MainActor in
            self.torRestartPending = true

            self.addGeohashOnlySystemMessage(
                String(localized: "system.tor.restarting", comment: "System message when Tor is restarting")
            )
        }
    }

    @objc func handleTorDidBecomeReady() {
        Task { @MainActor in

            if self.torRestartPending {

                self.addGeohashOnlySystemMessage(
                    String(localized: "system.tor.restarted", comment: "System message when Tor has restarted")
                )
                self.torRestartPending = false
            } else if TorManager.shared.torEnforced && !self.torInitialReadyAnnounced {

                self.addGeohashOnlySystemMessage(
                    String(localized: "system.tor.started", comment: "System message when Tor has started")
                )
                self.torInitialReadyAnnounced = true
            }
        }
    }

    @objc func handleTorPreferenceChanged(_ notification: Notification) {
        Task { @MainActor in
            self.torStatusAnnounced = false
            self.torInitialReadyAnnounced = false
            self.torRestartPending = false
        }
    }
}

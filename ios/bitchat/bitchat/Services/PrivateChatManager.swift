import BitLogger
import BitFoundation
import Foundation
import SwiftUI

final class PrivateChatManager: ObservableObject {
    @Published var privateChats: [PeerID: [BitchatMessage]] = [:]
    @Published var selectedPeer: PeerID? = nil
    @Published var unreadMessages: Set<PeerID> = []

    private var selectedPeerFingerprint: String? = nil
    var sentReadReceipts: Set<String> = []

    weak var meshService: Transport?

    weak var messageRouter: MessageRouter?

    weak var unifiedPeerService: UnifiedPeerService?

    init(meshService: Transport? = nil) {
        self.meshService = meshService
    }

    private let privateChatCap = TransportConfig.privateChatCap

    @MainActor
    func consolidateMessages(for peerID: PeerID, peerNickname: String, persistedReadReceipts: Set<String>) -> Bool {
        guard let meshService = meshService else { return false }
        var hasUnreadMessages = false

        if let peer = unifiedPeerService?.getPeer(by: peerID) {
            let noiseKeyHex = PeerID(hexData: peer.noisePublicKey)

            if noiseKeyHex != peerID, let nostrMessages = privateChats[noiseKeyHex], !nostrMessages.isEmpty {
                if privateChats[peerID] == nil {
                    privateChats[peerID] = []
                }

                let existingMessageIds = Set(privateChats[peerID]?.map { $0.id } ?? [])
                for message in nostrMessages {
                    if !existingMessageIds.contains(message.id) {

                        let updatedMessage = BitchatMessage(
                            id: message.id,
                            sender: message.sender,
                            content: message.content,
                            timestamp: message.timestamp,
                            isRelay: message.isRelay,
                            originalSender: message.originalSender,
                            isPrivate: message.isPrivate,
                            recipientNickname: message.recipientNickname,
                            senderPeerID: message.senderPeerID == meshService.myPeerID ? meshService.myPeerID : peerID,
                            mentions: message.mentions,
                            deliveryStatus: message.deliveryStatus
                        )
                        privateChats[peerID]?.append(updatedMessage)

                        if message.senderPeerID != meshService.myPeerID {
                            let messageAge = Date().timeIntervalSince(message.timestamp)
                            if messageAge < 60 && !persistedReadReceipts.contains(message.id) {
                                hasUnreadMessages = true
                            }
                        }
                    }
                }

                privateChats[peerID]?.sort { $0.timestamp < $1.timestamp }

                if hasUnreadMessages {
                    unreadMessages.insert(peerID)
                } else if unreadMessages.contains(noiseKeyHex) {
                    unreadMessages.remove(noiseKeyHex)
                }

                privateChats.removeValue(forKey: noiseKeyHex)
            }
        }

        let normalizedNickname = peerNickname.lowercased()
        var tempPeerIDsToConsolidate: [PeerID] = []

        for (storedPeerID, messages) in privateChats {
            if storedPeerID.isGeoDM && storedPeerID != peerID {
                let nicknamesMatch = messages.allSatisfy { $0.sender.lowercased() == normalizedNickname }
                if nicknamesMatch && !messages.isEmpty {
                    tempPeerIDsToConsolidate.append(storedPeerID)
                }
            }
        }

        if !tempPeerIDsToConsolidate.isEmpty {
            if privateChats[peerID] == nil {
                privateChats[peerID] = []
            }

            let existingMessageIds = Set(privateChats[peerID]?.map { $0.id } ?? [])
            var consolidatedCount = 0
            var hadUnreadTemp = false

            for tempPeerID in tempPeerIDsToConsolidate {
                if unreadMessages.contains(tempPeerID) {
                    hadUnreadTemp = true
                }

                if let tempMessages = privateChats[tempPeerID] {
                    for message in tempMessages {
                        if !existingMessageIds.contains(message.id) {
                            let updatedMessage = BitchatMessage(
                                id: message.id,
                                sender: message.sender,
                                content: message.content,
                                timestamp: message.timestamp,
                                isRelay: message.isRelay,
                                originalSender: message.originalSender,
                                isPrivate: message.isPrivate,
                                recipientNickname: message.recipientNickname,
                                senderPeerID: peerID,
                                mentions: message.mentions,
                                deliveryStatus: message.deliveryStatus
                            )
                            privateChats[peerID]?.append(updatedMessage)
                            consolidatedCount += 1
                        }
                    }
                    privateChats.removeValue(forKey: tempPeerID)
                    unreadMessages.remove(tempPeerID)
                }
            }

            if hadUnreadTemp {
                unreadMessages.insert(peerID)
                hasUnreadMessages = true
                SecureLogger.debug("Transferred unread status from temp peer IDs to \(peerID)", category: .session)
            }

            if consolidatedCount > 0 {
                privateChats[peerID]?.sort { $0.timestamp < $1.timestamp }
                SecureLogger.info("Consolidated \(consolidatedCount) Nostr messages from temporary peer IDs to \(peerNickname)", category: .session)
            }
        }

        return hasUnreadMessages
    }

    @MainActor
    func syncReadReceiptsForSentMessages(peerID: PeerID, nickname: String, externalReceipts: inout Set<String>) {
        guard let messages = privateChats[peerID] else { return }

        for message in messages {
            if message.sender == nickname {
                if let status = message.deliveryStatus {
                    switch status {
                    case .read, .delivered:
                        externalReceipts.insert(message.id)
                        sentReadReceipts.insert(message.id)
                    case .failed, .partiallyDelivered, .sending, .sent:
                        break
                    }
                }
            }
        }
    }

    func startChat(with peerID: PeerID) {
        selectedPeer = peerID

        if let fingerprint = meshService?.getFingerprint(for: peerID) {
            selectedPeerFingerprint = fingerprint
        }

        markAsRead(from: peerID)

        if privateChats[peerID] == nil {
            privateChats[peerID] = []
        }
    }

    func endChat() {
        selectedPeer = nil
        selectedPeerFingerprint = nil
    }

    func sanitizeChat(for peerID: PeerID) {
        guard let arr = privateChats[peerID] else { return }
        if arr.count <= 1 {
            return
        }

        var indexByID: [String: Int] = [:]
        indexByID.reserveCapacity(arr.count)
        var deduped: [BitchatMessage] = []
        deduped.reserveCapacity(arr.count)

        for msg in arr.sorted(by: { $0.timestamp < $1.timestamp }) {
            if let existing = indexByID[msg.id] {
                deduped[existing] = msg
            } else {
                indexByID[msg.id] = deduped.count
                deduped.append(msg)
            }
        }

        privateChats[peerID] = deduped
    }

    func markAsRead(from peerID: PeerID) {
        unreadMessages.remove(peerID)

        if let messages = privateChats[peerID] {
            for message in messages {
                if message.senderPeerID == peerID && !message.isRelay && !sentReadReceipts.contains(message.id) {
                    sendReadReceipt(for: message)
                }
            }
        }
    }

    private func sendReadReceipt(for message: BitchatMessage) {
        guard !sentReadReceipts.contains(message.id),
              let senderPeerID = message.senderPeerID else {
            return
        }

        sentReadReceipts.insert(message.id)

        let receipt = ReadReceipt(
            originalMessageID: message.id,
            readerID: meshService?.myPeerID ?? PeerID(str: ""),
            readerNickname: meshService?.myNickname ?? ""
        )

        if let router = messageRouter {
            SecureLogger.debug("PrivateChatManager: sending READ ack for \(message.id.prefix(8))… to \(senderPeerID.id.prefix(8))… via router", category: .session)
            Task { @MainActor in
                router.sendReadReceipt(receipt, to: senderPeerID)
            }
        } else {

            meshService?.sendReadReceipt(receipt, to: senderPeerID)
        }
    }
}

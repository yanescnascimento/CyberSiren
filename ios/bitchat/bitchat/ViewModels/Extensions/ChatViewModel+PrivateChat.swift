import Foundation
import Combine
import BitLogger
import BitFoundation
import SwiftUI

extension ChatViewModel {

    @MainActor
    func sendPrivateMessage(_ content: String, to peerID: PeerID) {
        guard !content.isEmpty else { return }

        if unifiedPeerService.isBlocked(peerID) {
            let nickname = meshService.peerNickname(peerID: peerID) ?? "user"
            addSystemMessage(
                String(
                    format: String(localized: "system.dm.blocked_recipient", comment: "System message when attempting to message a blocked user"),
                    locale: .current,
                    nickname
                )
            )
            return
        }

        if peerID.isGeoDM {
            sendGeohashDM(content, to: peerID)
            return
        }

        guard let noiseKey = Data(hexString: peerID.id) else { return }
        let isConnected = meshService.isPeerConnected(peerID)
        let isReachable = meshService.isPeerReachable(peerID)
        let favoriteStatus = FavoritesPersistenceService.shared.getFavoriteStatus(for: noiseKey)
        let isMutualFavorite = favoriteStatus?.isMutual ?? false
        let hasNostrKey = favoriteStatus?.peerNostrPublicKey != nil

        var recipientNickname = meshService.peerNickname(peerID: peerID)
        if recipientNickname == nil && favoriteStatus != nil {
            recipientNickname = favoriteStatus?.peerNickname
        }
        recipientNickname = recipientNickname ?? "user"

        let messageID = UUID().uuidString

        let message = BitchatMessage(
            id: messageID,
            sender: nickname,
            content: content,
            timestamp: Date(),
            isRelay: false,
            originalSender: nil,
            isPrivate: true,
            recipientNickname: recipientNickname,
            senderPeerID: meshService.myPeerID,
            mentions: nil,
            deliveryStatus: .sending
        )

        if privateChats[peerID] == nil {
            privateChats[peerID] = []
        }
        privateChats[peerID]?.append(message)

        objectWillChange.send()

        if isConnected || isReachable || (isMutualFavorite && hasNostrKey) {
            messageRouter.sendPrivate(content, to: peerID, recipientNickname: recipientNickname ?? "user", messageID: messageID)

            if let idx = privateChats[peerID]?.firstIndex(where: { $0.id == messageID }) {
                privateChats[peerID]?[idx].deliveryStatus = .sent
            }
        } else {

            if let index = privateChats[peerID]?.firstIndex(where: { $0.id == messageID }) {
                privateChats[peerID]?[index].deliveryStatus = .failed(
                    reason: String(localized: "content.delivery.reason.unreachable", comment: "Failure reason when a peer is unreachable")
                )
            }
            let name = recipientNickname ?? "user"
            addSystemMessage(
                String(
                    format: String(localized: "system.dm.unreachable", comment: "System message when a recipient is unreachable"),
                    locale: .current,
                    name
                )
            )
        }
    }

    func sendGeohashDM(_ content: String, to peerID: PeerID) {
        guard case .location(let ch) = activeChannel else {
            addSystemMessage(
                String(localized: "system.location.not_in_channel", comment: "System message when attempting to send without being in a location channel")
            )
            return
        }
        let messageID = UUID().uuidString

        let message = BitchatMessage(
            id: messageID,
            sender: nickname,
            content: content,
            timestamp: Date(),
            isRelay: false,
            isPrivate: true,
            recipientNickname: nickname,
            senderPeerID: meshService.myPeerID,
            deliveryStatus: .sending
        )

        if privateChats[peerID] == nil {
            privateChats[peerID] = []
        }

        privateChats[peerID]?.append(message)
        objectWillChange.send()

        guard let recipientHex = nostrKeyMapping[peerID] else {
            if let msgIdx = privateChats[peerID]?.firstIndex(where: { $0.id == messageID }) {
                privateChats[peerID]?[msgIdx].deliveryStatus = .failed(
                    reason: String(localized: "content.delivery.reason.unknown_recipient", comment: "Failure reason when the recipient is unknown")
                )
            }
            return
        }

        if identityManager.isNostrBlocked(pubkeyHexLowercased: recipientHex) {
            if let msgIdx = privateChats[peerID]?.firstIndex(where: { $0.id == messageID }) {
                privateChats[peerID]?[msgIdx].deliveryStatus = .failed(
                    reason: String(localized: "content.delivery.reason.blocked", comment: "Failure reason when the user is blocked")
                )
            }
            addSystemMessage(
                String(localized: "system.dm.blocked_generic", comment: "System message when sending fails because user is blocked")
            )
            return
        }

        do {
            let id = try idBridge.deriveIdentity(forGeohash: ch.geohash)

            if recipientHex.lowercased() == id.publicKeyHex.lowercased() {
                if let idx = privateChats[peerID]?.firstIndex(where: { $0.id == messageID }) {
                privateChats[peerID]?[idx].deliveryStatus = .failed(
                    reason: String(localized: "content.delivery.reason.self", comment: "Failure reason when attempting to message yourself")
                )
            }
                return
            }
            SecureLogger.debug("GeoDM: local send mid=\(messageID.prefix(8))… to=\(recipientHex.prefix(8))… conv=\(peerID)", category: .session)
            let nostrTransport = NostrTransport(keychain: keychain, idBridge: idBridge)
            nostrTransport.senderPeerID = meshService.myPeerID
            nostrTransport.sendPrivateMessageGeohash(content: content, toRecipientHex: recipientHex, from: id, messageID: messageID)
            if let msgIdx = privateChats[peerID]?.firstIndex(where: { $0.id == messageID }) {
                privateChats[peerID]?[msgIdx].deliveryStatus = .sent
            }
        } catch {
            if let idx = privateChats[peerID]?.firstIndex(where: { $0.id == messageID }) {
                privateChats[peerID]?[idx].deliveryStatus = .failed(
                    reason: String(localized: "content.delivery.reason.send_error", comment: "Failure reason for a generic send error")
                )
            }
        }
    }

    func handlePrivateMessage(
        _ payload: NoisePayload,
        senderPubkey: String,
        convKey: PeerID,
        id: NostrIdentity,
        messageTimestamp: Date
    ) {
        guard let pm = PrivateMessagePacket.decode(from: payload.data) else { return }
        let messageId = pm.messageID

        SecureLogger.info("GeoDM: recv PM <- sender=\(senderPubkey.prefix(8))… mid=\(messageId.prefix(8))…", category: .session)

        sendDeliveryAckIfNeeded(to: messageId, senderPubKey: senderPubkey, from: id)

        if identityManager.isNostrBlocked(pubkeyHexLowercased: senderPubkey) {
            return
        }

        if privateChats[convKey]?.contains(where: { $0.id == messageId }) == true { return }
        for (_, arr) in privateChats {
            if arr.contains(where: { $0.id == messageId }) {
                return
            }
        }

        let senderName = displayNameForNostrPubkey(senderPubkey)
        let msg = BitchatMessage(
            id: messageId,
            sender: senderName,
            content: pm.content,
            timestamp: messageTimestamp,
            isRelay: false,
            isPrivate: true,
            recipientNickname: nickname,
            senderPeerID: convKey,
            deliveryStatus: .delivered(to: nickname, at: Date())
        )

        if privateChats[convKey] == nil {
            privateChats[convKey] = []
        }
        privateChats[convKey]?.append(msg)

        let isViewing = selectedPrivateChatPeer == convKey
        let wasReadBefore = sentReadReceipts.contains(messageId)
        let isRecentMessage = Date().timeIntervalSince(messageTimestamp) < 30
        let shouldMarkUnread = !wasReadBefore && !isViewing && isRecentMessage
        if shouldMarkUnread {
            unreadPrivateMessages.insert(convKey)
        }

        if isViewing {
            sendReadReceiptIfNeeded(to: messageId, senderPubKey: senderPubkey, from: id)
        }

        if !isViewing && shouldMarkUnread {
            NotificationService.shared.sendPrivateMessageNotification(
                from: senderName,
                message: pm.content,
                peerID: convKey
            )
        }

        objectWillChange.send()
    }

    func handleDelivered(_ payload: NoisePayload, senderPubkey: String, convKey: PeerID) {
        guard let messageID = String(data: payload.data, encoding: .utf8) else { return }

        if let idx = privateChats[convKey]?.firstIndex(where: { $0.id == messageID }) {
            privateChats[convKey]?[idx].deliveryStatus = .delivered(to: displayNameForNostrPubkey(senderPubkey), at: Date())
            objectWillChange.send()
            SecureLogger.info("GeoDM: recv DELIVERED for mid=\(messageID.prefix(8))… from=\(senderPubkey.prefix(8))…", category: .session)
        } else {
            SecureLogger.warning("GeoDM: delivered ack for unknown mid=\(messageID.prefix(8))… conv=\(convKey)", category: .session)
        }
    }

    func handleReadReceipt(_ payload: NoisePayload, senderPubkey: String, convKey: PeerID) {
        guard let messageID = String(data: payload.data, encoding: .utf8) else { return }

        if let idx = privateChats[convKey]?.firstIndex(where: { $0.id == messageID }) {
            privateChats[convKey]?[idx].deliveryStatus = .read(by: displayNameForNostrPubkey(senderPubkey), at: Date())
            objectWillChange.send()
            SecureLogger.info("GeoDM: recv READ for mid=\(messageID.prefix(8))… from=\(senderPubkey.prefix(8))…", category: .session)
        } else {
            SecureLogger.warning("GeoDM: read ack for unknown mid=\(messageID.prefix(8))… conv=\(convKey)", category: .session)
        }
    }

    func sendDeliveryAckIfNeeded(to messageId: String, senderPubKey: String, from id: NostrIdentity) {
        guard !sentGeoDeliveryAcks.contains(messageId) else { return }
        let nt = NostrTransport(keychain: keychain, idBridge: idBridge)
        nt.senderPeerID = meshService.myPeerID
        nt.sendDeliveryAckGeohash(for: messageId, toRecipientHex: senderPubKey, from: id)
        sentGeoDeliveryAcks.insert(messageId)
    }

    func sendReadReceiptIfNeeded(to messageId: String, senderPubKey: String, from id: NostrIdentity) {
        guard !sentReadReceipts.contains(messageId) else { return }
        let nt = NostrTransport(keychain: keychain, idBridge: idBridge)
        nt.senderPeerID = meshService.myPeerID
        nt.sendReadReceiptGeohash(messageId, toRecipientHex: senderPubKey, from: id)
        sentReadReceipts.insert(messageId)
    }

    private enum MediaSendError: Error {
        case encodingFailed
        case tooLarge
        case copyFailed
    }

    @MainActor
    func sendVoiceNote(at url: URL) {
        guard canSendMediaInCurrentContext else {
            SecureLogger.info("Voice note blocked outside mesh/private context", category: .session)
            try? FileManager.default.removeItem(at: url)
            addSystemMessage("Voice notes are only available in mesh chats.")
            return
        }

        let targetPeer = selectedPrivateChatPeer
        let message = enqueueMediaMessage(content: "\(MimeType.Category.audio.messagePrefix)\(url.lastPathComponent)", targetPeer: targetPeer)
        let messageID = message.id
        let transferId = makeTransferID(messageID: messageID)

        Task.detached(priority: .userInitiated) { [weak self] in
            guard let self = self else { return }
            do {

                let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
                guard let fileSize = attrs[.size] as? Int,
                      fileSize <= FileTransferLimits.maxVoiceNoteBytes else {
                    let size = (attrs[.size] as? Int) ?? 0
                    SecureLogger.warning("Voice note exceeds size limit (\(size) bytes)", category: .session)
                    try? FileManager.default.removeItem(at: url)
                    await MainActor.run {
                        self.handleMediaSendFailure(messageID: messageID, reason: "Voice note too large")
                    }
                    return
                }

                let data = try Data(contentsOf: url)
                let packet = BitchatFilePacket(
                    fileName: url.lastPathComponent,
                    fileSize: UInt64(data.count),
                    mimeType: "audio/mp4",
                    content: data
                )
                guard packet.encode() != nil else { throw MediaSendError.encodingFailed }
                await MainActor.run {
                    self.registerTransfer(transferId: transferId, messageID: messageID)
                    if let peerID = targetPeer {
                        self.meshService.sendFilePrivate(packet, to: peerID, transferId: transferId)
                    } else {
                        self.meshService.sendFileBroadcast(packet, transferId: transferId)
                    }
                }
            } catch {
                SecureLogger.error("Voice note send failed: \(error)", category: .session)
                await MainActor.run {
                    self.handleMediaSendFailure(messageID: messageID, reason: "Failed to send voice note")
                }
            }
        }
    }

    #if os(iOS)
    func processThenSendImage(_ image: UIImage?) {
        guard let image else { return }
        Task.detached {
            do {
                let processedURL = try ImageUtils.processImage(image)
                await MainActor.run {
                    self.sendImage(from: processedURL)
                }
            } catch {
                SecureLogger.error("Image processing failed: \(error)", category: .session)
            }
        }
    }
    #elseif os(macOS)
    func processThenSendImage(from url: URL?) {
        guard let url else { return }
        Task.detached {
            do {
                let processedURL = try ImageUtils.processImage(at: url)
                await MainActor.run {
                    self.sendImage(from: processedURL)
                }
            } catch {
                SecureLogger.error("Image processing failed: \(error)", category: .session)
            }
        }
    }
    #endif

    @MainActor
    func sendImage(from sourceURL: URL, cleanup: (() -> Void)? = nil) {
        guard canSendMediaInCurrentContext else {
            SecureLogger.info("Image send blocked outside mesh/private context", category: .session)
            cleanup?()
            addSystemMessage("Images are only available in mesh chats.")
            return
        }

        let targetPeer = selectedPrivateChatPeer

        Task.detached(priority: .userInitiated) { [weak self] in
            guard let self = self else { return }
            var processedURL: URL?
            do {
                let outputURL = try ImageUtils.processImage(at: sourceURL)
                processedURL = outputURL
                let data = try Data(contentsOf: outputURL)
                guard data.count <= FileTransferLimits.maxImageBytes else {
                    SecureLogger.warning("Processed image exceeds size limit (\(data.count) bytes)", category: .session)
                    await MainActor.run {
                        self.addSystemMessage("Image is too large to send.")
                    }
                    try? FileManager.default.removeItem(at: outputURL)
                    return
                }
                let packet = BitchatFilePacket(
                    fileName: outputURL.lastPathComponent,
                    fileSize: UInt64(data.count),
                    mimeType: "image/jpeg",
                    content: data
                )
                guard packet.encode() != nil else { throw MediaSendError.encodingFailed }
                await MainActor.run {
                    let message = self.enqueueMediaMessage(content: "\(MimeType.Category.image.messagePrefix)\(outputURL.lastPathComponent)", targetPeer: targetPeer)
                    let messageID = message.id
                    let transferId = self.makeTransferID(messageID: messageID)
                    self.registerTransfer(transferId: transferId, messageID: messageID)
                    if let peerID = targetPeer {
                        self.meshService.sendFilePrivate(packet, to: peerID, transferId: transferId)
                    } else {
                        self.meshService.sendFileBroadcast(packet, transferId: transferId)
                    }
                }
            } catch {
                SecureLogger.error("Image send preparation failed: \(error)", category: .session)
                await MainActor.run {
                    self.addSystemMessage("Failed to prepare image for sending.")
                }
                if let url = processedURL {
                    try? FileManager.default.removeItem(at: url)
                }
            }
        }
    }

    @MainActor
    func enqueueMediaMessage(content: String, targetPeer: PeerID?) -> BitchatMessage {
        let timestamp = Date()
        let message: BitchatMessage

        if let peerID = targetPeer {
            message = BitchatMessage(
                sender: nickname,
                content: content,
                timestamp: timestamp,
                isRelay: false,
                originalSender: nil,
                isPrivate: true,
                recipientNickname: nicknameForPeer(peerID),
                senderPeerID: meshService.myPeerID,
                deliveryStatus: .sending
            )
            var chats = privateChats
            chats[peerID, default: []].append(message)
            privateChats = chats
            trimMessagesIfNeeded()
        } else {
            let (displayName, senderPeerID) = currentPublicSender()
            message = BitchatMessage(
                sender: displayName,
                content: content,
                timestamp: timestamp,
                isRelay: false,
                originalSender: nil,
                isPrivate: false,
                recipientNickname: nil,
                senderPeerID: senderPeerID,
                deliveryStatus: .sending
            )
            timelineStore.append(message, to: activeChannel)
            messages = timelineStore.messages(for: activeChannel)
            trimMessagesIfNeeded()
        }

        let key = deduplicationService.normalizedContentKey(message.content)
        deduplicationService.recordContentKey(key, timestamp: timestamp)
        objectWillChange.send()
        return message
    }

    @MainActor
    func registerTransfer(transferId: String, messageID: String) {
        transferIdToMessageIDs[transferId, default: []].append(messageID)
        messageIDToTransferId[messageID] = transferId
    }

    func makeTransferID(messageID: String) -> String {
        "\(messageID)-\(UUID().uuidString)"
    }

    @MainActor
    func clearTransferMapping(for messageID: String) {
        guard let transferId = messageIDToTransferId.removeValue(forKey: messageID) else { return }
        guard var queue = transferIdToMessageIDs[transferId] else { return }
        if !queue.isEmpty {
            if queue.first == messageID {
                queue.removeFirst()
            } else if let idx = queue.firstIndex(of: messageID) {
                queue.remove(at: idx)
            }
        }
        transferIdToMessageIDs[transferId] = queue.isEmpty ? nil : queue
    }

    @MainActor
    func handleMediaSendFailure(messageID: String, reason: String) {
        updateMessageDeliveryStatus(messageID, status: .failed(reason: reason))
        clearTransferMapping(for: messageID)
    }

    @MainActor
    func handleTransferEvent(_ event: TransferProgressManager.Event) {
        switch event {
        case .started(let id, let total):
            guard let messageID = transferIdToMessageIDs[id]?.first else { return }
            updateMessageDeliveryStatus(messageID, status: .partiallyDelivered(reached: 0, total: total))
        case .updated(let id, let sent, let total):
            guard let messageID = transferIdToMessageIDs[id]?.first else { return }
            updateMessageDeliveryStatus(messageID, status: .partiallyDelivered(reached: sent, total: total))
        case .completed(let id, _):
            guard let messageID = transferIdToMessageIDs[id]?.first else { return }
            updateMessageDeliveryStatus(messageID, status: .sent)
            clearTransferMapping(for: messageID)
        case .cancelled(let id, _, _):
            guard let messageID = transferIdToMessageIDs[id]?.first else { return }
            clearTransferMapping(for: messageID)
            removeMessage(withID: messageID, cleanupFile: true)
        }
    }

    func cleanupLocalFile(forMessage message: BitchatMessage) {

        let categories: [MimeType.Category] = [.audio, .image, .file]
        guard let category = categories.first(where: { message.content.hasPrefix($0.messagePrefix) }),
              let rawFilename = String(message.content.dropFirst(category.messagePrefix.count)).trimmedOrNilIfEmpty,
              let base = try? applicationFilesDirectory(),

              let safeFilename = (rawFilename as NSString).lastPathComponent.nilIfEmpty,
              safeFilename != "." && safeFilename != ".."
        else {
            return
        }

        let subdirs = categories.flatMap { ["\($0.mediaDir)/outgoing", "\($0.mediaDir)/incoming"] }
        for subdir in subdirs {
            let target = base.appendingPathComponent(subdir, isDirectory: true).appendingPathComponent(safeFilename)

            guard target.path.hasPrefix(base.path) else { continue }

            do {
                try FileManager.default.removeItem(at: target)
            } catch CocoaError.fileNoSuchFile {

            } catch {
                SecureLogger.error("Failed to cleanup \(safeFilename): \(error)", category: .session)
            }
        }
    }

    func applicationFilesDirectory() throws -> URL {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let filesDir = base.appendingPathComponent("files", isDirectory: true)
        try FileManager.default.createDirectory(at: filesDir, withIntermediateDirectories: true, attributes: nil)
        return filesDir
    }

    @MainActor
    func cancelMediaSend(messageID: String) {
        if let transferId = messageIDToTransferId[messageID],
           let active = transferIdToMessageIDs[transferId]?.first,
           active == messageID {
            meshService.cancelTransfer(transferId)
        }
        clearTransferMapping(for: messageID)
        removeMessage(withID: messageID, cleanupFile: true)
    }

    @MainActor
    func deleteMediaMessage(messageID: String) {
        clearTransferMapping(for: messageID)
        removeMessage(withID: messageID, cleanupFile: true)
    }

    @MainActor
    func handlePrivateMessage(
        _ payload: NoisePayload,
        actualSenderNoiseKey: Data?,
        senderNickname: String,
        targetPeerID: PeerID,
        messageTimestamp: Date,
        senderPubkey: String
    ) {
        guard let pm = PrivateMessagePacket.decode(from: payload.data) else { return }
        let messageId = pm.messageID
        let messageContent = pm.content

        if messageContent.hasPrefix("[FAVORITED]") || messageContent.hasPrefix("[UNFAVORITED]") {
            if let key = actualSenderNoiseKey {
                handleFavoriteNotificationFromMesh(messageContent, from: PeerID(hexData: key), senderNickname: senderNickname)
            }
            return
        }

        if isDuplicateMessage(messageId, targetPeerID: targetPeerID) {
            return
        }

        let wasReadBefore = sentReadReceipts.contains(messageId)

        var isViewingThisChat = false
        if selectedPrivateChatPeer == targetPeerID {
            isViewingThisChat = true
        } else if let selectedPeer = selectedPrivateChatPeer,
                  let selectedPeerData = unifiedPeerService.getPeer(by: selectedPeer),
                  let key = actualSenderNoiseKey,
                  selectedPeerData.noisePublicKey == key {
            isViewingThisChat = true
        }

        let isRecentMessage = Date().timeIntervalSince(messageTimestamp) < 30
        let shouldMarkAsUnread = !wasReadBefore && !isViewingThisChat && isRecentMessage

        let message = BitchatMessage(
            id: messageId,
            sender: senderNickname,
            content: messageContent,
            timestamp: messageTimestamp,
            isRelay: false,
            isPrivate: true,
            recipientNickname: nickname,
            senderPeerID: targetPeerID,
            deliveryStatus: .delivered(to: nickname, at: Date())
        )

        addMessageToPrivateChatsIfNeeded(message, targetPeerID: targetPeerID)
        mirrorToEphemeralIfNeeded(message, targetPeerID: targetPeerID, key: actualSenderNoiseKey)

        sendDeliveryAckViaNostrEmbedded(
            message,
            wasReadBefore: wasReadBefore,
            senderPubkey: senderPubkey,
            key: actualSenderNoiseKey
        )

        if wasReadBefore {

        } else if isViewingThisChat {
            handleViewingThisChat(
                message,
                targetPeerID: targetPeerID,
                key: actualSenderNoiseKey,
                senderPubkey: senderPubkey
            )
        } else {
            markAsUnreadIfNeeded(
                shouldMarkAsUnread: shouldMarkAsUnread,
                targetPeerID: targetPeerID,
                key: actualSenderNoiseKey,
                isRecentMessage: isRecentMessage,
                senderNickname: senderNickname,
                messageContent: messageContent
            )
        }

        objectWillChange.send()
    }

    @MainActor
    func handlePrivateMessage(_ message: BitchatMessage) {
        SecureLogger.debug("handlePrivateMessage called for message from \(message.sender)", category: .session)
        let senderPeerID = message.senderPeerID ?? getPeerIDForNickname(message.sender)

        guard let peerID = senderPeerID else {
            SecureLogger.warning("Could not get peer ID for sender \(message.sender)", category: .session)
            return
        }

        if message.content.hasPrefix("[FAVORITED]") || message.content.hasPrefix("[UNFAVORITED]") {
            handleFavoriteNotificationFromMesh(message.content, from: peerID, senderNickname: message.sender)
            return
        }

        migratePrivateChatsIfNeeded(for: peerID, senderNickname: message.sender)

        if peerID.id.count == 16 {
            if let peer = unifiedPeerService.getPeer(by: peerID) {
                let stableKeyHex = PeerID(hexData: peer.noisePublicKey)

                if stableKeyHex != peerID, let nostrMessages = privateChats[stableKeyHex], !nostrMessages.isEmpty {

                    if privateChats[peerID] == nil {
                        privateChats[peerID] = []
                    }

                    let existingMessageIds = Set(privateChats[peerID]?.map { $0.id } ?? [])
                    for nostrMessage in nostrMessages {
                        if !existingMessageIds.contains(nostrMessage.id) {
                            privateChats[peerID]?.append(nostrMessage)
                        }
                    }

                    privateChats[peerID]?.sort { $0.timestamp < $1.timestamp }

                    privateChats.removeValue(forKey: stableKeyHex)

                    SecureLogger.info("Consolidated \(nostrMessages.count) Nostr messages from stable key to ephemeral peer \(peerID)", category: .session)
                }
            }
        }

        if isDuplicateMessage(message.id, targetPeerID: peerID) {
            return
        }

        addMessageToPrivateChatsIfNeeded(message, targetPeerID: peerID)

        let noiseKey = peerID.noiseKey ?? unifiedPeerService.getPeer(by: peerID)?.noisePublicKey
        mirrorToEphemeralIfNeeded(message, targetPeerID: peerID, key: noiseKey)

        let isViewing = selectedPrivateChatPeer == peerID

        if isViewing {

            let receipt = ReadReceipt(originalMessageID: message.id, readerID: meshService.myPeerID, readerNickname: nickname)
            meshService.sendReadReceipt(receipt, to: peerID)
            sentReadReceipts.insert(message.id)
        } else {

            unreadPrivateMessages.insert(peerID)
            NotificationService.shared.sendPrivateMessageNotification(
                from: message.sender,
                message: message.content,
                peerID: peerID
            )
        }

        objectWillChange.send()
    }

    func isDuplicateMessage(_ messageId: String, targetPeerID: PeerID) -> Bool {
        if privateChats[targetPeerID]?.contains(where: { $0.id == messageId }) == true {
            return true
        }
        for (_, messages) in privateChats where messages.contains(where: { $0.id == messageId }) {
            return true
        }
        return false
    }

    func addMessageToPrivateChatsIfNeeded(_ message: BitchatMessage, targetPeerID: PeerID) {
        if privateChats[targetPeerID] == nil {
            privateChats[targetPeerID] = []
        }
        if let idx = privateChats[targetPeerID]?.firstIndex(where: { $0.id == message.id }) {
            privateChats[targetPeerID]?[idx] = message
        } else {
            privateChats[targetPeerID]?.append(message)
        }

        privateChatManager.sanitizeChat(for: targetPeerID)
    }

    @MainActor
    func mirrorToEphemeralIfNeeded(_ message: BitchatMessage, targetPeerID: PeerID, key: Data?) {
        guard let key,
              let ephemeralPeerID = unifiedPeerService.peers.first(where: { $0.noisePublicKey == key })?.peerID,
              ephemeralPeerID != targetPeerID
        else {
            return
        }

        if privateChats[ephemeralPeerID] == nil {
            privateChats[ephemeralPeerID] = []
        }
        if let idx = privateChats[ephemeralPeerID]?.firstIndex(where: { $0.id == message.id }) {
            privateChats[ephemeralPeerID]?[idx] = message
        } else {
            privateChats[ephemeralPeerID]?.append(message)
        }
        privateChatManager.sanitizeChat(for: ephemeralPeerID)
    }

    @MainActor
    func handleViewingThisChat(_ message: BitchatMessage, targetPeerID: PeerID, key: Data?, senderPubkey: String) {
        unreadPrivateMessages.remove(targetPeerID)
        if let key,
           let ephemeralPeerID = unifiedPeerService.peers.first(where: { $0.noisePublicKey == key })?.peerID {
            unreadPrivateMessages.remove(ephemeralPeerID)
        }
        if !sentReadReceipts.contains(message.id) {
            if let key {
                let receipt = ReadReceipt(originalMessageID: message.id, readerID: meshService.myPeerID, readerNickname: nickname)
                SecureLogger.debug("Viewing chat; sending READ ack for \(message.id.prefix(8))… via router", category: .session)
                messageRouter.sendReadReceipt(receipt, to: PeerID(hexData: key))
                sentReadReceipts.insert(message.id)
            } else if let id = try? idBridge.getCurrentNostrIdentity() {
                let nt = NostrTransport(keychain: keychain, idBridge: idBridge)
                nt.senderPeerID = meshService.myPeerID
                nt.sendReadReceiptGeohash(message.id, toRecipientHex: senderPubkey, from: id)
                sentReadReceipts.insert(message.id)
                SecureLogger.debug("Viewing chat; sent READ ack directly to Nostr pub=\(senderPubkey.prefix(8))… for mid=\(message.id.prefix(8))…", category: .session)
            }
        }
    }

    @MainActor
    func markAsUnreadIfNeeded(
        shouldMarkAsUnread: Bool,
        targetPeerID: PeerID,
        key: Data?,
        isRecentMessage: Bool,
        senderNickname: String,
        messageContent: String
    ) {
        guard shouldMarkAsUnread else { return }

        unreadPrivateMessages.insert(targetPeerID)
        if let key,
           let ephemeralPeerID = unifiedPeerService.peers.first(where: { $0.noisePublicKey == key })?.peerID,
           ephemeralPeerID != targetPeerID {
            unreadPrivateMessages.insert(ephemeralPeerID)
        }
        if isRecentMessage {
            NotificationService.shared.sendPrivateMessageNotification(
                from: senderNickname,
                message: messageContent,
                peerID: targetPeerID
            )
        }
    }

    @MainActor
    func handleFavoriteNotificationFromMesh(_ content: String, from peerID: PeerID, senderNickname: String) {

        let isFavorite = content.hasPrefix("[FAVORITED]")
        let parts = content.split(separator: ":")

        var nostrPubkey: String? = nil
        if parts.count > 1 {
            nostrPubkey = String(parts[1])
            SecureLogger.info("Received Nostr npub in favorite notification: \(nostrPubkey ?? "none")", category: .session)
        }

        let noiseKey = peerID.noiseKey ?? unifiedPeerService.getPeer(by: peerID)?.noisePublicKey

        guard let finalNoiseKey = noiseKey else {
            SecureLogger.warning("Cannot get Noise key for peer \(peerID)", category: .session)
            return
        }

        let prior = FavoritesPersistenceService.shared.getFavoriteStatus(for: finalNoiseKey)?.theyFavoritedUs ?? false

        FavoritesPersistenceService.shared.updatePeerFavoritedUs(
            peerNoisePublicKey: finalNoiseKey,
            favorited: isFavorite,
            peerNickname: senderNickname,
            peerNostrPublicKey: nostrPubkey
        )

        if isFavorite && nostrPubkey != nil {
            SecureLogger.info("Storing Nostr key association for \(senderNickname): \(nostrPubkey!.prefix(16))...", category: .session)
        }

        if prior != isFavorite {
            let action = isFavorite ? "favorited" : "unfavorited"
            addMeshOnlySystemMessage("\(senderNickname) \(action) you")
        }
    }

    func processActionMessage(_ message: BitchatMessage) -> BitchatMessage {
        let isActionMessage = message.content.hasPrefix("* ") && message.content.hasSuffix(" *") &&
                              (message.content.contains("") || message.content.contains("") ||
                               message.content.contains("took a screenshot"))

        if isActionMessage {
            return BitchatMessage(
                id: message.id,
                sender: "system",
                content: String(message.content.dropFirst(2).dropLast(2)),
                timestamp: message.timestamp,
                isRelay: message.isRelay,
                originalSender: message.originalSender,
                isPrivate: message.isPrivate,
                recipientNickname: message.recipientNickname,
                senderPeerID: message.senderPeerID,
                mentions: message.mentions,
                deliveryStatus: message.deliveryStatus
            )
        }
        return message
    }

    @MainActor
    func migratePrivateChatsIfNeeded(for peerID: PeerID, senderNickname: String) {
        let currentFingerprint = getFingerprint(for: peerID)

        if privateChats[peerID] == nil || privateChats[peerID]?.isEmpty == true {
            var migratedMessages: [BitchatMessage] = []
            var oldPeerIDsToRemove: [PeerID] = []

            let cutoffTime = Date().addingTimeInterval(-TransportConfig.uiMigrationCutoffSeconds)

            for (oldPeerID, messages) in privateChats {
                if oldPeerID != peerID {
                    let oldFingerprint = peerIDToPublicKeyFingerprint[oldPeerID]

                    let recentMessages = messages.filter { $0.timestamp > cutoffTime }

                    guard !recentMessages.isEmpty else { continue }

                    if let currentFp = currentFingerprint,
                       let oldFp = oldFingerprint,
                       currentFp == oldFp {
                        migratedMessages.append(contentsOf: recentMessages)

                        if recentMessages.count == messages.count {
                            oldPeerIDsToRemove.append(oldPeerID)
                        } else {

                            SecureLogger.info("Partially migrating \(recentMessages.count) of \(messages.count) messages from \(oldPeerID)", category: .session)
                        }

                        SecureLogger.info("Migrating \(recentMessages.count) recent messages from old peer ID \(oldPeerID) to \(peerID) (fingerprint match)", category: .session)
                    } else if currentFingerprint == nil || oldFingerprint == nil {

                        let isRelevantChat = recentMessages.contains { msg in
                            (msg.sender == senderNickname && msg.sender != nickname) ||
                            (msg.sender == nickname && msg.recipientNickname == senderNickname)
                        }

                        if isRelevantChat {
                            migratedMessages.append(contentsOf: recentMessages)

                            if recentMessages.count == messages.count {
                                oldPeerIDsToRemove.append(oldPeerID)
                            }

                            SecureLogger.warning("Migrating \(recentMessages.count) recent messages from old peer ID \(oldPeerID) to \(peerID) (nickname match)", category: .session)
                        }
                    }
                }
            }

            if !oldPeerIDsToRemove.isEmpty {

                let needsSelectedUpdate = oldPeerIDsToRemove.contains { selectedPrivateChatPeer == $0 }

                for oldID in oldPeerIDsToRemove {
                    privateChats.removeValue(forKey: oldID)
                    unreadPrivateMessages.remove(oldID)

                    if peerIDToPublicKeyFingerprint[oldID] != nil {
                        peerIDToPublicKeyFingerprint.removeValue(forKey: oldID)
                    }
                }

                if needsSelectedUpdate {
                    selectedPrivateChatPeer = peerID
                }
            }

            if !migratedMessages.isEmpty {
                if privateChats[peerID] == nil {
                    privateChats[peerID] = []
                }
                privateChats[peerID]?.append(contentsOf: migratedMessages)

                privateChats[peerID]?.sort { $0.timestamp < $1.timestamp }

                privateChatManager.sanitizeChat(for: peerID)

                objectWillChange.send()
            }
        }
    }

    @MainActor
    func sendFavoriteNotification(to peerID: PeerID, isFavorite: Bool) {

        var noiseKey: Data?

        if let hexKey = Data(hexString: peerID.id) {
            noiseKey = hexKey
        } else {

            if let peer = unifiedPeerService.getPeer(by: peerID) {
                noiseKey = peer.noisePublicKey
            }
        }

        if meshService.isPeerConnected(peerID) {
            messageRouter.sendFavoriteNotification(to: peerID, isFavorite: isFavorite)
            SecureLogger.debug("Sent favorite notification via BLE to \(peerID)", category: .session)
        } else if let key = noiseKey {

            messageRouter.sendFavoriteNotification(to: PeerID(hexData: key), isFavorite: isFavorite)
        } else {
            SecureLogger.warning("Cannot send favorite notification - peer not connected and no Nostr pubkey", category: .session)
        }
    }

    @MainActor
    func isMessageBlocked(_ message: BitchatMessage) -> Bool {
        if let peerID = message.senderPeerID ?? getPeerIDForNickname(message.sender) {

            if isPeerBlocked(peerID) { return true }

            if peerID.isGeoChat || peerID.isGeoDM {
                if let full = nostrKeyMapping[peerID]?.lowercased() {
                    if identityManager.isNostrBlocked(pubkeyHexLowercased: full) { return true }
                }
            }
            return false
        }
        return false
    }
}

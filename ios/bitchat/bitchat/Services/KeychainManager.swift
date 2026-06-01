import BitLogger
import BitFoundation
import Foundation
import Security

final class KeychainManager: KeychainManagerProtocol {

    private let service = BitchatApp.bundleID
    private let appGroup = "group.\(BitchatApp.bundleID)"

    func saveIdentityKey(_ keyData: Data, forKey key: String) -> Bool {
        let fullKey = "identity_\(key)"
        let result = saveData(keyData, forKey: fullKey)
        SecureLogger.logKeyOperation(.save, keyType: key, success: result)
        return result
    }

    func getIdentityKey(forKey key: String) -> Data? {
        let fullKey = "identity_\(key)"
        return retrieveData(forKey: fullKey)
    }

    func deleteIdentityKey(forKey key: String) -> Bool {
        let result = delete(forKey: "identity_\(key)")
        SecureLogger.logKeyOperation(.delete, keyType: key, success: result)
        return result
    }

    func getIdentityKeyWithResult(forKey key: String) -> KeychainReadResult {
        let fullKey = "identity_\(key)"
        return retrieveDataWithResult(forKey: fullKey)
    }

    func saveIdentityKeyWithResult(_ keyData: Data, forKey key: String) -> KeychainSaveResult {
        let fullKey = "identity_\(key)"
        return saveDataWithResult(keyData, forKey: fullKey)
    }

    private func saveDataWithResult(_ data: Data, forKey key: String, retryCount: Int = 2) -> KeychainSaveResult {

        _ = delete(forKey: key)

        var base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrService as String: service,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlocked,
            kSecAttrLabel as String: "bitchat-\(key)"
        ]
        #if os(macOS)
        base[kSecAttrSynchronizable as String] = false
        #endif

        func attempt(addAccessGroup: Bool) -> OSStatus {
            var query = base
            if addAccessGroup { query[kSecAttrAccessGroup as String] = appGroup }
            return SecItemAdd(query as CFDictionary, nil)
        }

        #if os(iOS)
        var status = attempt(addAccessGroup: true)
        if status == -34018 {
            status = attempt(addAccessGroup: false)
        }
        #else
        let status = attempt(addAccessGroup: false)
        #endif

        let result = classifySaveStatus(status)

        switch result {
        case .success:
            SecureLogger.debug("Keychain save succeeded for key: \(key)", category: .keychain)
        case .duplicateItem:
            SecureLogger.warning("Keychain save found duplicate for key: \(key)", category: .keychain)
        case .accessDenied:
            SecureLogger.error(NSError(domain: "Keychain", code: Int(status)),
                               context: "Keychain access denied for key: \(key)", category: .keychain)
        case .deviceLocked:
            SecureLogger.warning("Device locked during keychain save for key: \(key)", category: .keychain)
        case .storageFull:
            SecureLogger.error(NSError(domain: "Keychain", code: Int(status)),
                               context: "Keychain storage full for key: \(key)", category: .keychain)
        case .otherError(let code):
            SecureLogger.error(NSError(domain: "Keychain", code: Int(code)),
                               context: "Keychain save failed for key: \(key)", category: .keychain)
        }

        if result.isRecoverableError && retryCount > 0 {
            let delayMs = UInt32((3 - retryCount) * 100)
            usleep(delayMs * 1000)
            SecureLogger.debug("Retrying keychain save for key: \(key), attempts remaining: \(retryCount)", category: .keychain)
            return saveDataWithResult(data, forKey: key, retryCount: retryCount - 1)
        }

        return result
    }

    private func retrieveDataWithResult(forKey key: String) -> KeychainReadResult {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecAttrService as String: service,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        func attempt(withAccessGroup: Bool) -> OSStatus {
            var q = base
            if withAccessGroup { q[kSecAttrAccessGroup as String] = appGroup }
            return SecItemCopyMatching(q as CFDictionary, &result)
        }

        #if os(iOS)
        var status = attempt(withAccessGroup: true)
        if status == -34018 { status = attempt(withAccessGroup: false) }
        #else
        let status = attempt(withAccessGroup: false)
        #endif

        let readResult = classifyReadStatus(status, data: result as? Data)

        switch readResult {
        case .success:
            SecureLogger.debug("Keychain read succeeded for key: \(key)", category: .keychain)
        case .itemNotFound:

            break
        case .accessDenied:
            SecureLogger.error(NSError(domain: "Keychain", code: Int(status)),
                               context: "Keychain access denied for key: \(key)", category: .keychain)
        case .deviceLocked:
            SecureLogger.warning("Device locked during keychain read for key: \(key)", category: .keychain)
        case .authenticationFailed:
            SecureLogger.warning("Authentication failed for keychain read of key: \(key)", category: .keychain)
        case .otherError(let code):
            SecureLogger.error(NSError(domain: "Keychain", code: Int(code)),
                               context: "Keychain read failed for key: \(key)", category: .keychain)
        }

        return readResult
    }

    private func classifyReadStatus(_ status: OSStatus, data: Data?) -> KeychainReadResult {
        switch status {
        case errSecSuccess:
            if let data = data {
                return .success(data)
            }
            return .otherError(status)
        case errSecItemNotFound:
            return .itemNotFound
        case errSecInteractionNotAllowed:

            return .deviceLocked
        case errSecAuthFailed:
            return .authenticationFailed
        case -34018:
            return .accessDenied
        case errSecNotAvailable:
            return .accessDenied
        default:
            return .otherError(status)
        }
    }

    private func classifySaveStatus(_ status: OSStatus) -> KeychainSaveResult {
        switch status {
        case errSecSuccess:
            return .success
        case errSecDuplicateItem:
            return .duplicateItem
        case errSecInteractionNotAllowed:
            return .deviceLocked
        case -34018:
            return .accessDenied
        case errSecNotAvailable:
            return .accessDenied
        case errSecDiskFull:
            return .storageFull
        default:
            return .otherError(status)
        }
    }

    private func save(_ value: String, forKey key: String) -> Bool {
        guard let data = value.data(using: .utf8) else { return false }
        return saveData(data, forKey: key)
    }

    private func saveData(_ data: Data, forKey key: String) -> Bool {

        _ = delete(forKey: key)

        var base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrService as String: service,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlocked,
            kSecAttrLabel as String: "bitchat-\(key)"
        ]
        #if os(macOS)
        base[kSecAttrSynchronizable as String] = false
        #endif

        var triedWithoutGroup = false
        func attempt(addAccessGroup: Bool) -> OSStatus {
            var query = base
            if addAccessGroup { query[kSecAttrAccessGroup as String] = appGroup }
            return SecItemAdd(query as CFDictionary, nil)
        }

        #if os(iOS)
        var status = attempt(addAccessGroup: true)
        if status == -34018 {
            triedWithoutGroup = true
            status = attempt(addAccessGroup: false)
        }
        #else

        let status = attempt(addAccessGroup: false)
        #endif

        if status == errSecSuccess { return true }
        if status == -34018 && !triedWithoutGroup {
            SecureLogger.error(NSError(domain: "Keychain", code: -34018), context: "Missing keychain entitlement", category: .keychain)
        } else if status != errSecDuplicateItem {
            SecureLogger.error(NSError(domain: "Keychain", code: Int(status)), context: "Error saving to keychain", category: .keychain)
        }
        return false
    }

    private func retrieve(forKey key: String) -> String? {
        guard let data = retrieveData(forKey: key) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func retrieveData(forKey key: String) -> Data? {

        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecAttrService as String: service,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        func attempt(withAccessGroup: Bool) -> OSStatus {
            var q = base
            if withAccessGroup { q[kSecAttrAccessGroup as String] = appGroup }
            return SecItemCopyMatching(q as CFDictionary, &result)
        }

        #if os(iOS)
        var status = attempt(withAccessGroup: true)
        if status == -34018 { status = attempt(withAccessGroup: false) }
        #else
        let status = attempt(withAccessGroup: false)
        #endif

        if status == errSecSuccess { return result as? Data }
        if status == -34018 {
            SecureLogger.error(NSError(domain: "Keychain", code: -34018), context: "Missing keychain entitlement", category: .keychain)
        }
        return nil
    }

    private func delete(forKey key: String) -> Bool {

        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecAttrService as String: service
        ]

        func attempt(withAccessGroup: Bool) -> OSStatus {
            var q = base
            if withAccessGroup { q[kSecAttrAccessGroup as String] = appGroup }
            return SecItemDelete(q as CFDictionary)
        }

        #if os(iOS)
        var status = attempt(withAccessGroup: true)
        if status == -34018 { status = attempt(withAccessGroup: false) }
        #else
        let status = attempt(withAccessGroup: false)
        #endif
        return status == errSecSuccess || status == errSecItemNotFound
    }

    func deleteAllPasswords() -> Bool {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword
        ]

        if !service.isEmpty {
            query[kSecAttrService as String] = service
        }

        let status = SecItemDelete(query as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }

    func deleteAllKeychainData() -> Bool {
        SecureLogger.warning("Panic mode - deleting all keychain data", category: .security)

        var totalDeleted = 0

        let searchQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecReturnAttributes as String: true
        ]

        var result: AnyObject?
        let searchStatus = SecItemCopyMatching(searchQuery as CFDictionary, &result)

        if searchStatus == errSecSuccess, let items = result as? [[String: Any]] {
            for item in items {
                var shouldDelete = false
                let account = item[kSecAttrAccount as String] as? String ?? ""
                let service = item[kSecAttrService as String] as? String ?? ""
                let accessGroup = item[kSecAttrAccessGroup as String] as? String

                if accessGroup == appGroup {
                    shouldDelete = true
                } else if service == self.service {
                    shouldDelete = true
                } else if [
                    "com.cybersiren.passwords",
                    "com.cybersiren.deviceidentity",
                    "com.cybersiren.noise.identity",
                    "com.cybersiren.ios.passwords",
                    "bitchat.keychain",
                    "bitchat",
                    "com.bitchat"
                ].contains(service) {
                    shouldDelete = true
                }

                if shouldDelete {

                    var deleteQuery: [String: Any] = [
                        kSecClass as String: kSecClassGenericPassword
                    ]

                    if !account.isEmpty {
                        deleteQuery[kSecAttrAccount as String] = account
                    }
                    if !service.isEmpty {
                        deleteQuery[kSecAttrService as String] = service
                    }

                    if let accessGroup = item[kSecAttrAccessGroup as String] as? String,
                       !accessGroup.isEmpty && accessGroup != "test" {
                        deleteQuery[kSecAttrAccessGroup as String] = accessGroup
                    }

                    let deleteStatus = SecItemDelete(deleteQuery as CFDictionary)
                    if deleteStatus == errSecSuccess {
                        totalDeleted += 1
                        SecureLogger.info("Deleted keychain item: \(account) from \(service)", category: .keychain)
                    }
                }
            }
        }

        let knownServices = [
            self.service,
            "com.cybersiren.passwords",
            "com.cybersiren.deviceidentity",
            "com.cybersiren.noise.identity",
            "com.cybersiren.ios.passwords",
            "com.cybersiren.ios.nostr",
            "bitchat.keychain",
            "bitchat",
            "com.bitchat"
        ]

        for serviceName in knownServices {
            let query: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: serviceName
            ]

            let status = SecItemDelete(query as CFDictionary)
            if status == errSecSuccess {
                totalDeleted += 1
            }
        }

        let groupQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccessGroup as String: appGroup
        ]

        let groupStatus = SecItemDelete(groupQuery as CFDictionary)
        if groupStatus == errSecSuccess {
            totalDeleted += 1
        }

        SecureLogger.warning("Panic mode cleanup completed. Total items deleted: \(totalDeleted)", category: .keychain)

        return totalDeleted > 0
    }

    func secureClear(_ data: inout Data) {
        _ = data.withUnsafeMutableBytes { bytes in

            memset_s(bytes.baseAddress, bytes.count, 0, bytes.count)
        }
        data = Data()
    }

    func secureClear(_ string: inout String) {

        if var data = string.data(using: .utf8) {
            secureClear(&data)
        }
        string = ""
    }

    func verifyIdentityKeyExists() -> Bool {
        let key = "identity_noiseStaticKey"
        return retrieveData(forKey: key) != nil
    }

    func save(key: String, data: Data, service customService: String, accessible: CFString?) {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: customService,
            kSecAttrAccount as String: key,
            kSecValueData as String: data
        ]
        if let accessible = accessible {
            query[kSecAttrAccessible as String] = accessible
        }

        SecItemDelete(query as CFDictionary)
        SecItemAdd(query as CFDictionary, nil)
    }

    func load(key: String, service customService: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: customService,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        guard status == errSecSuccess else { return nil }
        return result as? Data
    }

    func delete(key: String, service customService: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: customService,
            kSecAttrAccount as String: key
        ]

        SecItemDelete(query as CFDictionary)
    }
}

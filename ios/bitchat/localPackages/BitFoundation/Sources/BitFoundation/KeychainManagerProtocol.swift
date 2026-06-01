import struct Foundation.Data
import class CoreFoundation.CFString
import typealias Darwin.OSStatus

public protocol KeychainManagerProtocol {
    func saveIdentityKey(_ keyData: Data, forKey key: String) -> Bool
    func getIdentityKey(forKey key: String) -> Data?
    func deleteIdentityKey(forKey key: String) -> Bool
    func deleteAllKeychainData() -> Bool

    func secureClear(_ data: inout Data)
    func secureClear(_ string: inout String)

    func verifyIdentityKeyExists() -> Bool

    func getIdentityKeyWithResult(forKey key: String) -> KeychainReadResult

    func saveIdentityKeyWithResult(_ keyData: Data, forKey key: String) -> KeychainSaveResult

    func save(key: String, data: Data, service: String, accessible: CFString?)

    func load(key: String, service: String) -> Data?

    func delete(key: String, service: String)
}

public enum KeychainReadResult {
    case success(Data)
    case itemNotFound
    case accessDenied
    case deviceLocked
    case authenticationFailed
    case otherError(OSStatus)

    public var isRecoverableError: Bool {
        switch self {
        case .deviceLocked, .authenticationFailed:
            return true
        default:
            return false
        }
    }
}

public enum KeychainSaveResult {
    case success
    case duplicateItem
    case accessDenied
    case deviceLocked
    case storageFull
    case otherError(OSStatus)

    public var isRecoverableError: Bool {
        switch self {
        case .duplicateItem, .deviceLocked:
            return true
        default:
            return false
        }
    }
}

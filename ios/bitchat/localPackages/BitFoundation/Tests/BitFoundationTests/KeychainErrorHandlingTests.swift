import Testing
import Foundation
import BitFoundation

struct KeychainErrorHandlingTests {

    @Test func keychainReadResult_successIsNotRecoverable() throws {
        let result = KeychainReadResult.success(Data([1, 2, 3]))
        #expect(result.isRecoverableError == false)
    }

    @Test func keychainReadResult_itemNotFoundIsNotRecoverable() throws {
        let result = KeychainReadResult.itemNotFound
        #expect(result.isRecoverableError == false)
    }

    @Test func keychainReadResult_deviceLockedIsRecoverable() throws {
        let result = KeychainReadResult.deviceLocked
        #expect(result.isRecoverableError == true)
    }

    @Test func keychainReadResult_authenticationFailedIsRecoverable() throws {
        let result = KeychainReadResult.authenticationFailed
        #expect(result.isRecoverableError == true)
    }

    @Test func keychainReadResult_accessDeniedIsNotRecoverable() throws {
        let result = KeychainReadResult.accessDenied
        #expect(result.isRecoverableError == false)
    }

    @Test func keychainSaveResult_successIsNotRecoverable() throws {
        let result = KeychainSaveResult.success
        #expect(result.isRecoverableError == false)
    }

    @Test func keychainSaveResult_duplicateItemIsRecoverable() throws {
        let result = KeychainSaveResult.duplicateItem
        #expect(result.isRecoverableError == true)
    }

    @Test func keychainSaveResult_deviceLockedIsRecoverable() throws {
        let result = KeychainSaveResult.deviceLocked
        #expect(result.isRecoverableError == true)
    }

    @Test func keychainSaveResult_storageFullIsNotRecoverable() throws {
        let result = KeychainSaveResult.storageFull
        #expect(result.isRecoverableError == false)
    }

    @Test func mockKeychain_canSimulateReadErrors() throws {
        let keychain = MockKeychain()

        keychain.simulatedReadError = .accessDenied
        let result = keychain.getIdentityKeyWithResult(forKey: "testKey")

        switch result {
        case .accessDenied:

            break
        default:
            throw KeychainTestError("Expected accessDenied, got \(result)")
        }
    }

    @Test func mockKeychain_canSimulateSaveErrors() throws {
        let keychain = MockKeychain()

        keychain.simulatedSaveError = .storageFull
        let result = keychain.saveIdentityKeyWithResult(Data([1, 2, 3]), forKey: "testKey")

        switch result {
        case .storageFull:

            break
        default:
            throw KeychainTestError("Expected storageFull, got \(result)")
        }
    }

    @Test func mockKeychain_returnsItemNotFoundForMissingKey() throws {
        let keychain = MockKeychain()
        let result = keychain.getIdentityKeyWithResult(forKey: "nonExistentKey")

        switch result {
        case .itemNotFound:

            break
        default:
            throw KeychainTestError("Expected itemNotFound, got \(result)")
        }
    }

    @Test func mockKeychain_returnsSuccessForExistingKey() throws {
        let keychain = MockKeychain()
        let testData = Data([1, 2, 3, 4, 5])

        _ = keychain.saveIdentityKey(testData, forKey: "existingKey")

        let result = keychain.getIdentityKeyWithResult(forKey: "existingKey")

        switch result {
        case .success(let data):
            #expect(data == testData)
        default:
            throw KeychainTestError("Expected success, got \(result)")
        }
    }

    @Test func mockKeychain_saveWithResultStoresData() throws {
        let keychain = MockKeychain()
        let testData = Data([10, 20, 30])

        let saveResult = keychain.saveIdentityKeyWithResult(testData, forKey: "newKey")

        switch saveResult {
        case .success:

            let readResult = keychain.getIdentityKeyWithResult(forKey: "newKey")
            switch readResult {
            case .success(let data):
                #expect(data == testData)
            default:
                throw KeychainTestError("Expected to read back saved data")
            }
        default:
            throw KeychainTestError("Expected save success, got \(saveResult)")
        }
    }
}

private struct KeychainTestError: Error, CustomStringConvertible {
    let message: String
    init(_ message: String) { self.message = message }
    var description: String { message }
}

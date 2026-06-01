import Testing
@testable import bitchat

struct NoiseEncryptionTests {
    @Test func generatesNewIdentityWhenMissing() throws {
        let keychain = MockKeychain()

        let service = NoiseEncryptionService(keychain: keychain)

        #expect(service.getStaticPublicKeyData().count == 32)
        #expect(service.getSigningPublicKeyData().count == 32)

        let noiseKeyResult = keychain.getIdentityKeyWithResult(forKey: "noiseStaticKey")
        switch noiseKeyResult {
        case .success:

            break
        default:
            throw KeychainTestError("Expected noise key to be saved")
        }
    }

    @Test func loadsExistingIdentity() throws {
        let keychain = MockKeychain()

        let service1 = NoiseEncryptionService(keychain: keychain)
        let originalPublicKey = service1.getStaticPublicKeyData()
        let originalSigningKey = service1.getSigningPublicKeyData()

        let service2 = NoiseEncryptionService(keychain: keychain)

        #expect(service2.getStaticPublicKeyData() == originalPublicKey)
        #expect(service2.getSigningPublicKeyData() == originalSigningKey)
    }

    @Test func handlesAccessDeniedGracefully() throws {
        let keychain = MockKeychain()
        keychain.simulatedReadError = .accessDenied

        let service = NoiseEncryptionService(keychain: keychain)

        #expect(service.getStaticPublicKeyData().count == 32)
        #expect(service.getSigningPublicKeyData().count == 32)
    }

    @Test func handlesDeviceLockedGracefully() throws {
        let keychain = MockKeychain()
        keychain.simulatedReadError = .deviceLocked

        let service = NoiseEncryptionService(keychain: keychain)

        #expect(service.getStaticPublicKeyData().count == 32)
    }
}

private struct KeychainTestError: Error, CustomStringConvertible {
    let message: String
    init(_ message: String) { self.message = message }
    var description: String { message }
}

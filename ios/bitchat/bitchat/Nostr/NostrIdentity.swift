import Foundation
import P256K

struct NostrIdentity: Codable {
    let privateKey: Data
    let publicKey: Data
    let npub: String
    let createdAt: Date

    init(privateKey: Data, publicKey: Data, npub: String, createdAt: Date) {
        self.privateKey = privateKey
        self.publicKey = publicKey
        self.npub = npub
        self.createdAt = createdAt
    }

    static func generate() throws -> NostrIdentity {

        let schnorrKey = try P256K.Schnorr.PrivateKey()
        let xOnlyPubkey = Data(schnorrKey.xonly.bytes)
        let npub = try Bech32.encode(hrp: "npub", data: xOnlyPubkey)

        return NostrIdentity(
            privateKey: schnorrKey.dataRepresentation,
            publicKey: xOnlyPubkey,
            npub: npub,
            createdAt: Date()
        )
    }

    init(privateKeyData: Data) throws {
        let schnorrKey = try P256K.Schnorr.PrivateKey(dataRepresentation: privateKeyData)
        let xOnlyPubkey = Data(schnorrKey.xonly.bytes)

        self.privateKey = privateKeyData
        self.publicKey = xOnlyPubkey
        self.npub = try Bech32.encode(hrp: "npub", data: xOnlyPubkey)
        self.createdAt = Date()
    }

    func signingKey() throws -> P256K.Signing.PrivateKey {
        try P256K.Signing.PrivateKey(dataRepresentation: privateKey)
    }

    func schnorrSigningKey() throws -> P256K.Schnorr.PrivateKey {
        try P256K.Schnorr.PrivateKey(dataRepresentation: privateKey)
    }

    var publicKeyHex: String {

        return publicKey.hexEncodedString()
    }
}

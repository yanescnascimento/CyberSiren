enum NoiseSessionError: Error, Equatable {
    case invalidState
    case notEstablished
    case sessionNotFound
    case alreadyEstablished
}

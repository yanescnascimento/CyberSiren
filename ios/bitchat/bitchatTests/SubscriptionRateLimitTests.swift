import Testing
import Foundation
@testable import bitchat

struct SubscriptionRateLimitTests {

    @Test("Rate limit configuration values are sensible")
    func rateLimitConfigurationValues() {

        #expect(TransportConfig.bleSubscriptionRateLimitMinSeconds >= 1.0)

        #expect(TransportConfig.bleSubscriptionRateLimitBackoffFactor > 1.0)

        #expect(TransportConfig.bleSubscriptionRateLimitMaxBackoffSeconds <= 60.0)
        #expect(TransportConfig.bleSubscriptionRateLimitMaxBackoffSeconds >= TransportConfig.bleSubscriptionRateLimitMinSeconds)

        #expect(TransportConfig.bleSubscriptionRateLimitWindowSeconds >= 30.0)

        #expect(TransportConfig.bleSubscriptionRateLimitMaxAttempts >= 2)
    }

    @Test("Exponential backoff calculation is correct")
    func exponentialBackoffCalculation() {
        let minInterval = TransportConfig.bleSubscriptionRateLimitMinSeconds
        let factor = TransportConfig.bleSubscriptionRateLimitBackoffFactor
        let maxBackoff = TransportConfig.bleSubscriptionRateLimitMaxBackoffSeconds

        var currentBackoff = minInterval
        var iterations = 0
        let maxIterations = 10

        while currentBackoff < maxBackoff && iterations < maxIterations {
            let nextBackoff = min(currentBackoff * factor, maxBackoff)
            #expect(nextBackoff >= currentBackoff, "Backoff should increase or stay at max")
            currentBackoff = nextBackoff
            iterations += 1
        }

        #expect(iterations <= maxIterations, "Backoff should reach max within \(maxIterations) iterations")
        #expect(currentBackoff == maxBackoff, "Final backoff should equal max")
    }

    @Test("Rate limiting would significantly slow enumeration attacks")
    func rateLimitingSlowsEnumeration() {

        let minInterval = TransportConfig.bleSubscriptionRateLimitMinSeconds
        let devicesPerMinuteWithRateLimit = 60.0 / minInterval

        #expect(devicesPerMinuteWithRateLimit < 60, "Rate limiting should significantly slow enumeration")

        #expect(devicesPerMinuteWithRateLimit <= 30, "With 2s minimum, should be <=30/min")
    }

    @Test("Max attempts threshold prevents complete enumeration")
    func maxAttemptsThresholdPreventsEnumeration() {
        let maxAttempts = TransportConfig.bleSubscriptionRateLimitMaxAttempts

        #expect(maxAttempts >= 2, "Should allow at least 2 attempts for legitimate reconnects")
        #expect(maxAttempts <= 10, "Should cap attempts to prevent enumeration")

        let maxAnnounces = maxAttempts
        #expect(maxAnnounces <= 10, "Max announces per window should be limited")
    }
}

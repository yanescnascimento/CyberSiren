import Foundation

enum TransportConfig {

    static let bleDefaultFragmentSize: Int = 469
    static let messageTTLDefault: UInt8 = 7
    static let bleMaxInFlightAssemblies: Int = 128
    static let bleHighDegreeThreshold: Int = 6
    static let bleMaxConcurrentTransfers: Int = 2
    static let bleFragmentRelayMinDelayMs: Int = 8
    static let bleFragmentRelayMaxDelayMs: Int = 25
    static let bleFragmentRelayTtlCap: UInt8 = 5

    static let privateChatCap: Int = 1337
    static let meshTimelineCap: Int = 1337
    static let geoTimelineCap: Int = 1337
    static let contentLRUCap: Int = 2000

    static let networkResetGraceSeconds: TimeInterval = 600
    static let networkNotificationCooldownSeconds: TimeInterval = 300
    static let basePublicFlushInterval: TimeInterval = 0.08

    static let bleConnectRateLimitInterval: TimeInterval = 0.5
    static let bleMaxCentralLinks: Int = 6
    static let bleDutyOnDuration: TimeInterval = 5.0
    static let bleDutyOffDuration: TimeInterval = 10.0
    static let bleAnnounceMinInterval: TimeInterval = 1.0

    static let bleDynamicRSSIThresholdDefault: Int = -90
    static let bleConnectionCandidatesMax: Int = 100
    static let blePendingWriteBufferCapBytes: Int = 1_000_000
    static let bleNotificationAssemblerHardCapBytes: Int = 8 * 1024 * 1024
    static let bleAssemblerStallResetMs: Int = 250
    static let blePendingNotificationsCapCount: Int = 128
    static let bleNotificationRetryDelayMs: Int = 25
    static let bleNotificationRetryMaxAttempts: Int = 80

    static let nostrReadAckInterval: TimeInterval = 0.35

    static let uiLateInsertThreshold: TimeInterval = 15.0

    static let uiLateInsertThresholdGeo: TimeInterval = 0.0
    static let uiProcessedNostrEventsCap: Int = 2000
    static let uiChannelInactivityThresholdSeconds: TimeInterval = 9 * 60

    static let uiSenderRateBucketCapacity: Double = 5
    static let uiSenderRateBucketRefillPerSec: Double = 1.0
    static let uiContentRateBucketCapacity: Double = 3
    static let uiContentRateBucketRefillPerSec: Double = 0.5

    static let uiStartupInitialDelaySeconds: TimeInterval = 1.0
    static let uiStartupShortSleepNs: UInt64 = 200_000_000
    static let uiStartupPhaseDurationSeconds: TimeInterval = 2.0
    static let uiAsyncShortSleepNs: UInt64 = 100_000_000
    static let uiAsyncMediumSleepNs: UInt64 = 500_000_000
    static let uiReadReceiptRetryShortSeconds: TimeInterval = 0.1
    static let uiReadReceiptRetryLongSeconds: TimeInterval = 0.5
    static let uiBatchDispatchStaggerSeconds: TimeInterval = 0.15
    static let uiScrollThrottleSeconds: TimeInterval = 0.5
    static let uiAnimationShortSeconds: TimeInterval = 0.15
    static let uiAnimationMediumSeconds: TimeInterval = 0.2
    static let uiAnimationSidebarSeconds: TimeInterval = 0.25
    static let uiRecentCutoffFiveMinutesSeconds: TimeInterval = 5 * 60
    static let uiMeshEmptyConfirmationSeconds: TimeInterval = 30.0

    static let bleMaintenanceInterval: TimeInterval = 5.0
    static let bleMaintenanceLeewaySeconds: Int = 1
    static let bleIsolationRelaxThresholdSeconds: TimeInterval = 60
    static let bleRecentTimeoutWindowSeconds: TimeInterval = 60
    static let bleRecentTimeoutCountThreshold: Int = 3
    static let bleRSSIIsolatedBase: Int = -90
    static let bleRSSIIsolatedRelaxed: Int = -92
    static let bleRSSIConnectedThreshold: Int = -85
    static let bleRSSIHighTimeoutThreshold: Int = -80

    static let blePeerInactivityTimeoutSeconds: TimeInterval = 8.0

    static let bleReachabilityRetentionVerifiedSeconds: TimeInterval = 21.0
    static let bleReachabilityRetentionUnverifiedSeconds: TimeInterval = 21.0
    static let bleFragmentLifetimeSeconds: TimeInterval = 30.0
    static let bleIngressRecordLifetimeSeconds: TimeInterval = 3.0
    static let bleConnectTimeoutBackoffWindowSeconds: TimeInterval = 120.0
    static let bleRecentPacketWindowSeconds: TimeInterval = 30.0
    static let bleRecentPacketWindowMaxCount: Int = 100

    static let bleRecentTrafficForceScanSeconds: TimeInterval = 10.0
    static let bleThreadSleepWriteShortDelaySeconds: TimeInterval = 0.05
    static let bleExpectedWritePerFragmentMs: Int = 20
    static let bleExpectedWriteMaxMs: Int = 5000

    static let bleFragmentSpacingMs: Int = 30
    static let bleFragmentSpacingDirectedMs: Int = 25
    static let bleAnnounceIntervalSeconds: TimeInterval = 4.0
    static let bleDutyOnDurationDense: TimeInterval = 3.0
    static let bleDutyOffDurationDense: TimeInterval = 15.0
    static let bleConnectedAnnounceBaseSecondsDense: TimeInterval = 30.0
    static let bleConnectedAnnounceBaseSecondsSparse: TimeInterval = 15.0
    static let bleConnectedAnnounceJitterDense: TimeInterval = 8.0
    static let bleConnectedAnnounceJitterSparse: TimeInterval = 4.0

    static let locationDistanceFilterMeters: Double = 1000

    static let locationDistanceFilterLiveMeters: Double = 10.0
    static let locationLiveRefreshInterval: TimeInterval = 5.0

    static let uiGeoNotifyCooldownSeconds: TimeInterval = 60.0
    static let uiGeoNotifySnippetMaxLen: Int = 80

    static let nostrGeohashInitialLookbackSeconds: TimeInterval = 3600
    static let nostrGeohashInitialLimit: Int = 200
    static let nostrGeoRelayCount: Int = 5
    static let nostrGeohashSampleLookbackSeconds: TimeInterval = 300
    static let nostrGeohashSampleLimit: Int = 100
    static let nostrDMSubscribeLookbackSeconds: TimeInterval = 86400

    static let nostrShortKeyDisplayLength: Int = 8
    static let nostrConvKeyPrefixLength: Int = 16

    static let messageDedupMaxAgeSeconds: TimeInterval = 300
    static let messageDedupMaxCount: Int = 1000

    static let verificationQRMaxAgeSeconds: TimeInterval = 5 * 60

    static let nostrRelayInitialBackoffSeconds: TimeInterval = 1.0
    static let nostrRelayMaxBackoffSeconds: TimeInterval = 300.0
    static let nostrRelayBackoffMultiplier: Double = 2.0
    static let nostrRelayMaxReconnectAttempts: Int = 10
    static let nostrRelayDefaultFetchLimit: Int = 100

    static let geoRelayFetchIntervalSeconds: TimeInterval = 60 * 60 * 24
    static let geoRelayRefreshCheckIntervalSeconds: TimeInterval = 60 * 60
    static let geoRelayRetryInitialSeconds: TimeInterval = 60
    static let geoRelayRetryMaxSeconds: TimeInterval = 60 * 60

    static let bleInitialAnnounceDelaySeconds: TimeInterval = 0.6
    static let bleConnectTimeoutSeconds: TimeInterval = 8.0
    static let bleRestartScanDelaySeconds: TimeInterval = 0.1
    static let blePostSubscribeAnnounceDelaySeconds: TimeInterval = 0.05
    static let blePostAnnounceDelaySeconds: TimeInterval = 0.4
    static let bleForceAnnounceMinIntervalSeconds: TimeInterval = 0.15

    static let bleSubscriptionRateLimitMinSeconds: TimeInterval = 2.0
    static let bleSubscriptionRateLimitBackoffFactor: Double = 2.0
    static let bleSubscriptionRateLimitMaxBackoffSeconds: TimeInterval = 30.0
    static let bleSubscriptionRateLimitWindowSeconds: TimeInterval = 60.0
    static let bleSubscriptionRateLimitMaxAttempts: Int = 5

    static let bleDirectedSpoolWindowSeconds: TimeInterval = 15.0

    static let bleDisconnectNotifyDebounceSeconds: TimeInterval = 0.9
    static let bleReconnectLogDebounceSeconds: TimeInterval = 2.0

    static let bleWeakLinkCooldownSeconds: TimeInterval = 30.0
    static let bleWeakLinkRSSICutoff: Int = -90

    static let contentKeyPrefixLength: Int = 256
    static let uiLongMessageLengthThreshold: Int = 2000
    static let uiVeryLongTokenThreshold: Int = 512
    static let uiLongMessageLineLimit: Int = 30
    static let uiFingerprintSampleCount: Int = 3

    static let uiBackSwipeTranslationLarge: CGFloat = 50
    static let uiBackSwipeTranslationSmall: CGFloat = 30
    static let uiBackSwipeVelocityThreshold: CGFloat = 300

    static let uiColorHueAvoidanceDelta: Double = 0.05
    static let uiColorHueOffset: Double = 0.12

    static let uiPeerPaletteSlots: Int = 36
    static let uiPeerPaletteRingBrightnessDeltaLight: Double = 0.07
    static let uiPeerPaletteRingBrightnessDeltaDark: Double = -0.07

    static let uiWindowInitialCountPublic: Int = 300
    static let uiWindowInitialCountPrivate: Int = 300
    static let uiWindowStepCount: Int = 200

    static let uiShareExtensionDismissDelaySeconds: TimeInterval = 2.0
    static let uiShareAcceptWindowSeconds: TimeInterval = 30.0
    static let uiMigrationCutoffSeconds: TimeInterval = 24 * 60 * 60

    static let syncSeenCapacity: Int = 1000
    static let syncGCSMaxBytes: Int = 400
    static let syncGCSTargetFpr: Double = 0.01
    static let syncMaxMessageAgeSeconds: TimeInterval = 900
    static let syncMaintenanceIntervalSeconds: TimeInterval = 30.0
    static let syncStalePeerCleanupIntervalSeconds: TimeInterval = 60.0
    static let syncStalePeerTimeoutSeconds: TimeInterval = 60.0
    static let syncFragmentCapacity: Int = 600
    static let syncFileTransferCapacity: Int = 200
    static let syncFragmentIntervalSeconds: TimeInterval = 30.0
    static let syncFileTransferIntervalSeconds: TimeInterval = 60.0
    static let syncMessageIntervalSeconds: TimeInterval = 15.0
}

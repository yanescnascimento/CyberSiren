package com.cybersiren.android.model

import com.cybersiren.android.protocol.BitchatPacket

data class RoutedPacket(
    val packet: BitchatPacket,
    val peerID: String? = null,
    val relayAddress: String? = null,
    val transferId: String? = null
)

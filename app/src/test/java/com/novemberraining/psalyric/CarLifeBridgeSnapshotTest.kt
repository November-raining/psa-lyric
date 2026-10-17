package com.novemberraining.psalyric

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifeBridgeSnapshotTest {
    @Test fun freshSnapshotIsAcceptedInsideWatchdogWindow() {
        val snapshot = CarLifeBridgeSnapshot(
            foreground = true,
            connected = true,
            enabled = true,
            receivedElapsedMs = 10_000L
        )
        assertTrue(snapshot.isFresh(16_000L, 6_500L))
    }

    @Test fun staleSnapshotFailsClosed() {
        val snapshot = CarLifeBridgeSnapshot(
            foreground = true,
            connected = true,
            enabled = true,
            receivedElapsedMs = 10_000L
        )
        assertFalse(snapshot.isFresh(16_501L, 6_500L))
    }

    @Test fun neverReceivedSnapshotIsNotFresh() {
        assertFalse(CarLifeBridgeSnapshot().isFresh(1_000L, 6_500L))
    }

    @Test fun bridgeDefaultsEnabledForOlderBridgeCompatibility() {
        assertTrue(CarLifeBridgeSnapshot().enabled)
    }
}

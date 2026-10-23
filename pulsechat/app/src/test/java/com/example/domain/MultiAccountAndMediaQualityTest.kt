package com.example.domain

import com.example.domain.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiAccountAndMediaQualityTest {

    @Test
    fun `multi-account policy isolates database and preferences names and routes notifications`() {
        val dbA = MultiAccountPolicy.databaseNameFor("alice_uid")
        val dbB = MultiAccountPolicy.databaseNameFor("bob_uid")
        assertTrue(dbA != dbB)
        assertEquals("pulsechat_account_alice_uid.db", dbA)
        assertEquals("pulse_prefs_acct_alice_uid", MultiAccountPolicy.prefsNameFor("alice_uid"))

        val acctA = MultiAccountPolicy.StoredAccount(uid = "alice", displayName = "Alice", username = "alice", lastActiveAt = 100L)
        val acctB = MultiAccountPolicy.StoredAccount(uid = "bob", displayName = "Bob", username = "bob", lastActiveAt = 200L)
        val list = MultiAccountPolicy.upsertAccount(listOf(acctA), acctB)
        assertEquals(listOf("bob", "alice"), list.map { it.uid })

        assertEquals(
            MultiAccountPolicy.SwitchEligibility.Allowed,
            MultiAccountPolicy.checkSwitch("alice", "bob", list, isCallActive = false, isSwitchInProgress = false),
        )
        assertEquals(
            MultiAccountPolicy.SwitchEligibility.Denied(MultiAccountPolicy.SwitchDenial.CALL_IN_PROGRESS),
            MultiAccountPolicy.checkSwitch("alice", "bob", list, isCallActive = true, isSwitchInProgress = false),
        )

        assertEquals(
            MultiAccountPolicy.NotificationRoute.OpenDirectly,
            MultiAccountPolicy.routeNotification(activeUid = "alice", notificationAccountUid = "alice", storedAccounts = list),
        )
        assertEquals(
            MultiAccountPolicy.NotificationRoute.SwitchThenOpen("bob"),
            MultiAccountPolicy.routeNotification(activeUid = "alice", notificationAccountUid = "bob", storedAccounts = list),
        )
        assertEquals(
            MultiAccountPolicy.NotificationRoute.Ignore,
            MultiAccountPolicy.routeNotification(activeUid = "alice", notificationAccountUid = "stranger", storedAccounts = list),
        )

        val removal = MultiAccountPolicy.removeAccount(list, removeUid = "alice", currentActiveUid = "alice")
        assertEquals("bob", removal.nextActiveUid)
        assertEquals(listOf("bob"), removal.remaining.map { it.uid })
    }

    @Test
    fun `media quality policy estimates sizes, dimensions, and HD benefit accurately`() {
        val hiResProbe = MediaQualityPolicy.MediaProbe(
            type = MessageType.IMAGE,
            mime = "image/jpeg",
            originalSizeBytes = 6_000_000L,
            width = 4000,
            height = 3000,
        )
        assertTrue(MediaQualityPolicy.canBenefitFromHd(hiResProbe))

        val stdInfo = MediaQualityPolicy.describeOption(MediaQuality.STANDARD, hiResProbe, maxAllowedBytes = 16_000_000L)
        val hdInfo = MediaQualityPolicy.describeOption(MediaQuality.HD, hiResProbe, maxAllowedBytes = 16_000_000L)
        val origInfo = MediaQualityPolicy.describeOption(MediaQuality.ORIGINAL, hiResProbe, maxAllowedBytes = 16_000_000L)

        assertEquals(1600 to 1200, stdInfo.targetWidth to stdInfo.targetHeight)
        assertEquals(2560 to 1920, hdInfo.targetWidth to hdInfo.targetHeight)
        assertEquals(4000 to 3000, origInfo.targetWidth to origInfo.targetHeight)
        assertTrue(stdInfo.estimatedBytes < hdInfo.estimatedBytes)
        assertEquals(6_000_000L, origInfo.estimatedBytes)
        assertFalse(origInfo.isEstimated)

        val smallProbe = MediaQualityPolicy.MediaProbe(
            type = MessageType.IMAGE,
            mime = "image/jpeg",
            originalSizeBytes = 250_000L,
            width = 1200,
            height = 900,
        )
        assertFalse(MediaQualityPolicy.canBenefitFromHd(smallProbe))
        val hdSmall = MediaQualityPolicy.describeOption(MediaQuality.HD, smallProbe, maxAllowedBytes = 16_000_000L)
        assertFalse(hdSmall.canBenefitFromHd)
        assertEquals(1200 to 900, hdSmall.targetWidth to hdSmall.targetHeight)
    }
}

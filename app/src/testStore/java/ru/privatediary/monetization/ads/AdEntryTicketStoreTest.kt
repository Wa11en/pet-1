package ru.privatediary.monetization.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File

class AdEntryTicketStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val directory get() = temporary.root
    private val store get() = AdEntryTicketStore(directory)

    @Test fun freshLaunchPermitCanBeConsumedOnlyOnceAcrossInstances() {
        val issued = store.issue(1_000_000, 20_000)
        assertEquals(issued, store.consume(1_001_000, 21_000))
        assertNull(store.consume(1_001_001, 21_001))
        assertFalse(File(directory, "entry.ticket").exists())
    }

    @Test fun abandonedLaunchExpiresAndCannotBeRetried() {
        store.issue(1_000_000, 20_000)
        assertNull(store.consume(1_015_001, 35_001))
        assertNull(store.consume(1_000_001, 20_001))
    }

    @Test fun elapsedClockResetOrWallClockRollbackFailsClosed() {
        store.issue(1_000_000, 20_000)
        assertNull(store.consume(1_000_100, 100))
        store.issue(1_000_000, 20_000)
        assertNull(store.consume(999_999, 20_001))
    }

    @Test fun oldPermitCannotBecomeValidAfterRebootEvenWithMatchingElapsedClock() {
        store.issue(1_000_000, 20_000)
        assertNull(store.consume(2_000_000, 20_001))
    }

    @Test fun failedLaunchRevocationRemovesItsPermission() {
        store.issue(1_000_000, 20_000)
        store.revoke()
        assertNull(store.consume(1_000_001, 20_001))
    }

    @Test fun eachTapReplacesTheAbandonedNonce() {
        val first = store.issue(1_000_000, 20_000)
        val second = store.issue(1_000_001, 20_001)
        assertNotEquals(first, second)
        assertEquals(second, store.consume(1_000_002, 20_002))
    }

    @Test fun malformedOversizedAndTrailingDataAreRejectedAndRemoved() {
        File(directory, "entry.ticket").writeBytes(byteArrayOf(0, 100, 1))
        assertNull(store.consume(1_000_001, 20_001))
        File(directory, "entry.ticket").writeBytes(ByteArray(257))
        assertNull(store.consume(1_000_001, 20_001))
        store.issue(1_000_000, 20_000)
        File(directory, "entry.ticket").appendBytes(byteArrayOf(0))
        assertNull(store.consume(1_000_001, 20_001))
        assertFalse(File(directory, "entry.ticket").exists())
    }

    @Test fun malformedNonceIsRejectedEvenWithValidTimestamps() {
        DataOutputStream(File(directory, "entry.ticket").outputStream()).use {
            it.writeUTF("not-a-grant")
            it.writeLong(1_000_000)
            it.writeLong(20_000)
        }
        assertNull(store.consume(1_000_001, 20_001))
    }
}

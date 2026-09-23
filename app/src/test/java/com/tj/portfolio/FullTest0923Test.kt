package com.tj.portfolio

import com.tj.portfolio.ui.backupTxnCount
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the full-test audit of 2026-09-23 (audits/2026-09-23/*.md) that do not
 * belong to an existing subsystem test file. Each test names the finding it pins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FullTest0923Test {

    // ---- A-1: the autosave is never shrunk without keeping the larger copy; that decision
    // rests on reading a backup's transaction count correctly.

    @Test fun `A-1 backup count reads the manifest, falls back to the array, and knows unknown`() {
        assertEquals(200, backupTxnCount("""{"counts":{"transactions":200},"transactions":[]}"""))
        assertEquals(2, backupTxnCount("""{"transactions":[{},{}]}"""))
        assertEquals(-1, backupTxnCount(null))
        assertEquals(-1, backupTxnCount(""))
        assertEquals(-1, backupTxnCount("not json"))
        // A missing old file (-1) is "smaller" than any real one, so nothing is rotated for it.
        assertEquals(true, backupTxnCount("""{"transactions":[{}]}""") > backupTxnCount(null))
    }
}

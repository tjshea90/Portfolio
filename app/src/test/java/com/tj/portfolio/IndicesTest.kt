package com.tj.portfolio

import com.tj.portfolio.data.Quote
import com.tj.portfolio.net.Indices
import com.tj.portfolio.ui.indexLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IndicesTest {
    @Test fun listHasTheBigThree() {
        assertTrue(listOf("^GSPC", "^DJI", "^IXIC").all { it in Indices.SYMBOLS })
        assertEquals(Indices.SYMBOLS.size, Indices.SYMBOLS.toSet().size)
    }
    @Test fun roundTripsForOffline() {
        val q = Quote("^GSPC", price = 5000.5, prevClose = 4990.0, updated = 7L)
        val back = Indices.decode(Indices.encode(listOf(q)))["^GSPC"]!!
        assertEquals(5000.5, back.price, 0.0); assertEquals(10.5, back.dayChange, 1e-9)
        assertTrue(Indices.decode("garbage").isEmpty())
    }
    @Test fun levelFormatting() { assertEquals("5,000.50", indexLevel("^GSPC", 5000.5)); assertEquals("4.25", indexLevel("^TNX", 4.25)) }
}

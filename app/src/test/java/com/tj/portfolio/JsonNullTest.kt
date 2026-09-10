package com.tj.portfolio

import com.tj.portfolio.net.EtfScreener
import com.tj.portfolio.net.ResearchBridge
import com.tj.portfolio.net.Screener
import com.tj.portfolio.util.text
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE org.json NULL TRAP - the one this app has already paid for once (Round 63 sweep).
 *
 * `optString(key)` on an explicit JSON `null` returns the literal STRING `"null"`, not the
 * empty fallback the call site plainly means. In v1 that turned all eight of TJ's cash rows
 * (DEPOSIT, `symbol: null`) into a phantom symbol called "NULL". The backup restore was fixed
 * at the time; the guard was never generalised, and the two parsers added this round - the
 * Yahoo fund screener and the Claude reply reader - both walked straight back into it.
 *
 * It matters most on the Claude path, because language models routinely emit `null` for an
 * optional field even when the schema asks for `""`. Left unguarded that paints the word
 * "null" under a card, hangs a red "NULL" inverse-ETF chip on a stock, and can insert a
 * fabricated ticker row called NULL into a list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JsonNullTest {

    // ------------------------------------------------------------------ the guard

    @Test fun `text returns empty for an explicit null, a missing key and a real value`() {
        val o = JSONObject().apply {
            put("real", "hello")
            put("nulled", JSONObject.NULL)
            put("empty", "")
        }
        assertEquals("hello", o.text("real"))
        assertEquals("", o.text("nulled"))
        assertEquals("", o.text("missing"))
        assertEquals("", o.text("empty"))
    }

    @Test fun `the fallback form fires where optString would have skipped it`() {
        val o = JSONObject().apply { put("longName", JSONObject.NULL); put("shortName", "SPDR") }
        // The whole point: "null" is not blank, so `optString(...).ifBlank { ... }` silently
        // keeps the string "null" and never consults the fallback.
        assertEquals("SPDR", o.text("longName").ifBlank { o.text("shortName") })
        assertEquals("SPDR", o.text("longName", o.text("shortName")))
    }

    @Test fun `an array element that is null reads as empty too`() {
        val a = JSONArray().apply { put("one"); put(JSONObject.NULL); put("three") }
        assertEquals("one", a.text(0))
        assertEquals("", a.text(1))
        assertEquals("three", a.text(2))
        assertEquals("", a.text(99))
        assertEquals("", a.text(-1))
    }

    // --------------------------------------------------------- the Claude reply

    @Test fun `a reply with null optional fields does not paint the word null`() {
        val reply = """
            {"portfolioAppResponse":1,"research":{"best":[
              {"symbol":"XYZ","why":"The business is growing.",
               "catalyst":null,"target":null,"risk":null}
            ],"notes":null}}
        """.trimIndent()
        val p = ResearchBridge.parse(reply)
        assertNull(p.error)
        assertEquals(1, p.best.size)
        val row = p.best.first()
        assertEquals("XYZ", row.symbol)
        assertEquals("The business is growing.", row.why)
        assertEquals("a null catalyst was painted under the card", "", row.catalyst)
        assertEquals("", p.notes)
    }

    @Test fun `a reply with a null symbol does not invent a ticker called NULL`() {
        val reply = """
            {"portfolioAppResponse":1,"research":{"best":[
              {"symbol":null,"why":"Something about a company I forgot to name."},
              {"symbol":"NVDA","why":"A real row."}
            ]}}
        """.trimIndent()
        val p = ResearchBridge.parse(reply)
        assertEquals(listOf("NVDA"), p.best.map { it.symbol })
    }

    @Test fun `a null category still lets the real catalyst through`() {
        val reply = """
            {"portfolioAppResponse":1,"research":{"etfs":[
              {"symbol":"VTI","why":"The whole market.","catalyst":null,
               "category":"broad US equity index"}
            ]}}
        """.trimIndent()
        val p = ResearchBridge.parse(reply)
        assertEquals("broad US equity index", p.etfs.first().catalyst)
    }

    // ------------------------------------------------------- the Yahoo screeners

    @Test fun `a fund row with a null name falls through to its short name`() {
        val body = """
{"finance":{"result":[{"quotes":[
  {"symbol":"XYZ","quoteType":"ETF","longName":null,"displayName":null,
   "shortName":"Some Bond ETF","regularMarketPrice":50.0,"netExpenseRatio":0.1,
   "netAssets":1.0E9,"annualReturnNavY5":6.0,"averageDailyVolume3Month":100000,
   "firstTradeDateMilliseconds":1300000000000,"fullExchangeName":null}
]}]}}
""".trimIndent()
        val rows = EtfScreener.parse("top_etfs_us", body)
        assertEquals(1, rows.size)
        assertEquals("Some Bond ETF", rows[0].name)
        assertEquals("", rows[0].exchange)
        assertFalse(
            "a fund named \"null\" was fed to the leverage filter",
            com.tj.portfolio.net.EtfScore.isLeveragedOrInverse(rows[0].name, rows[0].symbol)
        )
    }

    @Test fun `a stock row with a null name falls through too`() {
        val body = """
{"finance":{"result":[{"quotes":[
  {"symbol":"ABC","quoteType":"EQUITY","longName":null,"displayName":null,
   "shortName":"Abc Corp","regularMarketPrice":10.0}
]}]}}
""".trimIndent()
        val rows = Screener.parse("day_gainers", body)
        assertEquals(1, rows.size)
        assertEquals("Abc Corp", rows[0].name)
    }

    @Test fun `a null quoteType is not mistaken for a fund`() {
        val body = """{"finance":{"result":[{"quotes":[{"symbol":"ABC","quoteType":null}]}]}}"""
        assertTrue(EtfScreener.parse("top_etfs_us", body).isEmpty())
        assertTrue(Screener.parse("day_gainers", body).isEmpty())
    }

    // ------------------------------------------------- the terminal page (F19)

    @Test fun `a valid page carrying no quotes is recognised as an answer`() {
        // So `fetch` stops rather than asking the other Yahoo host the identical question -
        // every list's last page used to cost two requests instead of one.
        assertTrue(EtfScreener.isWellFormed("""{"finance":{"result":[{"quotes":[]}]}}"""))
        assertFalse(EtfScreener.isWellFormed(""))
        assertFalse(EtfScreener.isWellFormed("not json"))
        assertFalse(EtfScreener.isWellFormed("""{"finance":{"result":[]}}"""))
        assertFalse(EtfScreener.isWellFormed("""{"finance":{"error":{"code":"Not Found"}}}"""))
    }
}

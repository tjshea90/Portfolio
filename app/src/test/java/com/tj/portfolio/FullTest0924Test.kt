package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.ui.BUSY_EXPLAINING
import com.tj.portfolio.ui.PULL_PRICES
import com.tj.portfolio.ui.PortfolioViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Regression tests for the 2026-09-24 full test (the reports in audits/2026-09-24). One section per
 * finding ID; the pure-logic halves live next to the code they test where a file exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FullTest0924Test {

    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(Db.DB_NAME)
    }

    @After fun tearDown() { app.deleteDatabase(Db.DB_NAME) }

    private fun settle() {
        ShadowLooper.idleMainLooper(); Thread.sleep(120); ShadowLooper.idleMainLooper()
    }

    @Suppress("UNCHECKED_CAST")
    private fun researchBusy(vm: PortfolioViewModel): MutableStateFlow<String> =
        PortfolioViewModel::class.java.getDeclaredField("_researchBusy")
            .apply { isAccessible = true }.get(vm) as MutableStateFlow<String>

    private fun awaitQuotesIdle(vm: PortfolioViewModel) {
        repeat(60) { if (!vm.ui.value.loading) return; settle() }
    }

    // ---- U-1: a pull on a price screen waits only on the price pass.

    /**
     * Tj's 09-23c screenshot, in the ViewModel: "Explain with Claude" is running on Research,
     * he pulls down on Portfolio, the prices land - and the circle used to keep spinning until
     * Claude answered, then for up to another fifteen minutes (a research job's end never
     * re-derived the flag; only the closed-market poll tick did).
     */
    @Test fun `U-1 a price pull ends with its own pass while a research job is still running`() {
        val vm = PortfolioViewModel(app).also { settle() }
        awaitQuotesIdle(vm)
        val busy = researchBusy(vm)
        busy.value = BUSY_EXPLAINING

        vm.refresh(manual = true)
        settle()
        awaitQuotesIdle(vm)

        assertFalse("the price pass should have finished", vm.ui.value.loading)
        assertTrue("the research job is still running", busy.value.isNotEmpty())
        assertFalse("the Portfolio circle must stop when ITS prices land, not when Claude answers",
            vm.ui.value.pulling(PULL_PRICES))
    }
}

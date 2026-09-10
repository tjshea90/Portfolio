package com.tj.portfolio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.Keys
import com.tj.portfolio.data.PlMode
import com.tj.portfolio.ui.PortfolioViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * ROUND 61: the last link in the chain, exercised on the REAL ViewModel.
 *
 * Everything else about this feature is proven elsewhere - the helpers as pure functions, the
 * row and the summary card as rendered trees, the setting against real SQLite. What none of
 * those can show is the piece in the middle: that `togglePlMode` puts the new value on
 * `UiState` (so screens redraw) AND writes it to disk (so tomorrow it is still there), and
 * that a ViewModel built on a database which already holds a choice comes up with it.
 *
 * The seeding order is the subtle part and the reason this test exists. `init` copies the
 * stored mode onto `_ui` and then calls `recompute()`, which copies the state object again -
 * do those two in the other order and the seed is silently overwritten by the default on
 * every launch, which is a bug that would look exactly like "the setting does not save".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlModeViewModelTest {

    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(Db.DB_NAME)
    }

    @After fun tearDown() {
        app.deleteDatabase(Db.DB_NAME)
    }

    /** Lets the coroutines `init` and the setter launch actually run. */
    private fun settle() {
        ShadowLooper.idleMainLooper()
        Thread.sleep(120)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `a fresh install starts in dollars`() {
        val vm = PortfolioViewModel(app)
        settle()
        assertEquals(PlMode.DOLLAR, vm.ui.value.plMode)
    }

    @Test
    fun `toggling publishes the new mode so screens redraw`() {
        val vm = PortfolioViewModel(app)
        settle()
        assertEquals(PlMode.DOLLAR, vm.ui.value.plMode)

        vm.togglePlMode()
        assertEquals("the toggle did not reach UiState", PlMode.PERCENT, vm.ui.value.plMode)

        vm.togglePlMode()
        assertEquals(PlMode.DOLLAR, vm.ui.value.plMode)
    }

    @Test
    fun `toggling writes the choice to disk`() {
        val vm = PortfolioViewModel(app)
        settle()
        vm.setPlMode(PlMode.PERCENT)
        settle()
        Db(app).use { db ->
            assertEquals(
                "the toggle did not persist", PlMode.PERCENT, PlMode.byName(db.get(Keys.PL_MODE))
            )
        }
    }

    /**
     * THE SEEDING ORDER. A ViewModel built on a database that already holds PERCENT must come
     * up in PERCENT - not in the default, which is what happens if the seed runs before
     * something that copies the state object back over it.
     */
    @Test
    fun `a stored choice is picked up at startup`() {
        Db(app).use { it.set(Keys.PL_MODE, PlMode.PERCENT.name) }
        val vm = PortfolioViewModel(app)
        settle()
        assertEquals(
            "the stored mode was overwritten during init", PlMode.PERCENT, vm.ui.value.plMode
        )
    }

    /** A corrupt or unknown stored value must not stop the app coming up. */
    @Test
    fun `an unreadable stored value comes up as dollars`() {
        Db(app).use { it.set(Keys.PL_MODE, "not-a-mode") }
        val vm = PortfolioViewModel(app)
        settle()
        assertEquals(PlMode.DOLLAR, vm.ui.value.plMode)
    }

    /** Setting the mode it is already in must not churn state or write to disk again. */
    @Test
    fun `setting the same mode twice is a no-op`() {
        val vm = PortfolioViewModel(app)
        settle()
        val before = vm.ui.value
        vm.setPlMode(PlMode.DOLLAR)
        assertEquals("a no-op set replaced the state object", before, vm.ui.value)
    }
}

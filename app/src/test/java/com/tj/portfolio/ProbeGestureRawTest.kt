package com.tj.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.tj.portfolio.ui.ChartGesture
import com.tj.portfolio.ui.chartGestures
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProbeGestureRawTest {
    @get:Rule val rule = createComposeRule()

    private val log = ArrayList<String>()

    private fun show(canPan: Boolean) {
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.size(300.dp).testTag("t").pointerInput(Unit) {
                        chartGestures(
                            pointAt = { log.add("scrub") },
                            clearPoint = { },
                            zoom = { null },
                            pinch = { { _, _ -> log.add("pinch") } },
                            pan = { { f -> log.add("pan:$f") } },
                            canPan = { canPan },
                            onHold = { log.add("HOLD") },
                            onGesture = { g -> if (g != ChartGesture.NONE) log.add("G:$g") }
                        )
                    }
                )
            }
        }
    }

    private fun summary() = log.groupingBy { it.substringBefore(":") }.eachCount().toString()

    @Test fun `probe slow 3px 100ms`() {
        show(canPan = true)
        rule.onNodeWithTag("t").performTouchInput {
            val x0 = width * 0.8f; val y = height * 0.5f
            down(Offset(x0, y))
            for (i in 1..10) { advanceEventTime(100); moveTo(Offset(x0 - i * 3f, y)) }
            up()
        }
        rule.waitForIdle()
        assertTrue("PROBE slow100 ${summary()} first=${log.take(4)}", false)
    }

    @Test fun `probe slow 3px noadvance`() {
        show(canPan = true)
        rule.onNodeWithTag("t").performTouchInput {
            val x0 = width * 0.8f; val y = height * 0.5f
            down(Offset(x0, y))
            for (i in 1..10) { moveTo(Offset(x0 - i * 3f, y)) }
            up()
        }
        rule.waitForIdle()
        assertTrue("PROBE noadv ${summary()} first=${log.take(4)}", false)
    }

    @Test fun `probe hold`() {
        show(canPan = true)
        rule.onNodeWithTag("t").performTouchInput {
            val x0 = width * 0.5f; val y = height * 0.5f
            down(Offset(x0, y))
            advanceEventTime(600)
            moveTo(Offset(x0 + 1f, y))
            moveTo(Offset(x0 + 100f, y))
            up()
        }
        rule.waitForIdle()
        assertTrue("PROBE hold ${summary()} first=${log.take(4)}", false)
    }

    @Test fun `probe fast`() {
        show(canPan = true)
        rule.onNodeWithTag("t").performTouchInput {
            val x0 = width * 0.8f; val y = height * 0.5f
            down(Offset(x0, y))
            for (i in 1..5) { advanceEventTime(16); moveTo(Offset(x0 - i * 20f, y)) }
            up()
        }
        rule.waitForIdle()
        assertTrue("PROBE fast ${summary()} first=${log.take(4)}", false)
    }
}

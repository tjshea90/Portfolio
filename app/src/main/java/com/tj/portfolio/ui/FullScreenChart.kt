package com.tj.portfolio.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.tj.portfolio.data.ChartRange
import com.tj.portfolio.data.ChartSeries
import com.tj.portfolio.data.ChartWindow

/** Test handle for the full-screen viewer's root. */
internal const val FULLSCREEN_TAG = "chartFullScreen"

/** Test handle for its close button. */
internal const val FULLSCREEN_CLOSE_TAG = "chartFullScreenClose"

/**
 * THE CHART, FULL SCREEN, TURNING WITH THE PHONE (Round 64).
 *
 * TJ: *"for any chart anywhere in the app, make it so I can press it and it opens full screen
 * and can rotate landscape or portrait with the phone sensors."*
 *
 * ---- IT IS THE SAME CHART, NOT A SECOND ONE
 *
 * Everything inside is [PriceChart] and [RangeChips] with different numbers passed in. A
 * second drawing implementation for the large size would have been quicker to write and would
 * have drifted within a round: the comparison overlay, the live edge rule, the zoom window and
 * the after-hours gap are all decisions this app has already made once, and making them twice
 * is how two views of the same data start disagreeing.
 *
 * ---- WHY THE ORIENTATION IS FORCED WHILE IT IS OPEN
 *
 * "with the phone sensors" is the requirement, and a phone with rotation lock on - which most
 * phones have on most of the time - will not rotate an app that merely permits it. So the
 * viewer asks for `SCREEN_ORIENTATION_FULL_SENSOR` for as long as it is on screen and puts the
 * setting back to `UNSPECIFIED` when it closes, which is what every full-screen media viewer
 * does. The restore is in a `DisposableEffect`, so it happens even if the screen is left by a
 * route nobody thought of - a back gesture, a process pause, a crash in a sibling composable.
 *
 * The activity itself declares `configChanges` for orientation (see the manifest), so the
 * rotation costs a re-layout rather than a restart: the stock stays open, the zoom window
 * survives, and turning the phone back leaves everything where it was.
 */
@Composable
fun FullScreenChart(
    symbol: String,
    series: ChartSeries?,
    range: ChartRange,
    loading: Boolean,
    onRange: (ChartRange) -> Unit,
    perf: Map<ChartRange, Double>,
    loadingRanges: Set<ChartRange>,
    livePrice: Double,
    liveEdge: Boolean,
    window: ChartWindow?,
    windowBounds: ChartWindow?,
    onWindow: (ChartWindow) -> Unit,
    onResetWindow: () -> Unit,
    onZoomingChanged: (Boolean) -> Unit,
    compare: ChartSeries?,
    compareLabel: String,
    compareLivePrice: Double,
    onClose: () -> Unit,
    // THE "vs SPY" SWITCH IN HERE TOO (chart idea 4, 2026-09-24b) - turning the benchmark on or
    // off used to mean closing the full-screen view to reach the chip under the inline chart.
    compareOffered: Boolean = false,
    compareOn: Boolean = false,
    compareLoading: Boolean = false,
    onToggleCompare: () -> Unit = {}
) {
    val context = LocalContext.current

    DisposableEffect(context) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose {
            // Back to whatever it was, and to UNSPECIFIED when it was nothing - never left on
            // FULL_SENSOR, or closing the viewer would silently disable the user's rotation
            // lock for the rest of the session.
            activity?.requestedOrientation =
                previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Dialog(
        onDismissRequest = onClose,
        // BOTH FLAGS MATTER. `usePlatformDefaultWidth = false` is what lets the dialog be the
        // whole screen rather than an inset card; without `decorFitsSystemWindows = false` the
        // window would be re-laid-out with the old insets after a rotation and leave a band of
        // background down one side in landscape.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // ---- THE STATUS BAR OVER *THIS* WINDOW (sweep 4).
        //
        // A dialog is its own window, and the light/dark polarity of the system-bar icons is a
        // WINDOW property taken from the theme when that window is created. `MainActivity`
        // re-applies it to the ACTIVITY's window when the configuration changes, and nothing
        // reaches this one - so toggling dark mode with the chart open left the clock and
        // icons in the previous theme's colour until it was closed.
        //
        // INSIDE the dialog's content, because that is the only place `LocalView` is the
        // dialog's own view rather than the activity's; its parent implements
        // `DialogWindowProvider`, which is how Compose exposes the window it created.
        val dark = LocalDarkTheme.current
        val view = LocalView.current
        LaunchedEffect(dark, view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) {
                val bars = WindowCompat.getInsetsController(window, view)
                bars.isAppearanceLightStatusBars = !dark
                // BOTH BARS. The status bar was the obvious one and the navigation bar has the
                // same fault from the other end: this window's theme sets no
                // `windowLightNavigationBar`, so in light mode the gesture handle came up white
                // over a near-white page for as long as the chart was open.
                bars.isAppearanceLightNavigationBars = !dark
            }
        }

        Surface(
            Modifier
                .fillMaxSize()
                .testTag(FULLSCREEN_TAG),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        // ---- THE SYSTEM BARS (Round 64 sweep 3).
                        //
                        // `decorFitsSystemWindows = false` is what lets this window be the
                        // whole screen rather than an inset card, and it also means NOTHING
                        // moves the content out from under the status bar, the navigation
                        // pill or a display cutout unless this does. Without it the symbol,
                        // the range chips and the close button sat behind the clock in
                        // portrait and slid under the notch in landscape.
                        .safeDrawingPadding()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            symbol,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(10.dp))
                        RangeChips(
                            selected = range,
                            onSelect = onRange,
                            perf = perf,
                            loading = loadingRanges,
                            modifier = Modifier.weight(1f)
                        )
                        if (compareOffered) {
                            Spacer(Modifier.width(6.dp))
                            CompareToggle(on = compareOn, loading = compareLoading, onClick = onToggleCompare)
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.testTag(FULLSCREEN_CLOSE_TAG)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close the full-screen chart",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    PriceChart(
                        series = series,
                        range = range,
                        loading = loading,
                        livePrice = livePrice,
                        liveEdge = liveEdge,
                        window = window,
                        windowBounds = windowBounds,
                        onWindow = onWindow,
                        onResetWindow = onResetWindow,
                        onZoomingChanged = onZoomingChanged,
                        compare = compare,
                        compareLabel = compareLabel,
                        compareLivePrice = compareLivePrice,
                        // ---- MEASURED, NOT BUDGETED (Round 64 sweep 3).
                        //
                        // This used to subtract a fixed 150dp for the header, chips, readout,
                        // axis and caption. That allowance had no slack in it: turning the SPY
                        // overlay on added a legend row that pushed the caption off the
                        // bottom, and every part of it is text in sp, so any font scale above
                        // 1.0 overflowed too. The chart takes whatever is left after the rest
                        // of the column has measured itself, which is right at every size.
                        chartFillsHeight = true,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                        // NO `onExpand` HERE, deliberately: this IS the expanded view, and a
                        // button that opened a second copy of it is a trap rather than a
                        // feature.
                    )
                }
            }
        }
    }
}


/** The activity behind a composition's context, through however many wrappers. */
internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

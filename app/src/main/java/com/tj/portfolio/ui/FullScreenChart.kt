package com.tj.portfolio.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    compare: ChartSeries?,
    compareLabel: String,
    compareLivePrice: Double,
    onClose: () -> Unit
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
        Surface(
            Modifier
                .fillMaxSize()
                .testTag(FULLSCREEN_TAG),
            color = MaterialTheme.colorScheme.background
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // WHAT IS LEFT AFTER EVERYTHING ELSE. The header, the chips, the readout and
                // the axis labels are all fixed heights; the chart gets the remainder, which
                // is what makes landscape worth entering at all. The floor stops a very short
                // window (a split-screen phone) from collapsing the drawn area to nothing.
                val chartHeight = (maxHeight - RESERVED.dp).coerceAtLeast(140.dp)

                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
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
                        modifier = Modifier.fillMaxWidth(),
                        livePrice = livePrice,
                        liveEdge = liveEdge,
                        window = window,
                        windowBounds = windowBounds,
                        onWindow = onWindow,
                        onResetWindow = onResetWindow,
                        compare = compare,
                        compareLabel = compareLabel,
                        compareLivePrice = compareLivePrice,
                        chartHeight = chartHeight
                        // NO `onExpand` HERE, deliberately: this IS the expanded view, and a
                        // button that opened a second copy of it is a trap rather than a
                        // feature.
                    )
                }
            }
        }
    }
}

/**
 * Everything above and below the drawn area, in dp.
 *
 * Measured from the pieces rather than guessed: the header row is 48, the readout 22 with its
 * 8dp gap, the axis labels about 19 with their 3dp gap, the caption about 34, and the vertical
 * padding 12. `FullScreenChartUiTest` renders the viewer at two window sizes and asserts the
 * chart is the taller thing on screen, so a change to any of those parts is caught here rather
 * than by TJ opening a chart and finding it cut off.
 */
private const val RESERVED = 150

/** The activity behind a composition's context, through however many wrappers. */
internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

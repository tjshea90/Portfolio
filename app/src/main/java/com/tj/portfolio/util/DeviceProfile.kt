package com.tj.portfolio.util

import android.app.ActivityManager
import android.content.Context

/**
 * WHAT KIND OF DEVICE THIS IS (2026-10-27) — the app now runs on an Android 10 Go tablet
 * (MediaTek MT8168: four Cortex-A53 cores at 2 GHz, 2 GB RAM or less, eMMC storage, a per-app
 * heap near 192 MB). Android marks such hardware `isLowRamDevice`, and that one flag is what the
 * app keys its concurrency and heap-cache sizes on, so nothing is guessed from a model name and
 * a phone with plenty of RAM behaves exactly as before.
 *
 * Only the knobs that cost memory or CPU are scaled: how many requests run at once (each holds a
 * decoded response body and a parser), and the in-heap response cache. Correctness, what is
 * fetched and what is stored are identical on every device.
 */
object DeviceProfile {

    @Volatile var lowRam: Boolean = false
        private set

    fun init(ctx: Context) {
        lowRam = runCatching {
            (ctx.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
        }.getOrDefault(false)
    }

    /** [n] concurrent operations on a normal device, about 60% of that (never under 2) on a low-RAM one. */
    fun parallel(n: Int): Int = if (lowRam) maxOf(2, n * 3 / 5) else n

    /** Characters of response bodies the HTTP layer may hold in the heap. */
    fun heapCacheChars(normal: Int): Int = if (lowRam) normal / 2 else normal

    internal fun setForTests(low: Boolean) { lowRam = low }
}

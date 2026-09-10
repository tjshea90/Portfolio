package com.tj.portfolio.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * "IS THERE A NETWORK AT ALL?" — the cheapest request is the one never sent.
 *
 * ROUND 59. `ACCESS_NETWORK_STATE` has been declared in the manifest since v1.0 and nothing
 * ever read it. Meanwhile a refresh with no signal fires the whole pass anyway: the radio is
 * woken, every socket fails, and `Http.noteUnreachable` counts three of those into a per-host
 * cooldown that escalates towards five minutes. So a lift, a tunnel or a dead spot cost
 * battery on the way in AND a stale screen for minutes on the way out.
 *
 * This is a local, synchronous, permission-backed answer to a question the app was otherwise
 * paying the network to answer for it.
 *
 * THE DECISION IS A PURE FUNCTION ([decide]) AND THE FRAMEWORK CALL IS A THIN ADAPTER. That
 * split is not ceremony: the first version of this file had a doc comment promising it was
 * "deliberately optimistic" sitting above a branch that returned false whenever it could not
 * read the manager - and a comment that disagrees with its code is how two of this project's
 * bugs shipped. Written as a truth table, the disagreement was visible immediately and every
 * row of it is now asserted in `RetryBackoffTest`.
 */
object Connectivity {

    /**
     * True unless the system is CERTAIN there is no usable network.
     *
     * @param managerPresent  the ConnectivityManager could be obtained at all.
     * @param hasActiveNetwork  there is an active network. The one case Android is certain
     *        about, and the only one that returns false.
     * @param capsKnown  capabilities could be read for that network.
     * @param hasInternet  those capabilities include `NET_CAPABILITY_INTERNET`.
     *
     * `NET_CAPABILITY_VALIDATED` is deliberately NOT part of this. A captive portal, a VPN
     * still coming up, or a network Android has not finished validating all report
     * unvalidated - and on those the app should still try, because the alternative is an app
     * that refuses to refresh on a connection that works.
     */
    internal fun decide(
        managerPresent: Boolean,
        hasActiveNetwork: Boolean,
        capsKnown: Boolean,
        hasInternet: Boolean
    ): Boolean = when {
        // Nothing to ask - assume online rather than disabling the app on an odd device.
        !managerPresent -> true
        // The definitive answer, and the only false in the table.
        !hasActiveNetwork -> false
        // A network exists but will not describe itself. Try.
        !capsKnown -> true
        else -> hasInternet
    }

    /** Reads the framework and hands the answer to [decide]. Never throws. */
    fun isOnline(ctx: Context): Boolean = runCatching {
        val cm = ctx.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = if (network == null) null else cm.getNetworkCapabilities(network)
        decide(
            managerPresent = cm != null,
            hasActiveNetwork = network != null,
            capsKnown = caps != null,
            hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        )
    // A SecurityException, a stubbed manager, an OEM that answers oddly: all mean "we do not
    // know", and not knowing must never be the reason the app stops refreshing.
    }.getOrDefault(true)
}

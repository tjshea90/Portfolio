package com.tj.portfolio.ui

import android.content.Context

/**
 * "MAKE PROMPT FILE" OPENS THE SHARE SHEET (2026-09-23b) - the one place every prompt button
 * finishes, so the four of them cannot behave differently.
 *
 * Tj picks the Claude app in the sheet and it starts a new chat with the prompt attached. If
 * the file could not be staged, or no app can take the share, [out]'s message says where the
 * Downloads copy is instead - the same fallback every prompt button has always had.
 */
fun launchPromptShare(ctx: Context, out: PromptOut, toast: (String) -> Unit) {
    val uri = out.share
    if (uri == null || !onScreen(ctx)) {
        // NOT AFTER THE APP HAS LEFT THE SCREEN (full test 2026-09-23, U-6). Building the
        // prompt can outlast a swipe home (the Advice button gathers news first), and a share
        // sheet started from a stopped activity is either silently blocked by Android's
        // background-start rules - no sheet, no message - or pops up over whatever app Tj
        // switched to. The Downloads copy exists either way; say where it is.
        toast(out.message)
        return
    }
    val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "prompt.md"
    val opened = runCatching {
        ctx.startActivity(com.tj.portfolio.util.PromptShare.chooser(uri, name))
    }.isSuccess
    if (!opened) toast(out.message)
}

/** True while the activity behind [ctx] is at least STARTED - i.e. actually on screen. */
private fun onScreen(ctx: Context): Boolean {
    var c: Context? = ctx
    while (c != null) {
        if (c is androidx.lifecycle.LifecycleOwner) {
            return c.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }
        c = (c as? android.content.ContextWrapper)?.baseContext
    }
    return true
}

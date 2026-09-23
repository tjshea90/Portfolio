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
    if (uri == null) {
        toast(out.message)
        return
    }
    val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "prompt.md"
    val opened = runCatching {
        ctx.startActivity(com.tj.portfolio.util.PromptShare.chooser(uri, name))
    }.isSuccess
    if (!opened) toast(out.message)
}

package com.tj.portfolio

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.tj.portfolio.net.SharedAnswer
import com.tj.portfolio.util.ShareInbox

/**
 * THE SHARE TARGET (2026-09-23b) - "Share -> Portfolio" on Claude's answer file lands here.
 *
 * A trampoline, deliberately, rather than an intent filter on [MainActivity] itself. A share
 * starts the receiving activity inside the SENDER's task, so filtering MainActivity would put
 * a second copy of the whole app - its own composition, its own navigation, a second
 * ViewModel - on top of the Claude app, while the real one sat untouched in its own task.
 * This activity has no UI: it copies the shared text into [ShareInbox] while it still holds
 * the sender's read grant, then brings the ONE real MainActivity forward (or starts it) with
 * [ShareInbox.ACTION_IMPORT], and finishes. MainActivity does the import and the navigation.
 *
 * The copy runs on a worker thread - a content URI can be slow to read, and this runs while
 * the user is watching - and the activity is translucent so there is nothing to see while it
 * does. `taskAffinity=""` plus `excludeFromRecents` in the manifest keep it out of both the
 * Claude app's task and the recents list.
 */
class ShareImportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A process started by a share runs HERE before MainActivity exists, so a crash in the
        // copy below would leave no trace (full test 2026-09-24, L-Q2). Idempotent.
        com.tj.portfolio.util.CrashLog.install(this)
        // A recreation (rotation, process restore) would read the share a second time; the
        // first run already handed it over.
        if (savedInstanceState != null) { finish(); return }
        val shared = intent ?: run { finish(); return }
        val app = applicationContext
        Thread {
            val text = runCatching {
                ShareInbox.readShared(this, shared, SharedAnswer.MAX_CHARS)
            }.getOrNull()
            val queued = text != null && ShareInbox.put(app, text)
            runOnUiThread {
                if (queued) {
                    startActivity(
                        Intent(app, MainActivity::class.java)
                            .setAction(ShareInbox.ACTION_IMPORT)
                            // NEW_TASK: go to the app's own task, not the sender's.
                            // CLEAR_TOP + SINGLE_TOP: reuse the running MainActivity through
                            // onNewIntent instead of stacking a second one on it.
                            .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                            )
                    )
                } else {
                    Toast.makeText(
                        app,
                        "Portfolio couldn't read that share. Share the answer file Claude " +
                            "wrote (a text or .md file under 2 MB).",
                        Toast.LENGTH_LONG
                    ).show()
                }
                finish()
            }
        }.start()
    }
}

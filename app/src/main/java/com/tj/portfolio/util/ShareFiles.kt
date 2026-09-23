package com.tj.portfolio.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import java.io.File

/**
 * THE TWO HALVES OF THE CLAUDE-APP ROUND TRIP, WITHOUT DOWNLOADS (2026-09-23b).
 *
 * Tj: "anywhere there is a 'make prompt file' for the Claude app, when I press it, it
 * automatically opens up an android 'share with' menu and I can select share with new Claude
 * chat ... then the portfolio app will automatically import the data ... just from the share."
 *
 * OUT ([PromptShare]): the prompt is written to a private cache folder and handed to the share
 * sheet through a [FileProvider] URI - a read grant for one file, to whichever app Tj picks,
 * for as long as that app is using it. The Downloads copy every prompt button has always
 * written is kept alongside, as the fallback if the share sheet cannot open.
 *
 * IN ([ShareInbox]): `ShareImportActivity` copies whatever was shared into a one-file inbox
 * and hands over to `MainActivity`, which imports it. The copy is taken inside the receiving
 * activity because that is the only place the sender's read grant is guaranteed to hold - by
 * the time MainActivity (possibly cold-starting a whole process) gets to it, a grant tied to
 * the trampoline could already be gone. And a file on disk, rather than an Intent extra,
 * because a large answer would overflow the ~1 MB Binder limit an extra has to fit in.
 */
object PromptShare {

    /** Under `cacheDir`, and the only folder `res/xml/share_paths.xml` exposes. */
    private const val DIR = "shared"

    fun authority(ctx: Context): String = ctx.packageName + ".files"

    /**
     * Writes [body] to `cache/shared/<fileName>` (replacing an older copy at the same name,
     * the same one-file-per-prompt rule the Downloads copies follow) and returns the URI the
     * share sheet can read. Null when the write failed - the caller still has the Downloads
     * copy. Written beside and renamed, so an app reading the previous share never sees a
     * half-written file.
     */
    fun stage(ctx: Context, fileName: String, body: String): Uri? = try {
        val dir = File(ctx.cacheDir, DIR).apply { if (!exists()) mkdirs() }
        val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(dir, safe)
        val tmp = File(dir, "$safe.tmp")
        tmp.writeText(body)
        if (!tmp.renameTo(out)) {
            out.delete()
            if (!tmp.renameTo(out)) error("rename failed")
        }
        FileProvider.getUriForFile(ctx, authority(ctx), out)
    } catch (e: Exception) {
        null
    }

    /**
     * The share-sheet intent for one prompt file.
     *
     * `text/plain`, not `text/markdown`: a share target declares the MIME types it accepts, and
     * plain text is the one every chat app - the Claude app included - lists. The `.md` name is
     * still what the receiving app shows, because FileProvider reports it as the display name.
     *
     * No `EXTRA_TEXT`. Some targets use the text INSTEAD of the attachment when both are
     * present, and the file is the whole point - the prompt already tells Claude everything.
     * The grant is set on the intent AND carried as ClipData, which is what makes the chooser
     * pass it on to the app that is picked.
     */
    fun chooser(uri: Uri, fileName: String, title: String = "Send to Claude"): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_TITLE, fileName)
            clipData = ClipData.newRawUri(fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

object ShareInbox {

    /** Under `noBackupFilesDir`: a transient hand-off, never worth a cloud backup. */
    private const val DIR = "share-inbox"
    private const val FILE = "shared-answer.txt"

    /** The action `ShareImportActivity` starts `MainActivity` with. */
    const val ACTION_IMPORT = "com.tj.portfolio.action.IMPORT_SHARED"

    private fun file(ctx: Context): File =
        File(File(ctx.noBackupFilesDir, DIR).apply { if (!exists()) mkdirs() }, FILE)

    /** Replaces whatever is waiting. Written beside and renamed, so [take] never reads half. */
    fun put(ctx: Context, text: String): Boolean = try {
        val out = file(ctx)
        val tmp = File(out.parentFile, "$FILE.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(out)) {
            out.delete()
            tmp.renameTo(out)
        } else true
    } catch (e: Exception) {
        false
    }

    /**
     * Reads AND deletes what is waiting, so one share is imported exactly once - a second
     * delivery of the same intent (a process-death restore re-delivers the launching intent)
     * finds nothing and does nothing.
     */
    fun take(ctx: Context): String? = try {
        val f = file(ctx)
        if (!f.exists()) null
        else f.readText().also { f.delete() }
    } catch (e: Exception) {
        null
    }

    /**
     * The shared text, from any of the three shapes a share arrives in:
     *   - ACTION_SEND with EXTRA_STREAM - a file shared from the Claude app or a file manager;
     *   - ACTION_VIEW with a data URI   - "Open with" on the same file;
     *   - ACTION_SEND with EXTRA_TEXT   - a chat message shared as text, which is how an answer
     *     arrives if Claude could not make a file and Tj shares its reply instead.
     * Null when there is nothing readable or it is larger than [maxChars] - which the caller
     * reports rather than importing a truncated answer.
     */
    fun readShared(ctx: Context, intent: Intent, maxChars: Int): String? {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            else -> null
        }
        if (uri != null) {
            // Bytes, capped: a UTF-8 character is at most 4 bytes, so this bound can never
            // reject a file that is within [maxChars].
            val text = Storage.readText(ctx, uri, maxBytes = maxChars * 4) ?: return null
            return text.takeIf { it.length <= maxChars }
        }
        return intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?.takeIf { it.length <= maxChars }
    }
}

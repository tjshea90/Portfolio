package com.tj.portfolio.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Writes user-facing files into the phone's public Downloads folder.
 * API 29+ goes through MediaStore (no storage permission required); older
 * releases fall back to a direct file write.
 */
object Storage {

    /**
     * Everything this app writes goes in ONE folder inside Downloads.
     *
     * TJ asked in v1.7 for Downloads to stay clean; v4.7 then put the uninstall-proof
     * autosave back in its root because a copy that survives the app being removed is worth
     * more than a tidy folder. Both are right - the mistake was treating it as a choice.
     * A subfolder keeps the safety net AND the tidy root: one entry, "Portfolio", instead of
     * an autosave plus a prompt file for every time the button was tapped.
     */
    const val APP_SUBDIR = "Portfolio"

    data class Saved(val name: String, val display: String, val uri: Uri?)

    /** MediaStore's RELATIVE_PATH for our folder, or for the Downloads root when null. */
    private fun relPath(subDir: String?): String =
        if (subDir.isNullOrBlank()) Environment.DIRECTORY_DOWNLOADS
        else Environment.DIRECTORY_DOWNLOADS + "/" + subDir

    private fun displayPath(subDir: String?, fileName: String): String =
        if (subDir.isNullOrBlank()) "Downloads/$fileName" else "Downloads/$subDir/$fileName"

    @Suppress("DEPRECATION")
    private fun legacyDir(subDir: String?): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return if (subDir.isNullOrBlank()) base else File(base, subDir)
    }

    fun saveToDownloads(
        ctx: Context,
        fileName: String,
        content: String,
        mime: String = "text/plain",
        subDir: String? = APP_SUBDIR
    ): Saved? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relPath(subDir))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: return null
            // The row exists the moment insert() returns. If the write then fails we have to
            // delete it ourselves, or a zero-byte IS_PENDING entry is stranded in Downloads
            // forever - invisible to the file manager and impossible for the user to clear.
            try {
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    ?: throw java.io.IOException("no output stream")
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            cv.clear()
            cv.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, cv, null, null)
            Saved(fileName, displayPath(subDir, fileName), uri)
        } else {
            val dir = legacyDir(subDir)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, fileName)
            f.writeText(content)
            Saved(fileName, displayPath(subDir, fileName), Uri.fromFile(f))
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Write to a FIXED name in Downloads, replacing whatever is there.
     *
     * [saveToDownloads] always inserts, so calling it daily would leave a drift of
     * portfolio-autosave (1).json, (2).json ... Automatic saves want exactly one file that
     * is always the newest state, and one file is little enough clutter to be worth the
     * safety: the private snapshot folder is deleted along with the app, so before this
     * there was no automatic copy that survived an uninstall at all.
     */
    fun saveOrReplaceInDownloads(
        ctx: Context,
        fileName: String,
        content: String,
        mime: String = "application/json",
        subDir: String? = APP_SUBDIR
    ): Saved? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val existing = findOwnDownload(ctx, fileName, subDir)
            if (existing != null) {
                // ---- "wt" TRUNCATES FIRST, SO A FAILED WRITE DESTROYS THE OLD COPY.
                //
                // The truncation is necessary - without it a shorter export leaves trailing
                // bytes of the previous one behind and the file no longer parses. But it
                // happens BEFORE any of the new content is written, and this is the writer
                // for `portfolio-autosave.json`: the single copy that survives uninstalling
                // the app or moving phones (the private snapshot folder goes with the app).
                // If the write throws part-way - disk full, MediaStore revoking the URI -
                // what was left on disk was a partial JSON document, the caller got a bare
                // null it could not distinguish from "nothing to do", and nothing re-checked
                // the file. The next recovery read then found a truncated backup.
                //
                // So: keep the old bytes, and put them back if the new write does not land.
                // Worst case the user still has the copy they had before, which is the whole
                // point of the file.
                val prior = runCatching {
                    resolver.openInputStream(existing)?.use { it.readBytes() }
                }.getOrNull()
                val bytes = content.toByteArray()
                try {
                    resolver.openOutputStream(existing, "wt")?.use { it.write(bytes) }
                        ?: return null
                } catch (e: Exception) {
                    // Best-effort rollback. If this fails too there is nothing further to
                    // try here, and `autoBackupIfDue` has already written the identical
                    // content to the private snapshot folder, so the data itself still
                    // exists on the device - it is only the uninstall-proof copy at risk.
                    if (prior != null) runCatching {
                        resolver.openOutputStream(existing, "wt")?.use { it.write(prior) }
                    }
                    return null
                }
                Saved(fileName, displayPath(subDir, fileName), existing)
            } else {
                saveToDownloads(ctx, fileName, content, mime, subDir)
            }
        } else {
            val dir = legacyDir(subDir)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, fileName)
            // Same hazard as the MediaStore branch above, and the same answer: `writeText`
            // truncates before it writes, so a failure part-way leaves a partial file where
            // the only uninstall-proof backup used to be.
            val prior = if (f.exists()) runCatching { f.readBytes() }.getOrNull() else null
            try {
                f.writeText(content)
            } catch (e: Exception) {
                if (prior != null) runCatching { f.writeBytes(prior) }
                return null
            }
            Saved(fileName, displayPath(subDir, fileName), Uri.fromFile(f))
        }
    } catch (e: Exception) {
        null
    }

    /**
     * The MediaStore entry for a Downloads file this app created, if it still exists.
     *
     * RELATIVE_PATH is matched in Kotlin rather than in the SQL, because MediaStore stores it
     * WITH a trailing slash ("Download/Portfolio/") and a query written against the value
     * without one silently matches nothing - which here would mean failing to find the
     * autosave and cheerfully writing a second copy beside it.
     *
     * `MediaStore.Downloads` is API 29+, and minSdk here is 26. Every caller already guards
     * on Build.VERSION.SDK_INT, but the guard lives in the CALLER, so nothing could verify
     * it - and a full lint run flagged this as an error that had been sitting here unnoticed
     * since v4.7, because release builds only run FATAL-severity checks and NewApi is merely
     * an error. The annotation states the contract so the compiler's own tooling enforces it
     * on every future caller.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findOwnDownload(ctx: Context, fileName: String, subDir: String?): Uri? = try {
        val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val want = relPath(subDir).trimEnd('/') + "/"
        ctx.contentResolver.query(
            uri,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.RELATIVE_PATH),
            "${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf(fileName),
            null
        )?.use { c ->
            var found: Uri? = null
            val pathCol = c.getColumnIndex(MediaStore.Downloads.RELATIVE_PATH)
            while (c.moveToNext()) {
                val rel = if (pathCol >= 0) c.getString(pathCol).orEmpty() else ""
                val norm = rel.trimEnd('/') + "/"
                if (norm.equals(want, true)) {
                    found = android.content.ContentUris.withAppendedId(uri, c.getLong(0))
                    break
                }
            }
            found
        }
    } catch (e: Exception) { null }

    /**
     * Read back a Downloads file this app wrote.
     *
     * Looks in the app's folder first and falls back to the Downloads ROOT, because builds
     * before v5.6 wrote the autosave there. The fallback is what makes the move safe for
     * anyone updating with a root copy they have not migrated yet: the recovery card must
     * never come up empty just because the file moved.
     */
    fun readOwnDownload(ctx: Context, fileName: String, subDir: String? = APP_SUBDIR): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            findOwnDownload(ctx, fileName, subDir)?.let { readText(ctx, it) }
                ?: findOwnDownload(ctx, fileName, null)?.let { readText(ctx, it) }
        } else {
            (File(legacyDir(subDir), fileName).takeIf { it.isFile }
                ?: File(legacyDir(null), fileName).takeIf { it.isFile })?.readText()
        }
    } catch (e: Exception) { null }

    /** Is there a copy of this file in the app's own folder? Used to gate the cleanup. */
    fun ownDownloadExists(ctx: Context, fileName: String, subDir: String? = APP_SUBDIR): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            findOwnDownload(ctx, fileName, subDir) != null
        else File(legacyDir(subDir), fileName).isFile

    /** Read a document the user picked with the system file picker. */
    fun readText(ctx: Context, uri: Uri, maxBytes: Int = 8_000_000): String? = try {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > maxBytes) null else String(bytes)
        }
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------- private app folder

    /**
     * Automatic snapshots live here, NOT in Downloads: internal app storage, invisible to
     * the file manager and the gallery. Only the Backup button in Settings writes a file
     * the user can see. Note this folder is removed if the app is uninstalled, which is
     * why the manual export to Downloads is still the copy worth keeping.
     */
    fun appBackupDir(ctx: Context): File =
        File(ctx.filesDir, "backups").apply { if (!exists()) mkdirs() }

    /**
     * ---- WRITE BESIDE IT, THEN SWAP. The same hazard as the Downloads writer above.
     *
     * `writeText` truncates before it writes, and this is the writer for the rolling private
     * snapshots. A write that threw part-way (disk full, IO error) left a TRUNCATED file
     * carrying the NEWEST modification time - and `latestSnapshotJson` picks the newest by
     * mtime and hands it to Settings' "restore latest snapshot" button. So the failure did not
     * just lose one snapshot: it promoted the broken one to the head of the queue, where it
     * also counted against the `keep` window and pushed a good snapshot out on the next save.
     *
     * A temp file plus `renameTo` is atomic within a filesystem, so a reader sees either the
     * whole old snapshot or the whole new one and never a half-written document. If the rename
     * cannot be done, the previous snapshot is left exactly as it was - which is the entire
     * point of keeping it.
     */
    fun saveToAppFolder(ctx: Context, fileName: String, content: String): File? = try {
        val dir = appBackupDir(ctx)
        val f = File(dir, fileName)
        val tmp = File(dir, "$fileName.tmp")
        tmp.writeText(content)
        val landed = if (tmp.renameTo(f)) {
            true
        } else {
            // Some filesystems refuse a rename onto an existing file. Deleting first opens a
            // window where neither copy exists, so it is the fallback, not the first move.
            f.delete(); tmp.renameTo(f)
        }
        if (!landed) {
            tmp.delete()
            null
        } else {
            prune(ctx)
            f
        }
    } catch (e: Exception) {
        runCatching { File(appBackupDir(ctx), "$fileName.tmp").delete() }
        null
    }

    /** Newest automatic snapshots first. */
    fun appBackups(ctx: Context): List<File> =
        appBackupDir(ctx).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Keep a rolling window so the folder cannot grow without bound. */
    private fun prune(ctx: Context, keep: Int = 14) {
        appBackups(ctx).drop(keep).forEach { runCatching { it.delete() } }
    }

    /**
     * Removes automatic snapshots this app previously wrote into Downloads. An app can
     * delete its own MediaStore entries without any permission prompt; files it did not
     * create are silently skipped.
     */
    /**
     * @param subDir which folder to delete from - the Downloads ROOT when null. This
     *        parameter is the whole safety of the v5.6 tidy-up: the new prompt file is
     *        called `claude-advice-prompt.md` and the old ones `claude-advice-prompt-<stamp>
     *        .md`, so a name-prefix sweep that ignored the folder would delete the file the
     *        app had just written alongside the ones it was asked to clear.
     */
    fun deleteOwnDownloads(ctx: Context, namePrefix: String, subDir: String? = null): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return legacyDir(subDir).listFiles()?.filter { it.name.startsWith(namePrefix) }
                ?.count { runCatching { it.delete() }.getOrDefault(false) } ?: 0
        }
        var n = 0
        try {
            val resolver = ctx.contentResolver
            val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val want = relPath(subDir).trimEnd('/') + "/"
            resolver.query(
                uri,
                arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.RELATIVE_PATH
                ),
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("$namePrefix%"),
                null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val pathCol = c.getColumnIndex(MediaStore.Downloads.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val rel = if (pathCol >= 0) c.getString(pathCol).orEmpty() else ""
                    if (!(rel.trimEnd('/') + "/").equals(want, true)) continue
                    val item = android.content.ContentUris.withAppendedId(uri, c.getLong(idCol))
                    if (runCatching { resolver.delete(item, null, null) }.getOrDefault(0) > 0) n++
                }
            }
        } catch (e: Exception) { /* nothing we can do; leave the files alone */ }
        return n
    }

    /** Exactly one file in the Downloads root, by name. Used by the one-time tidy-up. */
    fun deleteOwnDownloadFile(ctx: Context, fileName: String, subDir: String? = null): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            findOwnDownload(ctx, fileName, subDir)?.let {
                runCatching { ctx.contentResolver.delete(it, null, null) }.getOrDefault(0) > 0
            } ?: false
        } else {
            File(legacyDir(subDir), fileName).takeIf { it.isFile }
                ?.let { runCatching { it.delete() }.getOrDefault(false) } ?: false
        }

    /** portfolio-backup-2026-09-03-1432.json */
    fun stamp(): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US)
        return f.format(java.util.Date())
    }
}

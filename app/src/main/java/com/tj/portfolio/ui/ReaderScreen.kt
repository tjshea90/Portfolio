package com.tj.portfolio.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tj.portfolio.net.AdBlock

/** What the reader was asked to open. */
data class ReaderTarget(val url: String, val title: String = "")

/**
 * The app's own lightweight browser.
 *
 * Tapping a headline used to fire an ACTION_VIEW intent, which threw the user out of the
 * app into Chrome; coming back meant a task switch, and the portfolio had to cold-start its
 * refresh loop again. Articles open here instead, inside the app's own back stack.
 *
 * Built on the framework WebView on purpose - no new dependency, which matters because the
 * library versions in this project are pinned to the last releases that compile against
 * compileSdk 36 (see CHECKPOINT.md) and adding one is a real risk to the build.
 *
 * SECURITY POSTURE. This renders arbitrary pages from the open internet, so:
 *   - no file:// or content:// access, so a page can never read the app's own storage
 *   - no JavaScript bridge of any kind is registered, so a page has nothing to call
 *   - autoplaying media is blocked
 *   - non-web schemes (mailto:, tel:, intent:, market:) are handed to the system rather
 *     than being loaded, which is also what stops a page redirecting into another app
 *     without the user choosing to
 * JavaScript itself IS enabled: most news sites render nothing without it, and Google News
 * links are a JS redirect to the publisher, so disabling it would break the common case.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderScreen(
    target: ReaderTarget,
    onBack: () -> Unit,
    /** Drop ad, tracker and pop-up hosts. See [AdBlock]. */
    blockAds: Boolean = true,
    /** Strip overlays and registration panels, and restore the page's scroll. */
    declutter: Boolean = true,
    /** Go straight to the text-only article when the page has one. */
    autoReader: Boolean = false
) {
    val ctx = LocalContext.current
    // Updated on every WebView progress callback - dozens per page load - so this one
    // genuinely avoids boxing an Int each time.
    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf(target.title) }
    var currentUrl by remember { mutableStateOf(target.url) }
    var menu by remember { mutableStateOf(false) }
    var readerMode by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    // a transient one-line message under the toolbar (reader view declined, and so on)
    var notice by remember { mutableStateOf<String?>(null) }

    // Held so the toolbar and the back button can drive the page that is already loaded.
    var web by remember { mutableStateOf<WebView?>(null) }

    // Composed inside the screen, so this callback is registered AFTER the app-level one and
    // therefore runs first: back walks the article's own history before it closes the reader.
    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else onBack()
    }

    Column(Modifier.fillMaxSize()) {

        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BigIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", iconSize = 24) {
                val w = web
                if (w != null && w.canGoBack()) w.goBack() else onBack()
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    pageTitle.ifBlank { "Loading..." },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    hostOf(currentUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BigIconButton(Icons.Filled.Refresh, "Reload", iconSize = 22) {
                readerMode = false
                web?.reload()
            }
            androidx.compose.foundation.layout.Box {
                BigIconButton(Icons.Filled.MoreVert, "More", size = 46, iconSize = 22) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (readerMode) "Show the full page" else "Reader view") },
                        onClick = {
                            menu = false
                            if (readerMode) {
                                readerMode = false
                                web?.reload()
                            } else {
                                // The script returns 'ok' or 'none'. Not every page HAS an
                                // article - a quote page, a video, a paywall stub - and
                                // without this the menu item simply appeared to do nothing,
                                // which reads as a broken button rather than a page that
                                // cannot be simplified.
                                web?.evaluateJavascript(READER_JS) { raw ->
                                    val ok = raw?.contains("ok") == true
                                    readerMode = ok
                                    if (!ok) notice = "There's no article on this page to simplify."
                                }
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Clear pop-ups") },
                        onClick = {
                            menu = false
                            // The same pass that runs automatically on load, on demand - for
                            // the walls a site throws up AFTER you start reading, which is
                            // most of them.
                            web?.evaluateJavascript(DECLUTTER_JS) { raw ->
                                val n = raw?.substringAfter("ok:", "")?.trim('"', ' ')
                                    ?.toIntOrNull() ?: 0
                                notice = if (n > 0) "Cleared $n overlay${if (n == 1) "" else "s"}."
                                else "Nothing was covering the page."
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Open in browser") },
                        onClick = {
                            menu = false
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, currentUrl.toUri()))
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        onClick = {
                            menu = false
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, pageTitle)
                                putExtra(Intent.EXTRA_TEXT, currentUrl)
                            }
                            runCatching { ctx.startActivity(Intent.createChooser(send, "Share")) }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Copy link") },
                        onClick = {
                            menu = false
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("link", currentUrl))
                        }
                    )
                }
            }
        }

        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp)
            )
        } else {
            Spacer(Modifier.height(2.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        failed?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodyMedium,
                // Text takes `redText`, never the fill `Red` - Round 66 audit, REG-5.
                color = redText,
                modifier = Modifier.padding(16.dp)
            )
        }
        notice?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LaunchedEffect(msg) { kotlinx.coroutines.delay(3500); notice = null }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        setSupportZoom(true)
                        mediaPlaybackRequiresUserGesture = true
                        // the page must never reach the app's own files
                        allowFileAccess = false
                        allowContentAccess = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val u = request.url
                            val scheme = u.scheme?.lowercase()
                            // http(s) stays in here; anything else is another app's business
                            // and is only ever opened because the user tapped it.
                            if (scheme == "http" || scheme == "https") return false
                            runCatching {
                                view.context.startActivity(Intent(Intent.ACTION_VIEW, u))
                            }
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            currentUrl = url
                            failed = null
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            currentUrl = url
                            view.title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }

                            // Clear whatever is covering the article and give the page back
                            // its scroll. Runs BEFORE the reader check below, because a
                            // registration wall often hides the article from the extractor
                            // too - un-clamping the text is what lets reader view find it.
                            if (declutter) view.evaluateJavascript(DECLUTTER_JS, null)

                            // A reader-view request made before the page finished would be
                            // wiped by the load; re-apply it once the document is there.
                            if (readerMode) {
                                view.evaluateJavascript(READER_JS) { raw ->
                                    if (raw?.contains("ok") != true) readerMode = false
                                }
                            } else if (autoReader) {
                                // Silent on failure: this was not asked for on this page in
                                // particular, so a page with no article just stays as it is
                                // rather than announcing that it could not be simplified.
                                view.evaluateJavascript(READER_JS) { raw ->
                                    if (raw?.contains("ok") == true) readerMode = true
                                }
                            }
                        }

                        /**
                         * AD AND TRACKER BLOCKING, at the network layer.
                         *
                         * TJ asked for ads and pop-ups to be blocked. This is the place that
                         * actually works: an ad that never loads cannot render an
                         * interstitial, cannot call window.open, and cannot run a script that
                         * puts itself back. Doing it in CSS or by deleting nodes afterwards
                         * only hides things that have already cost the page its load time.
                         *
                         * Returning an EMPTY 200 rather than null is deliberate - null means
                         * "load it normally". An empty body also fails more gracefully than an
                         * error would: a script tag that loads nothing is inert, whereas some
                         * pages have onerror handlers that retry from a second host.
                         *
                         * Runs on a WebView IO thread for every subresource, so [AdBlock] does
                         * host-suffix lookups and nothing more expensive.
                         */
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): android.webkit.WebResourceResponse? {
                            if (!blockAds) return null
                            // Never block the article itself, whatever host it is on.
                            if (request.isForMainFrame) return null
                            return if (AdBlock.blocks(request.url?.toString())) {
                                android.webkit.WebResourceResponse(
                                    "text/plain", "utf-8", java.io.ByteArrayInputStream(
                                        ByteArray(0)
                                    )
                                )
                            } else null
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: android.webkit.WebResourceError
                        ) {
                            // Only the main document matters - a blocked tracker or a failed
                            // ad image must not put an error banner over a readable article.
                            if (request.isForMainFrame) {
                                failed = "Couldn't load this page. Try \"Open in browser\" " +
                                    "from the menu."
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }

                        override fun onReceivedTitle(view: WebView, title: String) {
                            if (title.isNotBlank()) pageTitle = title
                        }
                    }
                    loadUrl(target.url)
                    web = this
                }
            }
        )
    }

    // `factory` runs ONCE, so if a new target ever arrives while the reader is already open
    // the WebView would keep showing the previous article. Nothing reaches that today - the
    // reader covers the lists a headline is tapped in, so there is no way to ask for a second
    // one without closing this first - which is exactly why it is worth pinning down now
    // rather than relying on a layout detail somewhere else staying true.
    var loadedUrl by remember { mutableStateOf(target.url) }
    LaunchedEffect(target.url) {
        if (target.url != loadedUrl) {
            loadedUrl = target.url
            pageTitle = target.title
            web?.loadUrl(target.url)
        }
    }

    /**
     * PAUSE THE WEBVIEW WHEN THE APP IS NOT ON SCREEN.
     *
     * This was the single largest background cost in the app, and nothing was handling it.
     * Composition is not lifecycle: leaving the app with an article open does not dispose
     * this composable, so the WebView kept running - JavaScript timers, CSS animations,
     * pending network, its whole renderer process resident - for as long as Android left the
     * process alive. A news site with an auto-refreshing ticker or a video player would sit
     * there burning CPU and radio with the screen off. The ViewModel's polling loop was made
     * lifecycle-aware back in v3.3 (see CHECKPOINT.md); the WebView never was.
     *
     * `onPause()` suspends this WebView's own drawing and JS. `pauseTimers()` is the one that
     * actually matters - it is PROCESS-WIDE and stops the JavaScript timers and layout timers
     * of every WebView the app owns, which is what stops a background page doing work. Both
     * are reversed on ON_START.
     *
     * ON_PAUSE AS WELL AS ON_STOP, since Round 57, and the two do different things.
     *
     * The original comment here said ON_STOP only, "so a permission dialog or the notification
     * shade does not stop the page the user is reading". That reasoning holds for the page's
     * JavaScript TIMERS - `pauseTimers()` is process-wide and stopping it for a shade pull is
     * heavy-handed. It does not hold for `onPause()`, which is Android's own signal that the
     * WebView is not the thing the user is interacting with: it stops animations, drawing and
     * media playback, and it is exactly what a paused-but-visible pane should be doing.
     *
     * So ON_PAUSE stops the expensive part and ON_STOP adds the timers on top. The case that
     * matters is a page with autoplaying video: before this, that video kept decoding through
     * every dialog, every notification shade, and in any unfocused split-screen pane.
     */
    val readerLifecycle = LocalLifecycleOwner.current
    DisposableEffect(readerLifecycle) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> web?.let {
                    runCatching { it.onPause() }
                }
                Lifecycle.Event.ON_STOP -> web?.let {
                    runCatching { it.onPause(); it.pauseTimers() }
                }
                Lifecycle.Event.ON_RESUME -> web?.let {
                    runCatching { it.resumeTimers() }
                    runCatching { it.onResume() }
                }
                // ON_START RESUMES THE VIEW, NOT JUST THE TIMERS. STARTED-not-RESUMED is a
                // stable state - an unfocused split-screen pane, or sitting behind a
                // non-fullscreen activity - and leaving `onPause()` in force there means a
                // fully visible article that never draws and never runs a line of script.
                // The two calls are guarded separately so a throw from one cannot strand the
                // other.
                Lifecycle.Event.ON_START -> web?.let {
                    runCatching { it.resumeTimers() }
                    runCatching { it.onResume() }
                }
                else -> Unit
            }
        }
        readerLifecycle.lifecycle.addObserver(obs)
        onDispose { readerLifecycle.lifecycle.removeObserver(obs) }
    }

    // A WebView outlives composition unless it is torn down by hand - it keeps its renderer
    // process, its timers and any playing media. Without this, opening a dozen articles in a
    // session leaves a dozen of them running.
    DisposableEffect(Unit) {
        onDispose {
            web?.let { w ->
                runCatching {
                    // Timers are process-wide, so they must be released here too - leaving
                    // the reader while paused would otherwise leave every future WebView in
                    // the process with its timers stopped.
                    w.resumeTimers()
                    w.stopLoading()
                    w.loadUrl("about:blank")
                    w.clearHistory()
                    (w.parent as? android.view.ViewGroup)?.removeView(w)
                    w.destroy()
                }
            }
            web = null
        }
    }
}

private fun hostOf(url: String): String =
    runCatching { url.toUri().host.orEmpty().removePrefix("www.") }.getOrDefault("")

/**
 * Reader view: find the block of the page that actually holds the article and throw the
 * rest away, then re-typeset it.
 *
 * The scoring is the well-worn heuristic - walk every container, add up the text length of
 * the paragraphs directly inside it, and take the winner - kept deliberately small. It is a
 * best-effort convenience, not a parser: "Show the full page" in the menu reloads the
 * original, and the button says so.
 */
/**
 * Clear whatever is covering the article, and give the page back its scroll.
 *
 * TJ asked for pop-ups to be blocked and for articles to be readable on sites that throw up a
 * "sign up to continue reading" panel. [AdBlock] stops most of those before they load; this
 * handles the ones the publisher serves from its own domain, which no host blocklist can
 * touch without breaking the site itself.
 *
 * WHAT THIS DOES, AND WHAT IT DELIBERATELY DOES NOT DO. It removes overlays from content the
 * server ALREADY SENT to this browser, and undoes the `overflow:hidden` that a modal sets on
 * the document to stop you scrolling past it. That is exactly what a reader view is for and
 * what every browser's own reader does. It does **not** try to defeat a real paywall: where
 * the publisher only sent a teaser, the rest of the article is not on the device and no
 * amount of client-side work can conjure it. It does not spoof a crawler, route through a
 * bypass service, or touch anything to do with authentication. So: soft registration walls
 * generally become readable, hard paywalls do not, and that limit is honest rather than a bug.
 *
 * THE SIZE TEST IS WHAT KEEPS THIS SAFE. Rather than a list of class names to delete - which
 * ages badly and differs on every site - an element is only removed when it is FIXED OR
 * STICKY POSITIONED **and** covers a large share of the viewport, or is a high z-index
 * full-screen layer. A sticky nav bar or a floating share rail is small and survives; a modal
 * that owns the screen does not. Elements that contain the article text are never touched.
 */
private const val DECLUTTER_JS = """
(function(){
  try{
    var vw=Math.max(document.documentElement.clientWidth||0,window.innerWidth||0);
    var vh=Math.max(document.documentElement.clientHeight||0,window.innerHeight||0);
    if(!vw||!vh) return 'skip';
    var area=vw*vh, removed=0;

    // 1. Give the document its scroll back. A modal almost always locks the page by setting
    //    overflow:hidden (and sometimes position:fixed) on <body> or <html>. Leaving that in
    //    place is why a page can look fine and still refuse to scroll after the overlay goes.
    [document.documentElement, document.body].forEach(function(el){
      if(!el) return;
      el.style.setProperty('overflow','visible','important');
      el.style.setProperty('position','static','important');
      el.style.setProperty('height','auto','important');
      el.style.setProperty('max-height','none','important');
    });

    // 2. Remove big fixed/sticky layers. Walk a bounded number of elements - a news page can
    //    have thousands of nodes and this runs on the main thread of the renderer.
    var all=document.body?document.body.querySelectorAll('*'):[];
    var limit=Math.min(all.length,4000);
    for(var i=0;i<limit;i++){
      var el=all[i];
      if(!el||!el.getBoundingClientRect) continue;
      var cs;
      try{ cs=window.getComputedStyle(el); }catch(e){ continue; }
      if(!cs) continue;
      var pos=cs.position;
      if(pos!=='fixed'&&pos!=='sticky') continue;
      var r=el.getBoundingClientRect();
      var covers=(r.width*r.height)/area;
      // A blocking layer owns most of the screen. A cookie bar or sticky header does not.
      var isBlocker = covers>0.5 || (covers>0.25 && (parseInt(cs.zIndex,10)||0)>=1000);
      if(!isBlocker) continue;
      // Never delete the thing holding the story.
      var words=((el.innerText||'').trim().match(/\s+/g)||[]).length;
      if(words>150) continue;
      el.remove(); removed++;
    }

    // 3. Kill the scroll-blocking backdrops that are left behind as plain elements, and
    //    unhide text a wall had faded out. Restricted to properties, not node removal, so a
    //    mis-identified element loses its blur rather than its content.
    var walls=document.querySelectorAll(
      '[class*="paywall" i],[class*="modal" i],[class*="overlay" i],[class*="backdrop" i],'+
      '[class*="interstitial" i],[class*="popup" i],[class*="subscribe" i],[class*="regwall" i],'+
      '[id*="paywall" i],[id*="modal" i],[id*="overlay" i]');
    for(var j=0;j<walls.length;j++){
      var w=walls[j];
      var wr=w.getBoundingClientRect();
      var wWords=((w.innerText||'').trim().match(/\s+/g)||[]).length;
      if(wWords>150) continue;                       // it is the article, leave it alone
      if((wr.width*wr.height)/area>0.25){ w.remove(); removed++; }
    }

    // 4. Undo the fade/blur/clip a registration wall applies to the text underneath it. This
    //    is the part that makes "sign up to continue" pages readable when the words were
    //    actually delivered - it changes presentation only and invents nothing.
    var faded=document.querySelectorAll('p,article,section,div');
    var fl=Math.min(faded.length,3000);
    for(var k=0;k<fl;k++){
      var f=faded[k], fs;
      try{ fs=window.getComputedStyle(f); }catch(e){ continue; }
      if(!fs) continue;
      if(fs.filter&&fs.filter!=='none') f.style.setProperty('filter','none','important');
      if(fs.webkitLineClamp&&fs.webkitLineClamp!=='none')
        f.style.setProperty('-webkit-line-clamp','none','important');
      if(parseFloat(fs.opacity)<0.95&&(f.innerText||'').trim().length>80)
        f.style.setProperty('opacity','1','important');
      if(fs.maxHeight&&fs.maxHeight!=='none'&&fs.overflow==='hidden'&&
         (f.innerText||'').trim().length>200){
        f.style.setProperty('max-height','none','important');
        f.style.setProperty('overflow','visible','important');
      }
    }

    // 5. Stop the page opening windows from here on. Pop-ups are already blocked at the
    //    WebView (setSupportMultipleWindows(false)); this also stops the scripts that
    //    re-open on every tap from doing anything at all.
    try{ window.open=function(){ return null; }; }catch(e){}

    return 'ok:'+removed;
  }catch(e){ return 'err'; }
})();
"""

private const val READER_JS = """
(function(){
  try{
    // Score CONTAINERS by the paragraphs inside them, crediting a paragraph to both its
    // parent and (at half weight) its grandparent.
    //
    // The first version of this only counted ':scope > p' - paragraphs that are DIRECT
    // children of the container. Tested against a page that wraps each paragraph in its
    // own div, which is what a great many content-management systems emit, every container
    // scored zero and reader view silently did nothing. Crediting the grandparent as well
    // handles both shapes.
    function nameOf(el){
      var c=el.className;
      if(c&&typeof c!=='string'&&c.baseVal!==undefined) c=c.baseVal;
      return ((c||'')+' '+(el.id||'')).toLowerCase();
    }
    var JUNK=/sidebar|promo|advert|comment|share|related|recirc|newsletter|subscribe|footer|nav|menu|widget|trending|popular|outbrain|taboola/;
    var GOOD=/article|content|story|post|entry|body|main|prose/;

    var scores=new Map();
    function credit(el,n){
      if(!el||el===document.body||el===document.documentElement) return;
      scores.set(el,(scores.get(el)||0)+n);
    }
    var ps=document.getElementsByTagName('p');
    for(var i=0;i<ps.length;i++){
      var len=(ps[i].innerText||'').trim().length;
      if(len<40) continue;
      // Text sitting in the page furniture is not the article, however much of it there is.
      if(ps[i].closest('aside,nav,footer,header')) continue;
      // Walk up a few levels with a decaying weight rather than stopping at the parent.
      // Some layouts put every paragraph in its own <section><div><div>, so no single
      // near ancestor ever accumulates more than one paragraph and the only element that
      // represents "the article" is several levels up. The decay keeps a tight container
      // ahead of a loose one whenever both actually hold the text.
      var W=[1,0.5,0.3,0.2], node=ps[i].parentElement;
      for(var w=0;w<W.length&&node;w++){
        credit(node,len*W[w]);
        node=node.parentElement;
      }
    }
    if(scores.size===0) return 'none';
    var cands=[];
    scores.forEach(function(score,el){
      if(el.tagName==='ARTICLE'||el.tagName==='MAIN') score*=1.5;
      // Weight by what the page calls the container. Tested against a page whose promo
      // rail carried three times the text of the story itself: on length alone the rail
      // won outright and the reader showed the promo instead of the article.
      var n=nameOf(el);
      if(JUNK.test(n)) score*=0.2;
      else if(GOOD.test(n)) score*=1.3;
      cands.push({el:el,score:score});
    });
    cands.sort(function(a,b){return b.score-a.score;});
    var top=cands[0].score;
    // Only a cheap early-out here. Whether there is enough of an article to be worth
    // showing is decided further down, on the text actually extracted - judging it on the
    // weighted score instead rejected perfectly good pages whose paragraphs are wrapped a
    // level deep, because those only ever earn the half-weight grandparent credit.
    if(top<100) return 'none';
    // Among containers that score comparably, take the DEEPEST one. A parent inherits its
    // child's paragraphs, so without this the winner drifts upward until it swallows the
    // sidebar and the nav along with the article.
    var best=null,bestDepth=-1;
    for(var c=0;c<cands.length;c++){
      if(cands[c].score < top*0.8) break;
      var d=0,node=cands[c].el;
      while(node.parentElement){d++;node=node.parentElement;}
      if(d>bestDepth){bestDepth=d;best=cands[c].el;}
    }
    if(!best) return 'none';
    // ESCAPE BEFORE RE-INSERTING. innerText hands back TEXT - a literal '<' is a character,
    // not markup - and the rebuilt article goes back through innerHTML, which re-parses it.
    // Unescaped, the browser reads '<' as the start of a tag and silently eats the rest of
    // the paragraph: "Analysts see <20% upside from here" rendered as "Analysts see ", and
    // "the P/E is < 15 and falling" as "the P/E is ". Finance copy is full of those, so this
    // was quietly deleting the substance of the sentences it was supposed to be showing.
    function esc(s){
      return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    }
    var h=document.querySelector('h1'), heading=h?h.innerText:(document.title||'');
    var parts=[];
    var kids=best.querySelectorAll('p,h2,h3,li,blockquote');
    for(var k=0;k<kids.length;k++){
      var t=(kids[k].innerText||'').trim();
      if(!t) continue;
      var tag=kids[k].tagName, e=esc(t);
      if(tag==='H2'||tag==='H3') parts.push('<h2>'+e+'</h2>');
      else if(tag==='LI') parts.push('<li>'+e+'</li>');
      else if(tag==='BLOCKQUOTE') parts.push('<blockquote>'+e+'</blockquote>');
      else if(t.length>40) parts.push('<p>'+e+'</p>');
    }
    // The real "is this an article?" test, measured on what would actually be shown.
    // Note this counts the ESCAPED text, so '&lt;' is four characters where the reader sees
    // one - close enough for a 400-character threshold, and erring toward accepting a page.
    var plain=parts.join(' ').replace(/<[^>]*>/g,'');
    if(parts.length<2||plain.length<400) return 'none';
    var css='body{margin:0;padding:20px 18px 60px;font:17px/1.65 -apple-system,Roboto,'+
      'sans-serif;background:#ffffff;color:#16181d;}'+
      '@media (prefers-color-scheme: dark){body{background:#0f1115;color:#e6e8ec;}'+
      'a{color:#7aa2ff;} blockquote{border-color:#3a3f4b;color:#aab;}}'+
      'h1{font-size:26px;line-height:1.25;margin:0 0 14px;}'+
      'h2{font-size:19px;margin:26px 0 8px;}p{margin:0 0 16px;}'+
      'img{max-width:100%;height:auto;}'+
      'blockquote{margin:0 0 16px;padding-left:14px;border-left:3px solid #ccd;color:#556;}'+
      'li{margin:0 0 8px;}';
    document.head.innerHTML='<meta name="viewport" content="width=device-width,'+
      'initial-scale=1"><style>'+css+'</style>';
    document.body.innerHTML='<h1>'+esc(heading)+'</h1>'+parts.join('');
    return 'ok';
  }catch(e){ return 'err'; }
})();
"""

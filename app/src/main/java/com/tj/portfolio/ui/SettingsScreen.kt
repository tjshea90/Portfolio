package com.tj.portfolio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tj.portfolio.data.Db
import com.tj.portfolio.data.Keys
import com.tj.portfolio.domain.Ledger
import com.tj.portfolio.util.CrashLog
import com.tj.portfolio.util.Fmt
import com.tj.portfolio.util.Storage

@Composable
fun SettingsScreen(vm: PortfolioViewModel) {
    val ctx = LocalContext.current
    val models by vm.models.collectAsState()
    val ui by vm.ui.collectAsState()

    var claudeKey by remember { mutableStateOf(vm.claudeKey()) }
    var finnhubKey by remember { mutableStateOf(vm.finnhubKey()) }
    var model by remember { mutableStateOf(vm.model()) }
    var modelMenu by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(vm.refreshSecs().toString()) }
    var webSearch by remember { mutableStateOf(vm.webSearch()) }
    var useCash by remember { mutableStateOf(vm.useCashOverride()) }
    var cash by remember { mutableStateOf(if (vm.cashOverrideValue() == 0.0) "" else vm.cashOverrideValue().toString()) }
    var showKey by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<String?>(null) }
    /** The backup text waiting on the second confirmation for a destructive "Replace all". */
    var confirmReplace by remember { mutableStateOf<String?>(null) }
    var lastBackup by remember { mutableStateOf(vm.lastBackup()) }
    var autoBackup by remember { mutableStateOf(vm.autoBackupOn()) }
    var costMethod by remember { mutableStateOf(vm.costMethod()) }
    var inAppReader by remember { mutableStateOf(vm.inAppReader()) }
    var blockAds by remember { mutableStateOf(vm.blockAds()) }
    var declutter by remember { mutableStateOf(vm.declutter()) }
    var autoReader by remember { mutableStateOf(vm.autoReader()) }
    var busy by remember { mutableStateOf(false) }
    // These read the database, list files, or hash the signing certificate. Called bare in
    // composition they ran on every keystroke in the fields above; remember them and bump
    // [infoTick] on the events that can actually change them.
    var infoTick by remember { mutableIntStateOf(0) }
    val modelChosen = remember(model, infoTick) { vm.modelChosen() }
    val snapshotAt = remember(infoTick) { vm.lastAutoBackup() }
    val snapshotCount = remember(infoTick) { vm.snapshotCount() }
    val appVersionText = remember { appVersion(ctx) }
    val signerText = remember { signerShort(ctx) }

    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // a backup can be megabytes; reading it through the content resolver is not
            // something to do on the UI thread just because we are in a picker callback
            vm.readPickedFile(uri) { text ->
                if (text == null) vm.toast("Couldn't read that file") else pendingRestore = text
            }
        }
    }

    // `infoTick++` ON THE PULL (Round 66 audit, EXP-1). The "Requests in the last hour" card
    // tells the reader to pull down to update its figures, and pulling could not: the value
    // lives in a `remember(infoTick, refresh)`, and `vm.refresh(manual = true)` touches
    // neither key. The card recomposed and handed back the same cached list it captured when
    // the tab was opened, so the one question it exists to answer - how hard are we hitting
    // the providers RIGHT NOW - could only be re-asked by leaving the screen and coming back.
    // `Http.requestsLastHour()` is an in-memory scan of sixty buckets with no disk work, so
    // it is safe on this path; the snapshot and crash-log remembers are not, which is why the
    // note further down warns against bumping the tick from the text field.
    Refreshable(
        refreshing = ui.pulling(PULL_PRICES),
        onRefresh = { vm.refresh(manual = true); infoTick++ }
    ) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        SectionHeader("Claude API")
        OutlinedTextField(
            value = claudeKey,
            onValueChange = { claudeKey = it.trim(); vm.setSetting(Keys.CLAUDE_KEY, claudeKey) },
            label = { Text("Anthropic API key (sk-ant-...)") },
            singleLine = true,
            visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
            TextButton(onClick = { vm.loadModels() }) { Text("Load models") }
        }
        Text(
            "Used for screenshot import and the Advice tab. Stored only on this phone: it is " +
                "left out of the app's backup export, and the database it lives in is excluded " +
                "from Android's cloud backup too. A direct phone-to-phone transfer still " +
                "carries everything across.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))
        Box {
            OutlinedButton(onClick = { modelMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (modelChosen) "Model: $model"
                    else "Model: $model (auto - tap Load models to pick)"
                )
            }
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                if (models.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Tap 'Load models' first") },
                        onClick = { modelMenu = false }
                    )
                }
                models.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m) },
                        onClick = { model = m; vm.setSetting(Keys.CLAUDE_MODEL, m); modelMenu = false }
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Let Claude web-search during advice")
                Text(
                    "More current, costs more tokens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = webSearch, onCheckedChange = {
                webSearch = it; vm.setSettingB(Keys.WEB_SEARCH, it)
            })
        }

        SectionHeader("Market data")
        OutlinedTextField(
            value = finnhubKey,
            onValueChange = { finnhubKey = it.trim(); vm.setSetting(Keys.FINNHUB_KEY, finnhubKey) },
            label = { Text("Finnhub API key (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Quotes come from Yahoo Finance by default and need no key. A free Finnhub key is " +
                "used as a fallback if Yahoo is unreachable, and delayed Stooq data as a last " +
                "resort so the app is never blank.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = refresh,
            onValueChange = {
                refresh = it.filter { c -> c.isDigit() }
                vm.setSetting(Keys.REFRESH_SECS, refresh.ifBlank { "0" })
                // NO infoTick++ HERE. It invalidates the remembers below, which list the
                // snapshot directory and read the crash-log file - so typing "15" ran two
                // directory listings and two file reads on the UI thread, which is the v2.4
                // "no disk work in composition" bug creeping back in through a side door.
                // The one thing on this screen that a new interval changes is the status
                // line, and that is already keyed on `refresh` itself.
            },
            label = { Text("Auto-refresh seconds during market hours (0 = off)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        // What the app is ACTUALLY doing right now. The interval below is the market-hours
        // one; outside those hours the app slows itself down, and it should say so rather
        // than leaving the user to wonder why prices are not ticking at 3am.
        val refreshStatus = remember(refresh, infoTick) { vm.refreshStatus() }
        Text(
            refreshStatus,
            style = MaterialTheme.typography.bodySmall,
            color = accentText,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            "Only the prices you can actually see are polled at this rate. Opening one " +
                "stock updates that stock; the Activity, Advice and Settings tabs show no " +
                "prices, so the app stops asking for them entirely while you are on those. " +
                "Anything that was paused is brought up to date the moment it comes back " +
                "into view.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Outside market hours the app polls far less often - overnight and at weekends " +
                "prices cannot move, and hammering a free feed for them is how an app gets " +
                "rate-limited. If a source does start throttling, the app backs off on its " +
                "own, honours whatever wait the provider asks for, and keeps showing the " +
                "last known prices.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ---- what the app is REALLY asking of its providers
        //
        // Every other claim on this screen is an argument; this is the measurement. If a
        // feed ever does start refusing, "how hard are we actually hitting them?" should be
        // answerable on the phone rather than estimated from the source.
        val rate = remember(infoTick, refresh) {
            com.tj.portfolio.net.Http.requestsLastHour()
        }
        // ---- the headline cache
        val newsStats = remember(infoTick) { vm.newsCacheStats() }
        SectionHeader("Saved headlines")
        KeyValue("Stories kept", newsStats.first.toString())
        KeyValue(
            "Oldest",
            if (newsStats.second <= 0L) "-" else Fmt.relative(newsStats.second)
        )
        Text(
            "Headlines are saved on this phone, so the Feed opens instantly and a refresh " +
                "only fetches what is new instead of downloading everything again. Anything " +
                "older than a month is deleted automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = { vm.clearNewsCache(); infoTick++ },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) { Text("Clear saved headlines") }

        // ---- the fundamentals cache. Same measurement principle as the request meter
        // above: the analyst history is by far the biggest thing this app stores per
        // symbol, so what it is actually holding should be visible on the phone rather
        // than estimated from the source.
        val fundStats = remember(infoTick) { vm.fundamentalsCacheStats() }
        SectionHeader("Saved company data")
        KeyValue("Records kept", fundStats.first.toString())
        KeyValue(
            "Size on disk",
            if (fundStats.second <= 0L) "-" else Fmt.compact(fundStats.second.toDouble()) + " chars"
        )
        Text(
            "The statistics, analyst ratings and earnings estimates behind each stock's " +
                "page are saved here, so opening a stock you looked at earlier is instant " +
                "and offline. Company numbers are refreshed every few hours and analyst " +
                "ratings twice a day; anything untouched for a month is deleted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ---- the price-chart cache (Round 58)
        val chartStats = remember(infoTick) { vm.chartCacheStats() }
        SectionHeader("Saved price charts")
        KeyValue("Charts kept", chartStats.first.toString())
        KeyValue(
            "Newest",
            if (chartStats.second <= 0L) "-" else Fmt.relative(chartStats.second)
        )
        Text(
            "Every chart you open is kept on the phone, one per stock per time range, so " +
                "coming back to it draws instantly and switching apps never blanks it. " +
                "Each range is refreshed on its own clock - a five-minute chart every five " +
                "minutes, a five-year chart once a day - and pulling down on a stock always " +
                "fetches a fresh one whatever the clock says.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = { vm.clearChartCache(); infoTick++ },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) { Text("Clear saved charts") }

        // ---- the downloaded-page cache (Round 56)
        val httpStats = remember(infoTick) { vm.httpCacheStats() }
        SectionHeader("Saved downloads")
        KeyValue("Pages kept", httpStats.first.toString())
        KeyValue(
            "Size on disk",
            if (httpStats.second <= 0L) "-"
            else Fmt.compact(httpStats.second.toDouble()) + " chars"
        )
        Text(
            "Every feed and data file the app downloads is kept here with the tag the " +
                "server gave it. Next time, the app sends that tag and the server usually " +
                "answers \"nothing has changed\" with no data at all - so a page that has " +
                "not been updated is never downloaded twice, even after the app is closed " +
                "and reopened. Anything untouched for a month is deleted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = { vm.clearHttpCache(); infoTick++ },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) { Text("Clear saved downloads") }

        SectionHeader("Requests in the last hour")
        if (rate.isEmpty()) {
            Text(
                "Nothing yet this session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            rate.forEach { (host, n) ->
                KeyValue(host, n.toString())
            }
            KeyValue("Total", com.tj.portfolio.net.Http.totalLastHour().toString())
            Text(
                "Counted since the app started, per provider, over a rolling hour. Pull " +
                    "down on this screen to update the figures.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        SectionHeader("News")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Open articles inside the app")
                Text(
                    "Headlines open in the app's own reader, so back returns to your list " +
                        "instead of leaving you in a browser. Turn this off to use your " +
                        "normal browser instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = inAppReader,
                onCheckedChange = {
                    inAppReader = it
                    vm.setSettingB(Keys.IN_APP_READER, it)
                }
            )
        }

        // ---- what the reader does to a page once it has it
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Block ads and trackers")
                Text(
                    "Drops requests to ad exchanges, trackers and pop-up vendors before " +
                        "they load, so articles open faster and use less battery. Turn it " +
                        "off if a site will not display properly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = blockAds,
                onCheckedChange = { blockAds = it; vm.setSettingB(Keys.BLOCK_ADS, it) }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Clear pop-ups and overlays")
                Text(
                    "Removes panels covering the article and gives the page its scroll " +
                        "back. This often makes \"sign up to continue reading\" pages " +
                        "readable - but only where the site actually sent the text. A real " +
                        "paywall keeps the rest of the article on its own server, and " +
                        "nothing here can show what was never sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = declutter,
                onCheckedChange = { declutter = it; vm.setSettingB(Keys.DECLUTTER, it) }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Always open the text-only version")
                Text(
                    "Skips straight to the article text, without images, adverts or " +
                        "layout. Pages with no article are left as they are. You can also " +
                        "switch any page over from the reader's menu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = autoReader,
                onCheckedChange = { autoReader = it; vm.setSettingB(Keys.AUTO_READER, it) }
            )
        }
        Text(
            "Headlines come from Yahoo Finance, Nasdaq, Google News, MarketWatch and " +
                "Investing.com, plus Finnhub if a key is set. Opening a stock pulls every " +
                "source at once; the background feed uses the cheapest one that answers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )

        // ---- fees and commissions
        SectionHeader("Fees and commissions")
        val feeAudit = remember(infoTick, ui.txns) { vm.auditFees() }
        StatCard {
            Text("Trading", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            com.tj.portfolio.domain.Fees.TRADING.forEach { FeeLine(it) }
            Spacer(Modifier.height(10.dp))
            Text("Regulatory fees passed through", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            com.tj.portfolio.domain.Fees.REGULATORY.forEach { FeeLine(it) }
            Spacer(Modifier.height(10.dp))
            Text("Account and service fees", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            com.tj.portfolio.domain.Fees.ACCOUNT.forEach { FeeLine(it) }
            Spacer(Modifier.height(10.dp))
            Text(
                "Buying an ordinary stock or ETF is free: Ally charges no commission, and " +
                    "both regulatory fees are levied on SALES only. The exception is a " +
                    "security trading under $2, which carries a commission on both sides.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                com.tj.portfolio.domain.Fees.SOURCE_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = accentText
            )
        }
        Text(
            "New trades you add by hand get these worked out automatically. Imported rows are " +
                "left exactly as Ally settled them - whatever it charged is already inside " +
                "the amount, so adding it again would overstate your cost basis.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(8.dp))
        KeyValue("Fees recorded across all transactions", Fmt.usd(feeAudit.totalRecorded))
        if (feeAudit.hasProblem) {
            Spacer(Modifier.height(6.dp))
            Text(
                "${feeAudit.buysWithFees.size} buy transaction" +
                    (if (feeAudit.buysWithFees.size == 1) " has" else "s have") +
                    " more commission on it than Ally's schedule allows " +
                    "(${Fmt.usd(feeAudit.buyFeeTotal)} in total), which inflates your cost " +
                    "basis. Buys of stocks under $2 are left alone - those really are charged.",
                style = MaterialTheme.typography.bodyMedium,
                // Text takes `redText`, never the fill `Red` - Round 66 audit, REG-5.
                color = redText
            )
            Button(
                onClick = {
                    busy = true
                    vm.clearIncorrectBuyFees { n ->
                        busy = false
                        infoTick++
                        vm.toast(
                            if (n > 0) "Corrected the fee on $n buy transaction(s)"
                            else "Nothing to change"
                        )
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text("Correct those buy fees") }
        } else {
            Text(
                "Every buy on file matches Ally's schedule.",
                style = MaterialTheme.typography.bodySmall,
                color = accentText,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // A trade with no share count moves cash but builds no position, so its money lands
        // in the headline total with nothing to attribute it to. Nothing can create one any
        // more - both import paths refuse them and the editor has always required a share
        // count - but a row from an older build or a restored backup would just sit there
        // making the totals quietly wrong. No auto-fix: deleting a user's transaction on the
        // app's own initiative is not this app's job. It names them so they can be corrected.
        if (feeAudit.hasGhostRows) {
            Spacer(Modifier.height(10.dp))
            StatCard {
                Text("Transactions your totals cannot explain", fontWeight = FontWeight.Bold, color = redText)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${feeAudit.quantityless.size} buy/sell row" +
                        (if (feeAudit.quantityless.size == 1) " has" else "s have") +
                        " no share count. A trade with no quantity moves your cash but " +
                        "creates no holding, so " + Fmt.usd(kotlin.math.abs(feeAudit.quantitylessCash)) +
                        " shows up in \"Since you started\" with no position behind it.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                feeAudit.quantityless.take(6).forEach { t ->
                    KeyValue(
                        "${t.type} ${t.symbol ?: ""} - ${Fmt.day(t.date)}",
                        Fmt.usdSigned(t.amount)
                    )
                }
                if (feeAudit.quantityless.size > 6) {
                    Text(
                        "...and ${feeAudit.quantityless.size - 6} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Open each one on the Activity tab and either fill in the share count or " +
                        "delete it. Nothing is changed automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = accentText
                )
            }
        }

        // MORE SOLD THAN EVER BOUGHT. The stock's own page says this too, but only while it
        // still HAS a position - and an uncovered sale usually closes the holding outright,
        // which is precisely the case that would otherwise have nowhere to appear. See
        // PortfolioViewModel.FeeAudit.oversold and domain/Position.oversold.
        if (feeAudit.hasOversold) {
            Spacer(Modifier.height(10.dp))
            StatCard {
                Text("More shares sold than bought", fontWeight = FontWeight.Bold, color = redText)
                Spacer(Modifier.height(4.dp))
                Text(
                    "A sale on file is bigger than the shares this app has a record of, so a " +
                        "buy is probably missing. The money from the sale is counted in full, " +
                        "which means the realized gain on " +
                        (if (feeAudit.oversold.size == 1) "this stock is"
                         else "these stocks are") +
                        " overstated by whatever the missing shares cost.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                feeAudit.oversold.take(6).forEach { (sym, n) ->
                    KeyValue(sym, "${Fmt.shares(n)} uncovered")
                }
                if (feeAudit.oversold.size > 6) {
                    Text(
                        "...and ${feeAudit.oversold.size - 6} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Add the missing buy on the Activity tab and this clears itself. Nothing " +
                        "is changed automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = accentText
                )
            }
        }

        SectionHeader("Cost basis method")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (costMethod == Ledger.FIFO) "FIFO (matches your broker)" else "Average cost")
                Text(
                    if (costMethod == Ledger.FIFO)
                        "Sells the oldest shares first, the way Ally reports it. Use this if you " +
                            "want the app's cost basis and per-stock gain to match your statement."
                    else
                        "Pools every share into one blended price. Simpler, but it drifts from the " +
                            "broker on any stock you sold and later bought back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = costMethod == Ledger.FIFO,
                onCheckedChange = {
                    costMethod = if (it) Ledger.FIFO else Ledger.AVERAGE
                    vm.setCostMethod(costMethod)
                }
            )
        }
        Text(
            "Either way your total lifetime gain is identical - the method only shifts how much " +
                "of it counts as already realized.",
            style = MaterialTheme.typography.bodySmall,
            color = accentText,
            modifier = Modifier.padding(top = 6.dp)
        )

        SectionHeader("Cash")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Override buying power")
                Text(
                    "Use this if your ledger's cash doesn't match Ally exactly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = useCash, onCheckedChange = {
                useCash = it; vm.setSettingB(Keys.USE_CASH_OVERRIDE, it)
            })
        }
        if (useCash) {
            OutlinedTextField(
                value = cash,
                onValueChange = {
                    cash = it
                    vm.setSetting(Keys.CASH_OVERRIDE, it.trim().replace(",", "").replace("$", ""))
                },
                label = { Text("Cash / buying power") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
        }

        SectionHeader("Backup and restore")
        Text(
            "A backup holds everything you have entered: every transaction, manual override, " +
                "watchlist symbol, import record and preference - all read live at the moment " +
                "you press the button. Only your API keys are left out. Restore it on any " +
                "device to pick up exactly where you left off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            "The only thing that writes a file you can see. The file is read back and " +
                "checked against your data before it reports success.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Button(
            onClick = {
                busy = true
                vm.backupToDownloads { out ->
                    busy = false
                    lastBackup = vm.lastBackup()
                    infoTick++
                    vm.toast(out.message)
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Backing up and verifying..." else "Back up to Downloads") }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { restorePicker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Restore from a backup file") }

        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedButton(onClick = {
                // Reads every table and serialises the lot. That is not main-thread work
                // just because it started in a click handler - the clipboard write itself
                // has to be on the main thread, so only the export moves off it.
                vm.exportJsonAsync { json ->
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("portfolio backup", json))
                    vm.toast("Backup JSON copied to clipboard")
                }
            }, modifier = Modifier.weight(1f)) { Text("Copy JSON") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { showImport = true },
                modifier = Modifier.weight(1f)
            ) { Text("Paste JSON") }
        }

        if (lastBackup.isNotBlank()) {
            Text(
                "Last backup: $lastBackup",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        SectionHeader("Data safety")
        StatCard {
            Text("Your data survives app updates", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Installing a newer APK over this one is an in-place update: same package, " +
                    "same signing key, so Android keeps the database untouched. Your " +
                    "transactions, watchlist, overrides and settings all carry over.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            KeyValue("Package", "com.tj.portfolio")
            KeyValue("App version", appVersionText)
            KeyValue("Database version", Db.DB_VERSION.toString())
            KeyValue("Signing key", signerText)
            Spacer(Modifier.height(8.dp))
            Text(
                "The one thing that DOES erase data is uninstalling the app first, or a " +
                    "factory reset. Never uninstall to update - just open the new APK.",
                style = MaterialTheme.typography.bodySmall,
                color = accentText
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Daily automatic snapshot")
                Text(
                    "Kept privately inside the app, plus one always-current copy in " +
                        "Downloads/Portfolio that survives uninstalling the app. Runs " +
                        "once a day and after every import, keeping the last 14.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = autoBackup, onCheckedChange = {
                autoBackup = it
                vm.setAutoBackup(it)
                lastBackup = vm.lastBackup()
                infoTick++
            })
        }
        if (snapshotAt > 0) {
            Text(
                "Last snapshot ${Fmt.relative(snapshotAt)} - $snapshotCount kept privately",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            OutlinedButton(
                onClick = {
                    vm.latestSnapshotAsync { json ->
                        if (json == null) vm.toast("No snapshot available yet")
                        else pendingRestore = json
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text("Restore the latest automatic snapshot") }
        }
        Text(
            "Snapshots are erased if the app is uninstalled, so keep an occasional manual " +
                "backup for a copy that outlives the app.",
            style = MaterialTheme.typography.bodySmall,
            color = accentText,
            modifier = Modifier.padding(top = 6.dp)
        )

        // ---- crash log
        //
        // A sideloaded app has no crash reporting, so "it just closed" used to be the whole
        // report. The stack trace is written to the app's private folder as it happens; this
        // is how it gets read back out and sent on.
        SectionHeader("Crash log")
        val crashSummary = remember(infoTick) { CrashLog.latestSummary(ctx) }
        if (crashSummary == null) {
            Text(
                "No crashes recorded. If the app ever closes by itself, come back here - the " +
                    "reason will be waiting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            StatCard {
                Text("Last crash", fontWeight = FontWeight.Bold, color = redText)
                Spacer(Modifier.height(4.dp))
                Text(crashSummary, style = MaterialTheme.typography.bodySmall)
            }
            Row {
                // Reading a 60KB file and writing it through MediaStore is not main-thread
                // work just because it started in a click handler - the same rule the backup
                // and "Copy JSON" buttons already follow. Only the clipboard write has to be
                // on this thread, so that is all that stays here.
                OutlinedButton(
                    onClick = {
                        vm.saveCrashLog { msg -> vm.toast(msg) }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Save to Downloads") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        vm.readCrashLog { text ->
                            val cm =
                                ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("crash log", text))
                            vm.toast("Crash log copied")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Copy") }
            }
            TextButton(onClick = { CrashLog.clear(ctx); infoTick++; vm.toast("Crash log cleared") }) {
                Text("Clear crash log")
            }
        }

        SectionHeader("Danger zone")
        TextButton(onClick = { confirmWipe = true }) { Text("Delete all transactions", color = redText) }

        Spacer(Modifier.height(30.dp))
        Text(
            "Ally Invest has no public developer API any more, so holdings are entered by " +
                "screenshot import or by hand. Everything stays on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(40.dp))
    }
    }

    if (showImport) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("Restore from JSON") },
            text = {
                Column {
                    Text(
                        "Paste a backup export. Duplicates are skipped.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        label = { Text("JSON") },
                        modifier = Modifier.fillMaxWidth().height(180.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val json = importText
                    showImport = false
                    importText = ""
                    busy = true
                    vm.restoreAsync(json, replace = false) { r ->
                        busy = false
                        infoTick++
                        vm.toast(r.summary())
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("Cancel") } }
        )
    }

    pendingRestore?.let { text ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore this backup?") },
            text = {
                Column {
                    Text(
                        "Merge adds anything missing and skips duplicates - safe to run twice, " +
                            "and it can never remove anything you already have."
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Replace all DELETES every transaction, override, watchlist symbol and " +
                            "import record on this phone first, so you end up with exactly what " +
                            "the file holds. That is what you want when moving to a new device, " +
                            "and nothing else.",
                        // Text takes `redText`, never the fill `Red` - Round 66 audit, REG-5.
                        color = redText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            // Merge is the safe answer, so it gets the primary position. "Replace all" used
            // to sit in the dismiss slot - the left-hand button, where every other dialog in
            // the app puts Cancel - and wiped the entire ledger on one tap with no second
            // step. It now asks again, and there is a real Cancel where Cancel belongs.
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    busy = true
                    vm.restoreAsync(text, replace = false) { r ->
                        busy = false
                        infoTick++
                        vm.toast(r.summary())
                    }
                }) { Text("Merge") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingRestore = null }) { Text("Cancel") }
                    TextButton(onClick = { confirmReplace = text; pendingRestore = null }) {
                        Text("Replace all", color = redText)
                    }
                }
            }
        )
    }

    confirmReplace?.let { text ->
        ConfirmDialog(
            title = "Delete everything and replace it?",
            message = "Every transaction, override, watchlist symbol and import record on " +
                "this phone is deleted first, then the file is loaded. This cannot be " +
                "undone. Choose Merge instead unless you are setting up a new device.",
            confirmText = "Delete and replace",
            onDismiss = { confirmReplace = null },
            onConfirm = {
                confirmReplace = null
                busy = true
                vm.restoreAsync(text, replace = true) { r ->
                    busy = false
                    infoTick++
                    vm.toast(r.summary())
                }
            }
        )
    }

    if (confirmWipe) {
        ConfirmDialog(
            title = "Delete all transactions?",
            message = "This cannot be undone. Export a backup first if you want one.",
            confirmText = "Delete everything",
            onDismiss = { confirmWipe = false },
            onConfirm = { vm.wipeTransactions(); confirmWipe = false; vm.toast("All transactions deleted") }
        )
    }
}

/** e.g. "1.3 (build 4)" - shown so an update can be confirmed at a glance. */
private fun appVersion(ctx: Context): String = runCatching {
    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
        pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    "${pi.versionName} (build $code)"
}.getOrDefault("unknown")

/**
 * First bytes of the signing certificate's SHA-256. Two APKs showing the same value
 * update in place; a different value means Android would refuse the install.
 */
private fun signerShort(ctx: Context): String = runCatching {
    val pm = ctx.packageManager
    val sigs: Array<android.content.pm.Signature> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val pi = pm.getPackageInfo(
                ctx.packageName,
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            )
            pi.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(
                ctx.packageName,
                @Suppress("DEPRECATION") android.content.pm.PackageManager.GET_SIGNATURES
            ).signatures ?: emptyArray()
        }
    val cert = sigs.firstOrNull() ?: return "unknown"
    val md = java.security.MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
    md.take(6).joinToString(":") { String.format(java.util.Locale.US, "%02X", it) } + "..."
}.getOrDefault("unknown")

/** One line of the published fee schedule. */
@Composable
private fun FeeLine(item: com.tj.portfolio.domain.Fees.Item) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.label, style = MaterialTheme.typography.bodyMedium)
            if (item.note.isNotBlank()) {
                Text(
                    item.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            item.amount,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

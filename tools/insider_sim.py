#!/usr/bin/env python3
"""
Python port of the Form 4 pipeline, run against the LIVE SEC service.

WHY THIS EXISTS. The unit tests prove the parser agrees with four filings captured on one
afternoon. They cannot tell you what the Insider tab will actually LOOK like on a real
portfolio in a real month, how many requests a first pass costs, or whether the headline
wording reads as English across a few hundred filings written by a few hundred filing agents.
This does - it is deliberately a re-implementation rather than a wrapper, so a bug in the
Kotlin shows up as a disagreement rather than being faithfully reproduced.

    python3 tools/insider_sim.py NVDA AAPL INTC F MSFT ...

Mirrors net/Form4.kt and net/Insider.kt as of round 55.
"""
import re
import sys
import time
import urllib.request
import urllib.parse
from datetime import datetime, timedelta, timezone

UA = "TJ Portfolio Tracker (personal use) tjshea90@gmail.com"
WINDOW_DAYS = 31
MAX_PER_SYMBOL = 25
MAX_DOCS_PER_PASS = 120
MAX_MARKET_PAGES_PER_PASS = 3

REQUESTS = {"listing": 0, "doc": 0, "bytes": 0}


def get(url):
    req = urllib.request.Request(
        url, headers={"User-Agent": UA,
                      "Accept": "application/atom+xml,application/xml,text/plain"})
    body = urllib.request.urlopen(req, timeout=45).read()
    REQUESTS["bytes"] += len(body)
    return body.decode("utf-8", "replace")


# ------------------------------------------------------------------ listing

def list_filings(symbol, since):
    url = ("https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK="
           + urllib.parse.quote(symbol) + "&type=4&dateb=&datea=" + since
           + "&owner=include&count=100&output=atom")
    REQUESTS["listing"] += 1
    xml = get(url)
    out = []
    for m in re.finditer(r"<entry>(.*?)</entry>", xml, re.S):
        e = m.group(1)
        ft = re.search(r"<filing-type>([^<]*)", e)
        # `type=4` is a PREFIX match on EDGAR's side - it returns 424B5 too, and those
        # submissions are half a megabyte.
        if not ft or ft.group(1).strip() not in ("4", "4/A"):
            continue
        acc = re.search(r"<accession-number>([^<]*)", e)
        href = re.search(r"<filing-href>([^<]*)", e)
        upd = re.search(r"<updated>([^<]*)", e)
        if not (acc and href):
            continue
        stamp = 0
        if upd:
            try:
                stamp = datetime.fromisoformat(upd.group(1).strip()).timestamp()
            except ValueError:
                stamp = 0
        out.append((acc.group(1).strip(), href.group(1).strip(), stamp))
    out.sort(key=lambda r: -r[2])
    return out[:MAX_PER_SYMBOL]


# ------------------------------------------------------------------- parse

def block(src, tag, start=0):
    while True:
        i = src.find("<" + tag, start)
        if i < 0:
            return None
        j = src.find(">", i)
        if j < 0:
            return None
        after = src[i + len(tag) + 1]
        if after not in ">/ \n\r\t":
            start = i + 1
            continue
        if src[j - 1] == "/":
            return (j + 1, j + 1)
        k = src.find("</" + tag + ">", j)
        return None if k < 0 else (j + 1, k)


def val(src, tag):
    r = block(src, tag)
    if not r:
        return ""
    inner = src[r[0]:r[1]]
    v = block(inner, "value")
    text = inner[v[0]:v[1]] if v else inner
    out = text.strip()
    # A leaf never contains markup. Filers may attach a footnote to an element INSTEAD of
    # giving it a value - <transactionPricePerShare><footnoteId id="F1"/></...> is real, and
    # about one filing in ten does it. See the matching guard in Form4.valueOf.
    if out.startswith("<") or "</" in out:
        return ""
    return (out.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", '"').replace("&#39;", "'").replace("&amp;", "&"))


def truthy(s):
    return s.strip().lower() in ("1", "true", "y", "yes")


ACTIONS = {"P": "BUY", "S": "SELL", "A": "GRANT", "M": "EXERCISE", "X": "EXERCISE",
           "C": "EXERCISE", "F": "TAX", "G": "GIFT"}
VERBS = {"BUY": "bought", "SELL": "sold", "GRANT": "was granted",
         "EXERCISE": "exercised options for", "TAX": "surrendered",
         "GIFT": "gifted away"}

ENTITY = {"inc", "corp", "corporation", "co", "company", "llc", "lp", "llp", "plc", "ltd",
          "limited", "trust", "fund", "funds", "partners", "partnership", "capital", "group",
          "holdings", "holding", "management", "advisors", "advisers", "associates",
          "ventures", "sa", "nv", "ag", "gmbh", "se", "bv", "foundation", "bank",
          "investments", "securities", "asset", "family", "trustee", "estate", "gp", "lllp"}
SUFFIX = {"II", "III", "IV", "V", "VI"}


def title_case(s):
    return " ".join(w.upper() if w.upper() in SUFFIX else (w[:1].upper() + w[1:].lower())
                    for w in s.split(" ") if w != "")


CONNECTIVES = {"and", "the", "of", "for", "to", "in", "at", "a", "or"}


def soften(title):
    """Mirror of Form4.softenWord: short all-caps words are abbreviations and stay."""
    out = []
    for w in title.split(" "):
        letters = "".join(c for c in w if c.isalpha())
        if not letters:
            out.append(w)
        elif letters.lower() in CONNECTIVES:
            out.append(w.lower())
        elif len(letters) <= 4:
            out.append(w)
        else:
            out.append(w[0].upper() + w[1:].lower())
    return " ".join(out)


def person_name(raw):
    t = re.sub(r"\s+", " ", raw.strip())
    if not t:
        return ""
    cased = title_case(t) if t == t.upper() else t
    parts = t.split(" ")
    if len(parts) < 2 or len(parts) > 4:
        return cased
    if any(w in ENTITY for w in re.split(r"[^a-z0-9]+", t.lower())):
        return cased
    c = cased.split(" ")
    return " ".join(c[1:] + [c[0]])


PLAIN_STOCK = {"", "common", "stock", "shares", "common stock", "capital stock",
               "common shares", "ordinary shares", "ordinary stock", "common share",
               "voting common stock"}


def security_name(raw):
    head = re.sub(r"\s+", " ", raw.strip()).split(",")[0].strip()
    if not head:
        return ""
    m = re.match(r"class\s+([A-Za-z0-9]+)\b", head, re.I)
    cls = m.group(1).upper() if m else None
    rest = (head[m.end():] if m else head).strip().lower()
    if rest in PLAIN_STOCK:
        return f"Class {cls} shares" if cls else ""
    return head[:40]


def plural(name, count):
    if count == 1 or name.lower().endswith("s"):
        return name
    return name + "s"


def parse_form4(symbol, accession, url, filed_at, body):
    i = body.find("<ownershipDocument")
    if i < 0:
        return None
    j = body.find("</ownershipDocument>", i)
    doc = body[i:] if j < 0 else body[i:j]
    if val(doc, "documentType").strip() not in ("4", "4/A"):
        return None

    fn = block(doc, "footnotes")
    notes = doc[fn[0]:fn[1]].lower() if fn else ""
    plan_el = val(doc, "aff10b5One")
    planned = (truthy(plan_el) if plan_el else False) or \
        any(k in notes for k in ("10b5-1", "10b5.1", "10b51", "trading plan"))

    officer_title = val(doc, "officerTitle").strip()
    if truthy(val(doc, "isOfficer")) and officer_title:
        role = officer_title if any(c.islower() for c in officer_title) \
            else soften(officer_title)
    elif truthy(val(doc, "isOfficer")):
        role = "Officer"
    elif truthy(val(doc, "isDirector")):
        role = "Director"
    elif truthy(val(doc, "isTenPercentOwner")):
        role = "10% owner"
    else:
        role = "Insider"

    trades = []
    for tag, deriv in (("nonDerivativeTransaction", False), ("derivativeTransaction", True)):
        pos = 0
        while True:
            r = block(doc, tag, pos)
            if not r:
                break
            t = doc[r[0]:r[1]]
            pos = r[1]
            code = val(t, "transactionCode").strip().upper()
            if not code:
                continue
            trades.append({
                "code": code, "action": ACTIONS.get(code, "OTHER"),
                "security": security_name(val(t, "securityTitle")),
                "disposed": val(t, "transactionAcquiredDisposedCode").strip().upper() == "D",
                "shares": float(val(t, "transactionShares") or 0),
                "price": float(val(t, "transactionPricePerShare") or 0),
                "after": float(val(t, "sharesOwnedFollowingTransaction") or 0),
                "deriv": deriv,
            })
    if not trades:
        return None

    def score(t):
        s = 0.0
        if t["action"] in ("BUY", "SELL"):
            s += 1e15
        if not t["deriv"]:
            s += 1e13
        return s + min(t["shares"], 1e12)

    best = max(trades, key=score)
    same = [t for t in trades if t["code"] == best["code"]
            and t["disposed"] == best["disposed"] and t["deriv"] == best["deriv"]]
    shares = sum(t["shares"] for t in same)
    paid = sum(t["shares"] * t["price"] for t in same)
    head = dict(best)
    head["shares"] = shares
    head["price"] = paid / shares if shares else best["price"]
    head["after"] = same[-1]["after"]

    return {"symbol": val(doc, "issuerTradingSymbol").strip().upper() or symbol,
            "accession": accession, "url": url, "filed_at": filed_at,
            "person": person_name(val(doc, "rptOwnerName")), "role": role,
            "planned": planned, "trade": head, "trades": trades}


def qty(v):
    return f"{v/1e6:,.2f}M" if v >= 1e6 else f"{v:,.0f}"


def money(v):
    if v >= 1e9:
        return f"${v/1e9:.2f}B"
    if v >= 1e6:
        return f"${v/1e6:.2f}M"
    if v >= 1e3:
        return f"${v/1e3:,.0f}K"
    return f"${v:,.0f}"


def headline(f):
    t = f["trade"]
    verb = VERBS.get(t["action"], "disposed of" if t["disposed"] else "acquired")
    what = (f"{qty(t['shares'])} {plural(t['security'], t['shares'])}"
            if t["security"] else f"{qty(t['shares'])} shares")
    s = f"{f['role']} {verb} {what}"
    v = t["shares"] * t["price"]
    if v > 0:
        s += " — " + money(v)
    return s


# ------------------------------------------------------- market-wide (all companies)

MIN_MARKET_TRADE_VALUE = 50_000.0


def current_listing(start=0):
    """One page of EDGAR's `getcurrent` feed - a different shape from `list_filings`'s
    per-CIK listing: no <filing-type>/<accession-number>/<filing-href>, the form type is
    <category term=...>, the accession lives inside <id>, and the page link is a plain Atom
    <link href=...>. One entry PER PARTY, not per filing - dedup by accession is most of the
    work, not an edge case."""
    url = ("https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent&type=4&company="
           "&dateb=&owner=include&count=100&output=atom" + (f"&start={start}" if start else ""))
    REQUESTS["listing"] += 1
    xml = get(url)
    out, seen = [], set()
    for m in re.finditer(r"<entry>(.*?)</entry>", xml, re.S):
        e = m.group(1)
        term = re.search(r'<category[^>]*\bterm="([^"]*)"', e)
        # type=4 is a PREFIX match here too, same trap as the per-symbol listing.
        if not term or term.group(1) not in ("4", "4/A"):
            continue
        acc_m = re.search(r"accession-number=([0-9-]+)", e)
        href_m = re.search(r'<link[^>]*href="([^"]*)"', e)
        upd = re.search(r"<updated>([^<]*)", e)
        if not (acc_m and href_m):
            continue
        acc = acc_m.group(1)
        if acc in seen:
            continue
        seen.add(acc)
        stamp = 0
        if upd:
            try:
                stamp = datetime.fromisoformat(upd.group(1).strip()).timestamp()
            except ValueError:
                stamp = 0
        out.append((acc, href_m.group(1), stamp))
    out.sort(key=lambda r: -r[2])
    return out


def main_market(pages):
    print(f"market-wide: reading {pages} page(s) of EDGAR's getcurrent feed "
          f"(major = ${MIN_MARKET_TRADE_VALUE:,.0f}+, discretionary only)\n")
    refs = []
    for p in range(pages):
        try:
            page = current_listing(p * 100)
        except Exception as e:
            print(f"  page {p}: failed: {e}")
            continue
        print(f"  page {p}: {len(page)} real Form 4s (after the type filter)")
        refs += page
        time.sleep(0.12)

    refs = list({acc: (acc, href, stamp) for acc, href, stamp in refs}.values())
    refs.sort(key=lambda r: -r[2])
    print(f"\n{len(refs)} distinct filings across {pages} page(s); fetching documents\n")

    filings = []
    for acc, href, stamp in refs:
        try:
            REQUESTS["doc"] += 1
            body = get(href.replace("-index.htm", ".txt"))
        except Exception as e:
            print(f"   ! {acc}: {e}")
            continue
        f = parse_form4("", acc, href, stamp, body)
        if f:
            filings.append(f)
        time.sleep(0.12)

    filings.sort(key=lambda f: -f["filed_at"])
    major = [
        f for f in filings
        if f["trade"]["action"] in ("BUY", "SELL") and not f["planned"]
        and f["trade"]["shares"] * f["trade"]["price"] >= MIN_MARKET_TRADE_VALUE
    ]
    print(f"\n===== MAJOR, ALL COMPANIES  ({len(major)} of {len(filings)} parsed)")
    for f in major[:40]:
        when = datetime.fromtimestamp(f["filed_at"], timezone.utc).strftime("%b %d")
        print(f"  {when}  {f['symbol']:<6} {headline(f)}")
        print(f"          {f['person']} · {f['trade']['code']}")

    print(f"\nrequests: {REQUESTS['listing']} listings + {REQUESTS['doc']} documents = "
          f"{REQUESTS['listing'] + REQUESTS['doc']}, {REQUESTS['bytes']/1024:.0f} KB")
    print(f"parsed {len(filings)} of {len(refs)} documents, {len(major)} cleared the major floor")


# -------------------------------------------------------------------- main

def main(symbols):
    since = (datetime.now(timezone.utc) - timedelta(days=WINDOW_DAYS)).strftime("%Y-%m-%d")
    print(f"window: filings on or after {since}   symbols: {len(symbols)}\n")

    refs = []
    for s in symbols:
        try:
            found = list_filings(s, since)
        except Exception as e:
            print(f"  {s}: listing failed: {e}")
            continue
        print(f"  {s}: {len(found)} Form 4s in window")
        refs += [(s,) + r for r in found]
        time.sleep(0.12)

    refs.sort(key=lambda r: -r[3])
    refs = refs[:MAX_DOCS_PER_PASS]
    print(f"\nfetching {len(refs)} filing documents (pass budget {MAX_DOCS_PER_PASS})\n")

    filings = []
    for sym, acc, href, stamp in refs:
        try:
            REQUESTS["doc"] += 1
            body = get(href.replace("-index.htm", ".txt"))
        except Exception as e:
            print(f"   ! {sym} {acc}: {e}")
            continue
        f = parse_form4(sym, acc, href, stamp, body)
        if f:
            filings.append(f)
        time.sleep(0.12)

    filings.sort(key=lambda f: -f["filed_at"])

    def show(title, rows):
        print(f"\n===== {title}  ({len(rows)})")
        for f in rows[:30]:
            when = datetime.fromtimestamp(f["filed_at"], timezone.utc).strftime("%b %d")
            tag = "  [10b5-1]" if f["planned"] else ""
            print(f"  {when}  {f['symbol']:<6} {headline(f)}{tag}")
            print(f"          {f['person']} · {f['trade']['code']} · "
                  f"{qty(f['trade']['after'])} held after")

    show("OPEN MARKET (unplanned purchases and sales)",
         [f for f in filings if f["trade"]["action"] in ("BUY", "SELL") and not f["planned"]])
    show("ALL TRADES (includes 10b5-1 plans)",
         [f for f in filings if f["trade"]["action"] in ("BUY", "SELL")])
    show("EVERYTHING", filings)

    codes = {}
    for f in filings:
        codes[f["trade"]["code"]] = codes.get(f["trade"]["code"], 0) + 1
    print("\ncodes:", dict(sorted(codes.items(), key=lambda kv: -kv[1])))
    print(f"requests: {REQUESTS['listing']} listings + {REQUESTS['doc']} documents = "
          f"{REQUESTS['listing'] + REQUESTS['doc']}, {REQUESTS['bytes']/1024:.0f} KB")
    print(f"parsed {len(filings)} of {len(refs)} documents")


if __name__ == "__main__":
    args = sys.argv[1:]
    if args and args[0] == "--market":
        main_market(int(args[1]) if len(args) > 1 else MAX_MARKET_PAGES_PER_PASS)
    else:
        main([s.upper() for s in args] or ["NVDA", "AAPL", "INTC", "F"])

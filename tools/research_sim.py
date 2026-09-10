#!/usr/bin/env python3
"""
PLAUSIBILITY HARNESS for the Research tab's scorers (Round 54).

The unit tests prove the scorers behave correctly on hand-built rows. They cannot prove the
OUTPUT IS SENSIBLE, because that depends on what the real market looks like today - and a
scorer can be internally consistent and still rank a delisted shell above Nvidia.

So this is a faithful Python port of `net/ResearchScore.kt`, run against the same nine live
Yahoo screeners the app reads, printing the top ten of each section exactly as the phone
would show them. It is the same technique used for the ClaudeBridge brace scanner in v5.2:
port the algorithm, run it on real inputs, read the output with your own eyes.

If you change the Kotlin scorer, change this too and re-run it. Divergence here is not a
build failure - it is worse than that, it is a harness that agrees with a bug.

    python3 tools/research_sim.py
"""
import json
import math
import urllib.request

LISTS = [
    "day_gainers", "day_losers", "most_actives", "most_shorted_stocks",
    "undervalued_growth_stocks", "growth_technology_stocks",
    "undervalued_large_caps", "small_cap_gainers", "aggressive_small_caps",
]
UA = {"User-Agent": "Mozilla/5.0"}


def fetch(list_id, count=50):
    url = ("https://query1.finance.yahoo.com/v1/finance/screener/predefined/saved"
           f"?scrIds={list_id}&count={count}&start=0")
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=25) as r:
            d = json.load(r)
    except Exception as e:                                     # noqa: BLE001
        print(f"  ! {list_id}: {e}")
        return []
    res = d.get("finance", {}).get("result") or []
    return res[0].get("quotes", []) if res else []


def num(q, k):
    v = q.get(k)
    return float(v) if isinstance(v, (int, float)) else 0.0


def ramp(v, lo, hi, pts):
    if v != v or hi <= lo:
        return 0.0
    return max(0.0, min(1.0, (v - lo) / (hi - lo))) * pts


def log_ramp(v, lo, hi, pts):
    if v <= 0:
        return 0.0
    return ramp(math.log(v), math.log(lo), math.log(hi), pts)


def range_pos(r):
    span = r["hi52"] - r["lo52"]
    return (r["price"] - r["lo52"]) / span if span > 1e-9 and r["price"] > 0 else -1.0


def eps_growth(r):
    return (r["epsF"] - r["epsT"]) / r["epsT"] if r["epsT"] > 0.01 and r["epsF"] > 0 else float("nan")


def score_best(r):
    s, why = 0.0, []
    g = eps_growth(r)
    if g == g:
        s += ramp(g * 100, 0, 40, 25)
        if g > 0.05:
            why.append(f"EPS growth {g*100:.0f}%")
    elif r["epsF"] > 0 >= r["epsT"]:
        s += 14
        why.append("turning profitable")
    if r["fpe"] > 0:
        s += ramp(-r["fpe"], -45, -8, 20)
        if r["fpe"] <= 25:
            why.append(f"fwd P/E {r['fpe']:.1f}")
    if r["price"] > 0 and r["ma50"] > 0 and r["ma200"] > 0:
        t = (10 if r["price"] > r["ma50"] else 0) + (10 if r["ma50"] > r["ma200"] else 0)
        s += t
        if t == 20:
            why.append("uptrend")
    p = range_pos(r)
    if p >= 0:
        s += 10 if 0.45 <= p <= 0.90 else (5 if p > 0.90 else ramp(p, 0.10, 0.45, 6))
    if r["cap"] > 0:
        s += log_ramp(r["cap"], 3e8, 2e10, 10)
    if r["vol3m"] > 0:
        s += log_ramp(r["vol3m"], 1e5, 5e6, 5)
    lp = (5 if "undervalued_growth_stocks" in r["lists"] else 0) \
        + (4 if "growth_technology_stocks" in r["lists"] else 0) \
        + (3 if "undervalued_large_caps" in r["lists"] else 0) \
        + (2 if "most_actives" in r["lists"] else 0)
    s += min(lp, 10)
    if r["pb"] < 0:
        s -= 8
        why.append("negative book value")
    return int(max(0.0, min(100.0, s))), why


def score_worst(r):
    s, why = 0.0, []
    if r["epsT"] or r["epsF"]:
        if r["epsT"] < 0:
            s += 15
            why.append("loss-making")
            if r["epsF"] < 0:
                s += 10
                why.append("still forecast to lose")
        elif 0 <= r["epsF"] <= r["epsT"] and r["epsT"] > 0.01:
            shrink = (r["epsT"] - r["epsF"]) / r["epsT"]
            s += ramp(shrink * 100, 0, 50, 15)
            if shrink > 0.10:
                why.append("earnings shrinking")
    if r["price"] > 0 and r["ma50"] > 0 and r["ma200"] > 0:
        t = (10 if r["price"] < r["ma50"] else 0) + (10 if r["ma50"] < r["ma200"] else 0) \
            + (5 if r["price"] < r["ma200"] else 0)
        s += t
        if t == 25:
            why.append("clear downtrend")
    if r["chg52"]:
        s += ramp(-r["chg52"], 10, 65, 20)
        if r["chg52"] <= -20:
            why.append(f"down {abs(r['chg52']):.0f}% in a year")
    p = range_pos(r)
    if p >= 0:
        s += ramp(-p, -0.35, -0.02, 10)
        if p <= 0.12:
            why.append("near 52w low")
    if "most_shorted_stocks" in r["lists"]:
        s += 10
        why.append("most-shorted")
    v = 0.0
    if r["fpe"] > 80:
        v += 6
    if r["pb"] < 0:
        v += 5
        why.append("negative book value")
    elif r["pb"] > 15:
        v += 3
    s += min(v, 10)
    if r["cap"] > 0:
        s += ramp(-r["cap"], -2e9, -1e8, 10)
        if r["cap"] < 5e8:
            why.append("small cap")
    return int(max(0.0, min(100.0, s))), why


def main():
    universe = {}
    print("Fetching nine live Yahoo screeners...")
    for lid in LISTS:
        qs = fetch(lid)
        print(f"  {lid:28s} {len(qs):3d}")
        for q in qs:
            if q.get("quoteType") != "EQUITY":
                continue
            sym = (q.get("symbol") or "").upper()
            if not sym or "-" in sym or "^" in sym:
                continue
            r = universe.setdefault(sym, {
                "sym": sym,
                "name": q.get("longName") or q.get("shortName") or "",
                "price": num(q, "regularMarketPrice"),
                "chg": num(q, "regularMarketChangePercent"),
                "cap": num(q, "marketCap"),
                "vol3m": num(q, "averageDailyVolume3Month"),
                "fpe": num(q, "forwardPE"),
                "pb": num(q, "priceToBook"),
                "epsT": num(q, "epsTrailingTwelveMonths"),
                "epsF": num(q, "epsForward"),
                "ma50": num(q, "fiftyDayAverage"),
                "ma200": num(q, "twoHundredDayAverage"),
                "hi52": num(q, "fiftyTwoWeekHigh"),
                "lo52": num(q, "fiftyTwoWeekLow"),
                "chg52": num(q, "fiftyTwoWeekChangePercent"),
                "lists": set(),
            })
            r["lists"].add(lid)

    rows = [r for r in universe.values()
            if r["price"] >= 1.0 and (r["cap"] <= 0 or r["cap"] >= 5e7)]
    print(f"\nUniverse: {len(universe)} symbols, {len(rows)} tradable\n")

    best = sorted(((score_best(r), r) for r in rows
                   if r["epsF"] or r["fpe"] > 0), key=lambda x: -x[0][0])[:10]
    worst = sorted(((score_worst(r), r) for r in rows), key=lambda x: -x[0][0])[:10]

    for title, rank in (("BEST", best), ("WORST", worst)):
        print(f"===== {title} =====")
        for (sc, why), r in rank:
            cap = f"{r['cap']/1e9:.1f}B" if r["cap"] else "?"
            print(f"{sc:3d}  {r['sym']:<6s} {r['name'][:30]:<30s} "
                  f"${r['price']:>9,.2f}  cap {cap:>7s}  {', '.join(why)}")
        print()


if __name__ == "__main__":
    main()

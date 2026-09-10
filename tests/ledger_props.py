"""Property tests over domain/Ledger.kt, re-run and extended for round 46."""
import os, random, sys, importlib.util
ZEROQ = os.environ.get('ZEROQ', '1') == '1'
spec = importlib.util.spec_from_file_location("L", "/home/claude/portfolio/tools_ledger_port.py")
L = importlib.util.module_from_spec(spec); spec.loader.exec_module(L)

SYMS = ["NVDA", "CSCO", "SOXQ", "BA", "ONDS"]
DAY = 86_400_000


def gen(rng, n):
    txns, i = [], 0
    for _ in range(n):
        i += 1
        t = rng.choice([L.BUY, L.BUY, L.SELL, L.DEPOSIT, L.WITHDRAWAL, L.DIVIDEND, L.FEE])
        sym = rng.choice(SYMS) if t in (L.BUY, L.SELL, L.DIVIDEND) else None
        qty = round(rng.uniform(0, 50), 4) if t in (L.BUY, L.SELL) else 0.0
        if ZEROQ and rng.random() < 0.06:
            qty = 0.0                                    # zero-quantity rows
        px = round(rng.uniform(0.2, 500), 4) if t in (L.BUY, L.SELL) else 0.0
        if rng.random() < 0.25:
            px = 0.0                                     # amount-only rows
        fees = round(rng.uniform(0, 6), 2) if rng.random() < 0.3 else 0.0
        amt = round(qty * (px or rng.uniform(0.2, 500)), 2) if t in (L.BUY, L.SELL) \
            else round(rng.uniform(1, 3000), 2)
        txns.append(dict(id=i, type=t, symbol=sym, quantity=qty, price=px,
                         amount=L.cash_effect(t, qty, px, amt, fees), fees=fees,
                         date=1_750_000_000_000 + rng.randrange(400) * DAY, note=None))
    return txns


def check(txns):
    f = L.fifo(txns); a = L.average(txns)
    problems = []
    # 1. total lifetime P/L is identical under both methods
    prices = {s: round(random.uniform(1, 500), 2) for s in SYMS}
    def equity(pos):
        return sum(p["shares"] * prices[s] for s, p in pos.items())
    def realized(pos):
        return sum(p["realized"] for p in pos.values())
    def basis(pos):
        return sum(p["costBasis"] for p in pos.values())
    lf = realized(f) + (equity(f) - basis(f))
    la = realized(a) + (equity(a) - basis(a))
    if abs(lf - la) > 1e-6:
        problems.append(f"lifetime P/L differs FIFO {lf:.6f} vs AVG {la:.6f}")
    # 2. share counts agree
    for s in set(f) | set(a):
        sf = f.get(s, {}).get("shares", 0.0); sa = a.get(s, {}).get("shares", 0.0)
        if abs(sf - sa) > 1e-6:
            problems.append(f"{s}: shares FIFO {sf} vs AVG {sa}")
    # 3. no negative shares or negative cost basis; a closed position keeps no basis
    for name, pos in (("FIFO", f), ("AVG", a)):
        for s, p in pos.items():
            if p["shares"] < -1e-9: problems.append(f"{name} {s}: negative shares {p['shares']}")
            if p["costBasis"] < -1e-6: problems.append(f"{name} {s}: negative basis {p['costBasis']}")
            if p["shares"] < 1e-9 and abs(p["costBasis"]) > 1e-6:
                problems.append(f"{name} {s}: closed but basis {p['costBasis']}")
    # 4. equity - net deposits == realized + unrealized + dividends - fees
    for name, pos in (("FIFO", f), ("AVG", a)):
        lhs = (equity(pos) + L.cash(txns)) - L.net_deposits(txns)
        # Only FEE-TYPE rows belong on the right: a per-trade `fees` value is already
        # inside realized (subtracted from proceeds) and inside cost basis (added to the
        # lot), so counting it again here would double it.
        fee_rows = sum(abs(t["amount"]) for t in txns if t["type"] == L.FEE)
        rhs = realized(pos) + (equity(pos) - basis(pos)) + L.dividends(txns) - fee_rows
        if abs(lhs - rhs) > 1e-6:
            problems.append(f"{name}: reconciliation off by {lhs - rhs:.9f}")
    return problems


def main():
    runs = int(sys.argv[1]) if len(sys.argv) > 1 else 5000
    bad = 0
    for i in range(runs):
        rng = random.Random(i)
        random.seed(i)
        problems = check(gen(rng, rng.randrange(1, 40)))
        if problems:
            bad += 1
            if bad <= 3:
                print(f"  seed {i}: " + "; ".join(problems[:3]))
    print(f"{runs} randomised histories: {runs - bad} clean, {bad} with a violation")
    return 1 if bad else 0


sys.exit(main())

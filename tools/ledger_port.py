"""Faithful port of domain/Ledger.kt + Txn.cashEffect, for property testing."""
from collections import deque

BUY,SELL,DEPOSIT,WITHDRAWAL,DIVIDEND,INTEREST,FEE = (
    "BUY","SELL","DEPOSIT","WITHDRAWAL","DIVIDEND","INTEREST","FEE")

def cash_effect(type_, qty, price, amount, fees):
    gross = qty * price
    if type_ == BUY:   return -(gross if gross > 0 else abs(amount)) - fees
    if type_ == SELL:  return  (gross if gross > 0 else abs(amount)) - fees
    if type_ in (DEPOSIT, DIVIDEND, INTEREST): return abs(amount)
    if type_ in (WITHDRAWAL, FEE): return -abs(amount)
    return amount

def unit_price(t, qty):
    if t["price"] > 0: return t["price"]
    if qty < 1e-9: return 0.0
    if t["type"] == BUY:  gross = max(abs(t["amount"]) - t["fees"], 0.0)
    elif t["type"] == SELL: gross = max(t["amount"] + t["fees"], 0.0)   # signed - v5.9 fix
    else: gross = abs(t["amount"])
    return gross / qty

def _key(t): return (t["date"], t["id"])

def fifo(txns, today=range(0,0)):
    # "BOUGHT TODAY" RIDES ON THE LOT, exactly as Ledger.kt does it (CRX-1). This used to be
    # a pair of side maps that a BUY added to and nothing ever removed from - the very bug
    # CRX-1 fixed in the Kotlin, left standing here - and nothing returned them anyway, so
    # the port could not see a same-day defect of any kind. A lot carries its own flag, so
    # whatever is still in the deque and flagged is genuinely what was bought today and held.
    lots, realized, first = {}, {}, {}
    for t in sorted(txns, key=_key):
        sym = (t["symbol"] or "").upper()
        if not sym: continue
        if t["type"] == DIVIDEND: lots.setdefault(sym, deque()); continue
        if t["type"] not in (BUY, SELL): continue
        q = lots.setdefault(sym, deque()); realized.setdefault(sym, 0.0)
        qty = abs(t["quantity"])
        if qty < 1e-9: continue
        px = unit_price(t, qty)
        if t["type"] == BUY:
            first.setdefault(sym, t["date"])
            q.append([qty, (qty*px + t["fees"])/qty, t["date"] in today])
        else:
            remaining, cost_out = qty, 0.0
            while remaining > 1e-9 and q:
                lot = q[0]
                take = min(remaining, lot[0])
                cost_out += take*lot[1]; lot[0] -= take; remaining -= take
                if lot[0] < 1e-9: q.popleft()
            realized[sym] += (qty*px - t["fees"]) - cost_out
    out = {}
    for sym, q in lots.items():
        shares = sum(l[0] for l in q)
        cost   = sum(l[0]*l[1] for l in q)
        fresh  = [l for l in q if l[2]]
        out[sym] = dict(symbol=sym, shares=shares, costBasis=cost,
                        realized=realized.get(sym,0.0),
                        sharesToday=sum(l[0] for l in fresh),
                        costToday=sum(l[0]*l[1] for l in fresh))
    return out

def _acc():
    return dict(shares=0.0, cost=0.0, realized=0.0, tshares=0.0, tcost=0.0)

def average(txns, today=range(0,0)):
    acc = {}
    for t in sorted(txns, key=_key):
        sym = (t["symbol"] or "").upper()
        if not sym: continue
        if t["type"] not in (BUY, SELL):
            if t["type"] == DIVIDEND: acc.setdefault(sym, _acc())
            continue
        a = acc.setdefault(sym, _acc())
        qty = abs(t["quantity"])
        if qty < 1e-9: continue          # the v5.4 guard
        px = unit_price(t, qty)
        if t["type"] == BUY:
            a["shares"] += qty; a["cost"] += qty*px + t["fees"]
            if t["date"] in today:
                a["tshares"] += qty; a["tcost"] += qty*px + t["fees"]
        else:
            avg = a["cost"]/a["shares"] if a["shares"] > 1e-9 else 0.0
            shares_before = max(a["shares"], 0.0)
            covered = min(qty, shares_before)
            a["realized"] += (qty*px - t["fees"]) - covered*avg
            a["cost"] -= covered*avg; a["shares"] -= covered
            if a["shares"] < 1e-9: a["shares"]=0.0; a["cost"]=0.0
            # OLDEST SHARES FIRST, matching fifo() above and Ledger.averageCost. A sale only
            # reaches today's pool once it has exhausted everything held from before today;
            # taking today's shares first is the Part 10 bug this port could not see, because
            # average() had no same-day tracking at all.
            held_before = max(shares_before - a["tshares"], 0.0)
            from_today = min(max(covered - held_before, 0.0), a["tshares"])
            if from_today > 1e-9 and a["tshares"] > 1e-9:
                a["tcost"] -= from_today * (a["tcost"]/a["tshares"])
                a["tshares"] -= from_today
                if a["tshares"] < 1e-9: a["tshares"]=0.0; a["tcost"]=0.0
    return {s: dict(symbol=s, shares=v["shares"], costBasis=v["cost"],
                    realized=v["realized"], sharesToday=v["tshares"],
                    costToday=v["tcost"]) for s,v in acc.items()}

def cash(txns): return sum(t["amount"] for t in txns)
def net_deposits(txns):
    return sum(abs(t["amount"]) if t["type"]==DEPOSIT else
               -abs(t["amount"]) if t["type"]==WITHDRAWAL else 0.0 for t in txns)

def dividends(txns):
    return sum(abs(t["amount"]) for t in txns if t["type"] in (DIVIDEND, INTEREST))

def fees(txns):
    return sum(t["fees"] for t in txns) + sum(abs(t["amount"]) for t in txns if t["type"] == FEE)

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
    lots, realized, first = {}, {}, {}
    tshares, tcost = {}, {}
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
            q.append([qty, (qty*px + t["fees"])/qty])
            if t["date"] in today:
                tshares[sym] = tshares.get(sym,0.0)+qty
                tcost[sym]  = tcost.get(sym,0.0)+qty*px+t["fees"]
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
        out[sym] = dict(symbol=sym, shares=shares, costBasis=cost,
                        realized=realized.get(sym,0.0))
    return out

def average(txns, today=range(0,0)):
    acc = {}
    for t in sorted(txns, key=_key):
        sym = (t["symbol"] or "").upper()
        if not sym: continue
        if t["type"] not in (BUY, SELL):
            if t["type"] == DIVIDEND: acc.setdefault(sym, dict(shares=0.0,cost=0.0,realized=0.0))
            continue
        a = acc.setdefault(sym, dict(shares=0.0,cost=0.0,realized=0.0))
        qty = abs(t["quantity"])
        if qty < 1e-9: continue          # the v5.4 guard
        px = unit_price(t, qty)
        if t["type"] == BUY:
            a["shares"] += qty; a["cost"] += qty*px + t["fees"]
        else:
            avg = a["cost"]/a["shares"] if a["shares"] > 1e-9 else 0.0
            covered = min(qty, max(a["shares"], 0.0))
            a["realized"] += (qty*px - t["fees"]) - covered*avg
            a["cost"] -= covered*avg; a["shares"] -= covered
            if a["shares"] < 1e-9: a["shares"]=0.0; a["cost"]=0.0
    return {s: dict(symbol=s, shares=v["shares"], costBasis=v["cost"],
                    realized=v["realized"]) for s,v in acc.items()}

def cash(txns): return sum(t["amount"] for t in txns)
def net_deposits(txns):
    return sum(abs(t["amount"]) if t["type"]==DEPOSIT else
               -abs(t["amount"]) if t["type"]==WITHDRAWAL else 0.0 for t in txns)

def dividends(txns):
    return sum(abs(t["amount"]) for t in txns if t["type"] in (DIVIDEND, INTEREST))

def fees(txns):
    return sum(t["fees"] for t in txns) + sum(abs(t["amount"]) for t in txns if t["type"] == FEE)

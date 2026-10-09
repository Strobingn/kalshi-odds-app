"""Hand-built checks of rec_sim.py. Run: python3 test_rec_sim.py"""
import numpy as np
import rec_sim as R
def win(trades, book, result="yes"):
    """trades: (second, yes_price, contracts, taker_yes); book: {second: (yb, ybq, ya, yaq)} carried forward."""
    T = np.array([t[0] + 0.5 - 900.0 for t in trades]); P = np.array([t[1] for t in trades], dtype=np.float32)
    C = np.array([t[2] for t in trades], dtype=np.float32); S = np.array([1 if t[3] else 0 for t in trades], dtype=np.int8)
    yb = np.full(900, np.nan); ybq = yb.copy(); ya = yb.copy(); yaq = yb.copy(); cur = None
    for s in range(900):
        cur = book.get(s, cur)
        if cur: yb[s], ybq[s], ya[s], yaq[s] = cur
    return R.frames(dict(T=T, P=P, C=C, S=S, yb=yb, ybq=ybq, ya=ya, yaq=yaq), result)
D = np.array([100]); n = 0
def check(name, got, want):
    global n; n += 1
    assert (np.isnan(want) and np.isnan(got)) or abs(got - want) < 1e-9, f"{name}: got {got}, want {want}"
# 1. volume rule: 100 ahead + our 10 must trade at 50c. 60 + 49 = 109 is one short; the next contract fills.
up, dn = win([(101, .50, 60, False), (102, .50, 49, False), (103, .50, 1, False), (110, .51, 500, True)], {0: (.50, 100, .51, 200)})
for mode, fill in (("volume", 103), ("front", 101), ("back", -1)):
    pnl, fs, kind = R.rest_scalp(up, D, mode); check("fill " + mode, fs[0], fill)
# offer at 51c: 200 ahead + 10; 500 lifted at 51c -> target, +1c, no fee
pnl, fs, kind = R.rest_scalp(up, D, "volume"); check("target pnl", pnl[0], 0.01); check("target kind", kind[0], R.T_TARGET)
# 2. book rule: the displayed size at our price drops to 5, so at most 5 are ahead: 15 more contracts fill us
up, dn = win([(101, .50, 20, False), (103, .50, 15, False)], {0: (.50, 5000, .51, 200), 102: (.50, 5, .51, 200)})
check("book fill", R.rest_scalp(up, D, "book")[1][0], 103); check("volume no fill", R.rest_scalp(up, D, "volume")[1][0], -1)
check("unfilled is 0", R.rest_scalp(up, D, "volume")[0][0], 0.0)
# 3. book rule: best bid falls below our price with no print -> nothing ahead any more; a later 10-lot at our price fills
up, dn = win([(104, .50, 10, False)], {0: (.50, 5000, .51, 200), 102: (.49, 800, .50, 300), 103: (.50, 50, .51, 200)})
check("level cleared", R.rest_scalp(up, D, "book")[1][0], 104)
# 4. stop: filled at 50c (through print at 49c), book bid 46c next second -> sell at 46c, fee ceil(0.07*10*.46*.54*100)=18c/10
up, dn = win([(101, .49, 30, False)], {0: (.50, 100, .51, 200), 102: (.46, 100, .47, 100)})
pnl, fs, kind = R.rest_scalp(up, D, "book"); check("stop fill", fs[0], 101); check("stop pnl", pnl[0], 0.46 - 0.50 - 0.018); check("stop kind", kind[0], R.T_STOP)
# 5. time-out: filled, nothing happens for 120 s, sold at the bid 50c: fee ceil(17.5)=18c -> -1.8c
up, dn = win([(101, .49, 30, False)], {0: (.50, 100, .51, 200)})
pnl, fs, kind = R.rest_scalp(up, D, "book"); check("timeout pnl", pnl[0], -0.018); check("timeout kind", kind[0], R.T_TIMEOUT)
# 6. settles: filled at second 801 (decision 790), window ends before the time-out; result yes -> 1 - 0.50
pnl, fs, kind = R.rest_scalp(win([(801, .49, 30, False)], {0: (.50, 100, .51, 200)})[0], np.array([790]), "book")
check("settle pnl", pnl[0], 0.50); check("settle kind", kind[0], R.T_SETTLED)
# 7. the DOWN frame mirrors: NO bid = 1 - YES ask = 49c with 200 ahead; takers buying YES at 51c sell NO at 49c
up, dn = win([(101, .51, 210, True), (105, .50, 110, False)], {0: (.50, 100, .51, 200)}, result="no")
pnl, fs, kind = R.rest_scalp(dn, D, "volume"); check("down fill", fs[0], 101); check("down target", pnl[0], 0.01)
# 8. improve: spread 2c -> bid 51c with nothing ahead; the first taker sale at or below 51c fills
up, dn = win([(101, .50, 10, False), (102, .52, 210, True)], {0: (.50, 100, .52, 200)})
pnl, fs, kind = R.improve_scalp(up, D, "book"); check("improve fill", fs[0], 101); check("improve pnl", pnl[0], 0.01)
check("no improve at 1c spread", R.improve_scalp(win([], {0: (.50, 100, .51, 200)})[0], D, "book")[0][0], np.nan)
# 9. buy now at 51c: fee 18c/10 in, offer 53c lifted through (54c print) -> +2c - 1.8c
up, dn = win([(101, .54, 10, True)], {0: (.50, 100, .51, 200)})
pnl, kind = R.take_scalp(up, D, "book", 0.02, 0.02, 30); check("take pnl", pnl[0], 0.02 - 0.018)
# 10. not live outside 10c..90c or with a stale book
check("price band", R.rest_scalp(win([], {0: (.95, 100, .96, 200)})[0], D, "book")[0][0], np.nan)
# 11. offer queue: filled at 50c while the best ask is 53c -> our 51c offer is alone in front: any lift at >= 51c fills
up, dn = win([(101, .49, 30, False), (102, .51, 10, True)], {0: (.50, 100, .51, 200), 101: (.48, 100, .53, 200)})
pnl, fs, kind = R.rest_scalp(up, D, "book", S=0.10); check("offer alone", pnl[0], 0.01)
# 12. mark-out: buy at 51c, the bid 10 s later is 55c -> +4c - fees ceil(17.49)=18c and ceil(17.325)=18c per 10
up, dn = win([], {0: (.50, 100, .51, 200), 105: (.55, 100, .56, 200)})
check("markout", R.take_markout(up, D, 10)[0], 0.55 - 0.51 - 0.018 - 0.018)
check("hold yes", R.take_hold(up, D)[0], 1.0 - 0.51 - 0.018)
check("hold down side", R.take_hold(dn, D)[0], 0.0 - 0.50 - 0.018)
print("ok", n, "checks")

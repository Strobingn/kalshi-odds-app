"""Hand-built checks of depth_sim.py. Run: python3 test_depth_sim.py"""
import numpy as np
import depth_sim as DS, rec_sim as R
L = 15
def win(trades, books, result="yes"):
    """trades: (second, yes_price, contracts, taker_yes); books: {second: (yes levels, no levels)} as [(price, size)...], carried forward."""
    T = np.array([t[0] + 0.5 - 900.0 for t in trades]); P = np.array([t[1] for t in trades], np.float32)
    C = np.array([t[2] for t in trades], np.float32); S = np.array([1 if t[3] else 0 for t in trades], np.int8)
    YP = np.full((900, L), np.nan, np.float32); YQ = YP.copy(); NP = YP.copy(); NQ = YP.copy(); YN = np.full(900, -1, np.int16); NN = YN.copy(); cur = None
    for s in range(900):
        cur = books.get(s, cur)
        if cur:
            y, n = cur
            YP[s, :len(y)] = [a for a, _ in y]; YQ[s, :len(y)] = [b for _, b in y]; NP[s, :len(n)] = [a for a, _ in n]; NQ[s, :len(n)] = [b for _, b in n]
            YN[s] = len(y); NN[s] = len(n)
    return DS.frames(dict(T=T, P=P, C=C, S=S, YP=YP, YQ=YQ, NP=NP, NQ=NQ, YN=YN, NN=NN), result)
D = np.array([100]); n = 0
def check(name, got, want):
    global n; n += 1
    assert (np.isnan(want) and np.isnan(got)) or abs(got - want) < 1e-9, f"{name}: got {got}, want {want}"
Y = [(.50, 4000), (.49, 3000), (.48, 500), (.47, 900)]; N_ = [(.49, 2000), (.48, 1500)]      # UP bid 50c, ask 51c
# 1. the frame: best bid / ask, sizes at and below the best, 0 on an empty level, 0 above the best
up, dn = win([], {0: (Y, N_)})
check("best bid", up.bid[100], .50); check("best ask", up.ask[100], .51); check("down bid", dn.bid[100], .49); check("down ask", dn.ask[100], .50)
check("size 2c down", up.shown_bid[100, 480], 500); check("empty level", up.shown_bid[100, 460], 0); check("above best", up.shown_bid[100, 505], 0)
check("offered at 51c = DOWN bids at 49c", up.shown_off[100, 510], 2000); check("offered at 52c", up.shown_off[100, 520], 1500)
# 2. deeper than 15 recorded levels: unknown, so no order there
deep = [(round(.60 - i * .01, 2), 100) for i in range(15)]                                   # 60c .. 46c
up, dn = win([], {0: (deep, [(.39, 100)])}); check("below the recorded levels", up.shown_bid[100, 450], np.nan)
r = DS.patient(up, D, 5, 60); check("k=5 from 60c is 55c: recorded", float(r["live"][0]), 1.0)
up2, _ = win([], {0: ([(.60, 100)] + [(round(.50 - i * .01, 2), 100) for i in range(14)], [(.39, 100)])})   # levels 60c, then 50c .. 37c
check("gap level inside the range is empty, not unknown", up2.shown_bid[100, 550], 0)
# 3. bid 2c below (48c, 500 ahead). The size there falls to 40: at most 40 ahead. Then 50 trade at 48c: filled.
up, dn = win([(131, .48, 49, False), (133, .48, 1, False)],
             {0: (Y, N_), 120: ([(.50, 4000), (.49, 3000), (.48, 40), (.47, 900)], N_), 130: ([(.48, 40), (.47, 900)], [(.51, 2000)])})
r = DS.patient(up, D, 2, 60)
check("price", r["price"][0], .48); check("queue when placed", r["q0"][0], 500); check("fill second", r["fs"][0], 133)
check("ahead at the fill", r["ahead"][0], 0); check("not a through fill", float(r["through"][0]), 0.0)
check("without the wait it would not fill in 20 s", DS.patient(up, D, 2, 20)["fs"][0], -1)
check("still 500 ahead at a 10 s expiry", DS.patient(up, D, 2, 10)["ahead"][0], 500)
check("40 ahead once the book shows 40", DS.patient(up, D, 2, 25)["ahead"][0], 40)
# 4. a sale below the price fills at once, wherever the queue is
up, dn = win([(110, .47, 5, False)], {0: (Y, N_)}); r = DS.patient(up, D, 2, 60)
check("through fill second", r["fs"][0], 110); check("through flag", float(r["through"][0]), 1.0); check("ahead then", r["ahead"][0], 500)
# 5. an empty level: nothing ahead, the first 10 sold there fill it
up, dn = win([(105, .46, 10, False)], {0: (Y, N_)}); check("empty level fills on 10", DS.patient(up, D, 4, 60)["fs"][0], 105)
# 6. never past 30 s before the close; not live when the bid's price would be under 10c or the best bid over 90c
up, dn = win([(880, .48, 9000, False)], {0: (Y, N_)}); check("no fill after second 870", DS.patient(up, np.array([860]), 2, 60)["fs"][0], -1)
lo, _ = win([], {0: ([(.12, 100), (.11, 100)], [(.87, 100)])}); check("price under 10c", float(DS.patient(lo, D, 3, 60)["live"][0]), 0.0)
check("11c - 1c is allowed", float(DS.patient(lo, D, 2, 60)["live"][0]), 1.0)
# 7. exits. Filled at 48c in second 110. Offer 49c: the DOWN bids at 51c hold 2,000 -> 2,010 must be lifted there.
# (the 2,000 are there one second before the fill; anything that joins after it is behind us)
bk = {0: (Y, N_), 109: ([(.48, 4000), (.47, 900)], [(.51, 2000), (.50, 100)])}
up, dn = win([(110, .47, 5, False), (115, .49, 2010, True)], bk); r = DS.patient(up, D, 2, 60)
pnl, kind = DS.exit_scalp(up, r["price"], r["fs"]); check("target", pnl[0], 0.01); check("target kind", kind[0], R.T_TARGET)
up, dn = win([(110, .47, 5, False), (115, .49, 2009, True)], bk); r = DS.patient(up, D, 2, 60)
pnl, kind = DS.exit_scalp(up, r["price"], r["fs"]); check("one short: time-out at the 48c bid, fee ceil(17.47)=18c", pnl[0], -0.018); check("timeout kind", kind[0], R.T_TIMEOUT)
# the offer's queue shrinks with the book too: only 20 offered at 49c later, then 30 lifted
bk2 = dict(bk); bk2[113] = ([(.48, 4000), (.47, 900)], [(.51, 20), (.50, 100)])
up, dn = win([(110, .47, 5, False), (115, .49, 30, True)], bk2); r = DS.patient(up, D, 2, 60)
check("offer queue shrinks with the book", DS.exit_scalp(up, r["price"], r["fs"])[0][0], 0.01)
# stop: best bid 44c is 4c under the 48c fill -> sold at 44c, fee ceil(0.07*10*.44*.56*100)=18c
bk3 = {0: (Y, N_), 111: ([(.44, 500)], [(.55, 2000)])}
up, dn = win([(110, .47, 5, False)], bk3); r = DS.patient(up, D, 2, 60)
pnl, kind = DS.exit_scalp(up, r["price"], r["fs"]); check("stop", pnl[0], 0.44 - 0.48 - 0.018); check("stop kind", kind[0], R.T_STOP)
# offers that appear only after our fill are behind us: 10 lifted at 49c is enough
late = {0: (Y, N_), 111: ([(.48, 4000), (.47, 900)], [(.51, 2000), (.50, 100)])}
up, dn = win([(110, .47, 5, False), (115, .49, 10, True)], late); r = DS.patient(up, D, 2, 60)
check("later offers are behind us", DS.exit_scalp(up, r["price"], r["fs"])[0][0], 0.01)
# 8. the DOWN side mirrors: DOWN best bid 49c; 1c below is 48c with 1,500 ahead; takers buying UP at 53c sell DOWN at 47c
up, dn = win([(108, .53, 5, True)], {0: (Y, N_)}); r = DS.patient(dn, D, 1, 60)
check("down price", r["price"][0], .48); check("down queue", r["q0"][0], 1500); check("down through fill", r["fs"][0], 108)
print("ok", n, "checks")

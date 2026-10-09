# Clear lead — "bet the way Bitcoin is going" — 2026-10-09

Dirk's rule: the app is predicting whether Bitcoin finishes the 15 minutes up
or down, so watch the market and bet the way it is going.

Status: **in the app from 1.8.2 as the BET headline, a $5 ticket and a paper
record. A lead, not proof.** Not financial advice.

## Data

Every settled `KXBTC15M` window from 2026-08-24 to 2026-10-08: 4,264 windows,
46 days. Kalshi 1-minute quote candles, Coinbase BTC-USD 1-minute closes.
At minute *m* only what had already printed is used. Buy price is the ask.
Fee `0.07 × P × (1 − P)` per contract. Intervals are 95%, resampling whole days.
Tool: `tools/research/clear_lead_study.py`.

## 1. The way it is going is a good prediction, and the price already says so

Bet the side Bitcoin is on (above the start price → UP, below → DOWN):

| When | Wins | Paid | Net per contract | Per $5 bet |
|---|---:|---:|---|---:|
| Minute 3 | 67.4% | 65.4¢ | +0.47¢ [−0.77, +1.71] | +$0.03 |
| Minute 5 | 70.4% | 69.8¢ | −0.77¢ [−2.19, +0.68] | −$0.07 |
| Minute 8 | 77.7% | 76.5¢ | +0.10¢ [−1.08, +1.29] | +$0.00 |
| Minute 10 | 80.8% | 81.4¢ | −1.53¢ [−2.56, −0.53] | −$0.11 |
| Minute 12 | 82.8% | 84.5¢ | −2.46¢ [−3.59, −1.35] | −$0.16 |
| Minutes 3–12 | 75.7% | 75.2¢ | −0.63¢ [−1.44, +0.19] | −$0.05 |

## 2. Bitcoin does not keep going the way it was going

With no Kalshi prices involved: after a move over the last 1, 3 or 5 minutes,
the rest of the window went the same way 48.3–48.4% of the time (about 20,000
cases each). Following the recent move is a coin flip.

## 3. Where the rule comes out ahead

By how far ahead Bitcoin is at minute 8:

| Distance from the start price | n | Wins | Paid | Net per contract |
|---|---:|---:|---:|---|
| 0–2 bp | 762 | 59.4% | 56.5¢ | +1.22¢ [−3.02, +5.36] |
| 2–5 bp | 945 | 67.2% | 68.9¢ | −3.18¢ [−6.15, −0.37] |
| 5–10 bp | 1,042 | 79.8% | 78.8¢ | −0.21¢ [−2.88, +2.39] |
| **10–20 bp** | 993 | 90.5% | 87.5¢ | **+2.27¢ [+0.52, +3.87]** |
| 20 bp + | 516 | 95.2% | 93.9¢ | +0.86¢ [−1.87, +3.18] |

The rule in the app: the first time in a window that Bitcoin is 10–20 bp
(0.10–0.20%) from the start price between minute 3 and minute 10, buy that
side at the ask.

| Days | Bets | Wins | Paid | Net per contract | Per $5 bet |
|---|---:|---:|---:|---|---:|
| 2026-08-24 → 09-15 | 1,135 | 82.9% | 79.9¢ | +1.97¢ [+0.02, +3.93] | +$0.11 |
| 2026-09-16 → 10-08 | 1,192 | 84.1% | 82.2¢ | +0.85¢ [−1.29, +2.84] | +$0.06 |
| All 46 days | 2,327 | 83.5% | 81.1¢ | +1.40¢ [−0.06, +2.76] | +$0.08 |

## Why this is a lead and not proof

- The 10–20 bp band was chosen after looking at five bands. The neighbouring
  bands are flat or negative.
- The second half's interval includes zero, and so does the 46-day total.
- Spot here is Coinbase's 1-minute close. Kalshi settles on the 60-second
  average of the CF Benchmarks index.
- It agrees with two earlier findings: the favourite wins about 1.7 points more
  often than its price in minutes 3–8 (`claude/hit-rate-study-2026-10-02.md`),
  and cheap-side buyers lose the most (`docs/tape-study-2026-10-04.md`).
- The size of the claim is small. The rule fires in about 50 windows a day and
  the history says about +8¢ per $5 bet: about $4 a day if every one were
  taken. The $50 daily live cap allows about ten $5 buys, so under $1 a day at
  the shipped caps, before any of the caveats above.

## In the app (1.8.2)

- `ClearLeadRule` (signal/trend/ClearLead.kt): 10 ≤ |spot/strike − 1| < 20 bp,
  300–720 s left, side = the side spot is on. Spot is the CF Benchmarks index
  when the Live signals feed has it, otherwise Coinbase.
- The card says **BET UP / BET DOWN** while the rule is on, and Buy opens a $5
  all-in ticket on that side. Approve is still required. The rule does not use
  the model, so the sit-out switch does not silence it, and the $10
  min-profit-if-win gate does not apply to its side (these are 60–95¢ buys;
  that gate allows nothing above 31¢).
- Home card **Clear lead (your rule) · PAPER RECORD** logs the first call in
  every window as a $5 buy at the ask and settles it, whether or not a live
  ticket was approved. It needs Live signals on (or the app open) to see the
  window.
- Settings → **Clear lead rule** turns it off and resets the record.

Bar before trusting it: about 1,000 settled windows on the paper record with
the P&L per bet above zero and its interval clear of zero.

## Repro

```
python3 tools/research/clear_lead_study.py pull --days 45 --dir lead
python3 tools/research/clear_lead_study.py report --dir lead
```

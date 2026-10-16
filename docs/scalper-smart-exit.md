# Smart exit for the ML scalper (2026-10-13)

Question: should the ML scalper sell a filled scalp early when the price starts to turn down, instead of
waiting for its fixed exits (resting offer +1¢, sale at the bid 4¢ down, sale at the bid after 120 s)?

Data: the archived trade tape (`scalper-data` release, 1,474 KXBTC15M windows, 2026-09-24 … 10-09). Split as
the retrain (`SCALP_TEST_DAYS=7`): train 09-24 … 09-28, validation 09-29 … 10-02, test 10-03 … 10-09. Orders are
the shipped ML scalper's picks, front of the queue, cents per order posted (an unfilled order counts 0).

## How the fixed exits close (test days, filled scalps)

| Exit | share | average |
| --- | ---: | ---: |
| +1¢ offer filled | 91.7% | +1.00¢ |
| stopped at the bid | 8.3% | −6.59¢ |
| timed out | ~0% | |

Half of all scalps close within 1 s of the fill, 90% within 3 s, 99% within 22 s.

## A learned exit (`exit_ml.py`): no gain

A second LightGBM model looked at every open second of every scalp (the 23 grid features plus seconds held,
bid − entry, ask − entry, entry) and predicted how much more keeping the fixed exits makes than selling at the
bid now. On the test days it sold 982 of 102,267 orders early: +0.351¢ against +0.350¢ per order, a difference
of +0.001¢ (95% interval [−0.00, +0.00]). Not shipped. An early sale pays the taker fee (1.75¢ a contract at
50¢), and most scalps are gone within a second or two, so there is little to act on.

## Wider stops (`exit_sweep.py`): better on every day

The other way round paid. Giving the scalp more room before the stop:

| Stop / time-out | validation | test | test vs 4¢ / 120 s | worst order | orders losing > 10¢ |
| --- | ---: | ---: | --- | ---: | ---: |
| 4¢ / 120 s (shipped) | +0.266¢ | +0.350¢ | | −48¢ | 0.58% |
| 6¢ / 120 s | +0.326¢ | +0.403¢ | +0.053 [+0.039, +0.069], 7/7 days | −48¢ | 0.90% |
| 10¢ / 120 s | +0.361¢ | +0.443¢ | +0.093 [+0.066, +0.123], 7/7 days | −48¢ | 3.87% |
| 20¢ / 120 s | +0.400¢ | +0.465¢ | +0.115 [+0.085, +0.151], 7/7 days | −48¢ | 2.37% |
| no stop / 120 s | +0.403¢ | +0.468¢ | +0.118 [+0.085, +0.151], 7/7 days | −87¢ | 2.11% |

Most dips that hit a 4¢ stop come back to the +1¢ offer within two minutes, and selling into them locks in the dip and pays the taker fee
on top. A wider stop wins more often and loses bigger when it loses (the tail column). Paper, front of the
queue; the queue penalty was fitted with the 4¢ stop.

Rerun: `python3 -m exit_ml <tape> <scalper_model.json> 4` and `python3 -m exit_sweep <tape> <scalper_model.json>`
with `SCALP_TEST_DAYS=7` and `tools/research/scalp` on `PYTHONPATH`.

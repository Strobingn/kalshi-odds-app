package com.dirk.kalshiodds.decision

/**
 * 0.3.50 Scalp tab: the SIX paper scalp models that run side by side on every BTC/ETH/SOL 15-minute window, each with
 * its own bankroll slice and ledger, plus the pure leaderboard / per-window / ladder math the tab renders.
 * PAPER ONLY — no model has an order path; no LLM is in the decision path.
 */
object ScalpModels {
    enum class Model(val code: String, val label: String, val blurb: String) {
        FAIR_GAP("fair", "Fair-gap", ScalpStrategy.FAIR_GAP.blurb),
        DIP_HUNTER("dip", "Dip-hunter", ScalpStrategy.DIP_HUNTER.blurb),
        MOMENTUM("momo", "Momentum-sniper", ScalpStrategy.MOMENTUM.blurb),
        EXTREME_REVERSION("xrev", "Extreme-reversion", ScalpStrategy.EXTREME_REVERSION.blurb),
        CF_REPRICE("cfr", "CF-reprice", ScalpStrategy.CF_REPRICE.blurb),
        MAKER_DIP("mkdip", "Maker dip-rebound", "maker-first: post a bid after a fast ask drop (fee $0 maker), sell the rebound resting at the ask; queue-modelled fills"),
        /** 0.3.53: buy the side the short-horizon forecaster says will rise, near 50¢; exit when the forecast turns. */
        FORECAST_RIDE("fcride", "Forecast ride", "near 50¢ (40–60¢), buy the side the deterministic short-horizon forecast says rises by more than spread + both fees; hold while it keeps pointing up"),
        /** 0.3.53: exact mirror of Forecast ride — trades against the forecast with the same band, exits, sizing and fees. */
        FORECAST_FADE("fcfade", "Forecast fade", "mirror of Forecast ride: takes the side the forecast says falls. Entry band 40–49¢ unless a price slider is set. Paper only."),
        /** 0.3.61 siblings: paper copies next to the unchanged originals. */
        DIP_HOLD("diph60", "Dip-hunter hold", "Dip-hunter entries; stop armed after 60 s; fair-value early exit off; hold to settlement"),
        MOMO_HOLD("momoh60", "Momentum-sniper hold", "Momentum-sniper entries; stop armed after 60 s; fair-value early exit off; hold to settlement"),
        /** 0.3.63 queued copies. */
        FAIR_HOLD("fairh60", "Fair-gap hold", "Fair-gap entries; stop armed after 60 s; fair-value early exit off; hold to settlement"),
        DIP_NOTP("dipnotp", "Dip-hunter no TP", "Dip-hunter entries and stop; no early take-profit target"),
        MOMO_NOTP("momonotp", "Momentum-sniper no TP", "Momentum-sniper entries and stop; no early take-profit target"),
        // 0.3.57 sf57: twenty shock-lag / late-favourite paper scalpers (see ShockFav).
        SF01("sf01", "sf01 " + ShockFav.VARIANTS[0].label, ShockFav.VARIANTS[0].summary()),
        SF02("sf02", "sf02 " + ShockFav.VARIANTS[1].label, ShockFav.VARIANTS[1].summary()),
        SF03("sf03", "sf03 " + ShockFav.VARIANTS[2].label, ShockFav.VARIANTS[2].summary()),
        SF04("sf04", "sf04 " + ShockFav.VARIANTS[3].label, ShockFav.VARIANTS[3].summary()),
        SF05("sf05", "sf05 " + ShockFav.VARIANTS[4].label, ShockFav.VARIANTS[4].summary()),
        SF06("sf06", "sf06 " + ShockFav.VARIANTS[5].label, ShockFav.VARIANTS[5].summary()),
        SF07("sf07", "sf07 " + ShockFav.VARIANTS[6].label, ShockFav.VARIANTS[6].summary()),
        SF08("sf08", "sf08 " + ShockFav.VARIANTS[7].label, ShockFav.VARIANTS[7].summary()),
        SF09("sf09", "sf09 " + ShockFav.VARIANTS[8].label, ShockFav.VARIANTS[8].summary()),
        SF10("sf10", "sf10 " + ShockFav.VARIANTS[9].label, ShockFav.VARIANTS[9].summary()),
        SF11("sf11", "sf11 " + ShockFav.VARIANTS[10].label, ShockFav.VARIANTS[10].summary()),
        SF12("sf12", "sf12 " + ShockFav.VARIANTS[11].label, ShockFav.VARIANTS[11].summary()),
        SF13("sf13", "sf13 " + ShockFav.VARIANTS[12].label, ShockFav.VARIANTS[12].summary()),
        SF14("sf14", "sf14 " + ShockFav.VARIANTS[13].label, ShockFav.VARIANTS[13].summary()),
        SF15("sf15", "sf15 " + ShockFav.VARIANTS[14].label, ShockFav.VARIANTS[14].summary()),
        SF16("sf16", "sf16 " + ShockFav.VARIANTS[15].label, ShockFav.VARIANTS[15].summary()),
        SF17("sf17", "sf17 " + ShockFav.VARIANTS[16].label, ShockFav.VARIANTS[16].summary()),
        SF18("sf18", "sf18 " + ShockFav.VARIANTS[17].label, ShockFav.VARIANTS[17].summary()),
        SF19("sf19", "sf19 " + ShockFav.VARIANTS[18].label, ShockFav.VARIANTS[18].summary()),
        SF20("sf20", "sf20 " + ShockFav.VARIANTS[19].label, ShockFav.VARIANTS[19].summary()),
        SF21("sf21", "sf21 " + ShockFav.VARIANTS[20].label, ShockFav.VARIANTS[20].summary()),
        SF22("sf22", "sf22 " + ShockFav.VARIANTS[21].label, ShockFav.VARIANTS[21].summary()),
        SF23("sf23", "sf23 " + ShockFav.VARIANTS[22].label, ShockFav.VARIANTS[22].summary()),
        SF24("sf24", "sf24 " + ShockFav.VARIANTS[23].label, ShockFav.VARIANTS[23].summary()),
        SF27("sf27", "sf27 " + ShockFav.VARIANTS[24].label, ShockFav.VARIANTS[24].summary()),
        SF28("sf28", "sf28 " + ShockFav.VARIANTS[25].label, ShockFav.VARIANTS[25].summary()),
        SF29("sf29", "sf29 " + ShockFav.VARIANTS[26].label, ShockFav.VARIANTS[26].summary()),
        SF30("sf30", "sf30 " + ShockFav.VARIANTS[27].label, ShockFav.VARIANTS[27].summary()),
        SF31("sf31", "sf31 " + ShockFav.VARIANTS[28].label, ShockFav.VARIANTS[28].summary()),
        SF32("sf32", "sf32 " + ShockFav.v("sf32").label, ShockFav.v("sf32").summary()),
        SF41("sf41", "Simple · sf41 " + ShockFav.v("sf41").label, ShockFav.v("sf41").summary()),
        SF42("sf42", "Simple · sf42 " + ShockFav.v("sf42").label, ShockFav.v("sf42").summary()),
        SF43("sf43", "Simple · sf43 " + ShockFav.v("sf43").label, ShockFav.v("sf43").summary()),
        SF44("sf44", "Simple · sf44 " + ShockFav.v("sf44").label, ShockFav.v("sf44").summary()),
        SF51("sf51", "Open 50 · sf51 " + ShockFav.v("sf51").label, ShockFav.v("sf51").summary()),
        SF52("sf52", "Open 50 · sf52 " + ShockFav.v("sf52").label, ShockFav.v("sf52").summary()),
        SF53("sf53", "Open 50 · sf53 " + ShockFav.v("sf53").label, ShockFav.v("sf53").summary()),
        SF54("sf54", "Open 50 · sf54 " + ShockFav.v("sf54").label, ShockFav.v("sf54").summary()),
        SF55("sf55", "Open 50 · sf55 " + ShockFav.v("sf55").label, ShockFav.v("sf55").summary()),
        SF56("sf56", "Open 50 · sf56 " + ShockFav.v("sf56").label, ShockFav.v("sf56").summary()),
        SF57("sf57", "Open 50 · sf57 " + ShockFav.v("sf57").label, ShockFav.v("sf57").summary()),
        SF58("sf58", "Open 50 · sf58 " + ShockFav.v("sf58").label, ShockFav.v("sf58").summary()),
        SF59("sf59", "Open 50 · sf59 " + ShockFav.v("sf59").label, ShockFav.v("sf59").summary()),
        SF60("sf60", "Open 50 · sf60 " + ShockFav.v("sf60").label, ShockFav.v("sf60").summary()),
        SF61("sf61", "Open 50 · sf61 " + ShockFav.v("sf61").label, ShockFav.v("sf61").summary()),
        // 0.3.65 v2 copies (group 'v2'): same entries as the original, ask ≤ 50¢, original's stop, sell only once net gain ≥ stop then 2¢ off peak.
        V2_FAIR_GAP("v2-fair", "v2 · Fair-gap", V2_BLURB),
        V2_DIP_HUNTER("v2-dip", "v2 · Dip-hunter", V2_BLURB),
        V2_MOMENTUM("v2-momo", "v2 · Momentum-sniper", V2_BLURB),
        V2_EXTREME_REVERSION("v2-xrev", "v2 · Extreme-reversion", V2_BLURB),
        V2_CF_REPRICE("v2-cfr", "v2 · CF-reprice", V2_BLURB),
        V2_MAKER_DIP("v2-mkdip", "v2 · Maker dip-rebound", V2_BLURB),
        V2_FORECAST_RIDE("v2-fcride", "v2 · Forecast ride", V2_BLURB),
        /** Kalshi Scalper: the one LLM paper strategy (needs an API key in Settings). Same hard rules, enforced in code. */
        /** Kalshi Scalper 1.0.2 Research strategies: exempt from the 3¢ trail / window-start / >95% rules. BTC, paper only. */
        R_LATE("r-late", "Research · Late favourite settle", "Last 3–5 min, buys 80–95¢ when BRTI settlement-aware fair beats price by 10–21¢ after the taker fee; holds to settlement. Contracts and re-entries per 15-minute window are unlimited unless you set a limit; each entry still needs a fresh trigger. A price slider replaces the band. Size is unchanged. Paper only."),
        R_HOLD("r-hold", "Research · Fair-value hold", "Buys whenever fair value beats price after entry fee and expected exit fee by the edge; sells only if bid beats fair + exit fee or fair falls below entry − fee; else holds to settlement."),
        R_MAKER("r-maker", "Research · Passive maker entry", "Rests a paper bid at fair − edge; fills only when the market trades through; logs 5 s / 30 s drift after the fill; holds to settlement."),
        R_KELLY("r-kelly", "Research · Kelly-sized fair value", "Same entry gate and exits as Fair-value hold; size = quarter-Kelly (p − c)/(1 − c) of a reference bankroll, capped per window."),
        V2_LLM("v2-llm", "LLM · paper", "One LLM call per BTC window at the window start proposes YES/NO + confidence; code enforces ask ≤ 55¢, 3¢ peak trail and the > 95% hold gate. Paper only."),
        V2_FORECAST_FADE("v2-fcfade", "v2 · Forecast fade", V2_BLURB),
        V2_DIP_HOLD("v2-diph60", "v2 · Dip-hunter hold", V2_BLURB),
        V2_MOMO_HOLD("v2-momoh60", "v2 · Momentum-sniper hold", V2_BLURB),
        V2_FAIR_HOLD("v2-fairh60", "v2 · Fair-gap hold", V2_BLURB),
        V2_DIP_NOTP("v2-dipnotp", "v2 · Dip-hunter no TP", V2_BLURB),
        V2_MOMO_NOTP("v2-momonotp", "v2 · Momentum-sniper no TP", V2_BLURB),
        V2_SF01("v2-sf01", "v2 · sf01 " + ShockFav.v("sf01").label, V2_BLURB),
        V2_SF02("v2-sf02", "v2 · sf02 " + ShockFav.v("sf02").label, V2_BLURB),
        V2_SF03("v2-sf03", "v2 · sf03 " + ShockFav.v("sf03").label, V2_BLURB),
        V2_SF04("v2-sf04", "v2 · sf04 " + ShockFav.v("sf04").label, V2_BLURB),
        V2_SF05("v2-sf05", "v2 · sf05 " + ShockFav.v("sf05").label, V2_BLURB),
        V2_SF06("v2-sf06", "v2 · sf06 " + ShockFav.v("sf06").label, V2_BLURB),
        V2_SF07("v2-sf07", "v2 · sf07 " + ShockFav.v("sf07").label, V2_BLURB),
        V2_SF08("v2-sf08", "v2 · sf08 " + ShockFav.v("sf08").label, V2_BLURB),
        V2_SF09("v2-sf09", "v2 · sf09 " + ShockFav.v("sf09").label, V2_BLURB),
        V2_SF10("v2-sf10", "v2 · sf10 " + ShockFav.v("sf10").label, V2_BLURB),
        V2_SF11("v2-sf11", "v2 · sf11 " + ShockFav.v("sf11").label, V2_BLURB),
        V2_SF12("v2-sf12", "v2 · sf12 " + ShockFav.v("sf12").label, V2_BLURB),
        V2_SF13("v2-sf13", "v2 · sf13 " + ShockFav.v("sf13").label, V2_BLURB),
        V2_SF14("v2-sf14", "v2 · sf14 " + ShockFav.v("sf14").label, V2_BLURB),
        V2_SF15("v2-sf15", "v2 · sf15 " + ShockFav.v("sf15").label, V2_BLURB),
        V2_SF16("v2-sf16", "v2 · sf16 " + ShockFav.v("sf16").label, V2_BLURB),
        V2_SF17("v2-sf17", "v2 · sf17 " + ShockFav.v("sf17").label, V2_BLURB),
        V2_SF18("v2-sf18", "v2 · sf18 " + ShockFav.v("sf18").label, V2_BLURB),
        V2_SF19("v2-sf19", "v2 · sf19 " + ShockFav.v("sf19").label, V2_BLURB),
        V2_SF20("v2-sf20", "v2 · sf20 " + ShockFav.v("sf20").label, V2_BLURB),
        V2_SF21("v2-sf21", "v2 · sf21 " + ShockFav.v("sf21").label, V2_BLURB),
        V2_SF22("v2-sf22", "v2 · sf22 " + ShockFav.v("sf22").label, V2_BLURB),
        V2_SF23("v2-sf23", "v2 · sf23 " + ShockFav.v("sf23").label, V2_BLURB),
        V2_SF24("v2-sf24", "v2 · sf24 " + ShockFav.v("sf24").label, V2_BLURB),
        V2_SF27("v2-sf27", "v2 · sf27 " + ShockFav.v("sf27").label, V2_BLURB),
        V2_SF28("v2-sf28", "v2 · sf28 " + ShockFav.v("sf28").label, V2_BLURB),
        V2_SF30("v2-sf30", "v2 · sf30 " + ShockFav.v("sf30").label, V2_BLURB),
        V2_SF31("v2-sf31", "v2 · sf31 " + ShockFav.v("sf31").label, V2_BLURB),
        V2_SF32("v2-sf32", "v2 · sf32 " + ShockFav.v("sf32").label, V2_BLURB),
        V2_SF41("v2-sf41", "v2 · sf41 " + ShockFav.v("sf41").label, V2_BLURB),
        V2_SF42("v2-sf42", "v2 · sf42 " + ShockFav.v("sf42").label, V2_BLURB),
        V2_SF43("v2-sf43", "v2 · sf43 " + ShockFav.v("sf43").label, V2_BLURB),
        V2_SF44("v2-sf44", "v2 · sf44 " + ShockFav.v("sf44").label, V2_BLURB),
        V2_SF51("v2-sf51", "v2 · sf51 " + ShockFav.v("sf51").label, V2_BLURB),
        V2_SF52("v2-sf52", "v2 · sf52 " + ShockFav.v("sf52").label, V2_BLURB),
        V2_SF53("v2-sf53", "v2 · sf53 " + ShockFav.v("sf53").label, V2_BLURB),
        V2_SF54("v2-sf54", "v2 · sf54 " + ShockFav.v("sf54").label, V2_BLURB),
        V2_SF55("v2-sf55", "v2 · sf55 " + ShockFav.v("sf55").label, V2_BLURB),
        V2_SF56("v2-sf56", "v2 · sf56 " + ShockFav.v("sf56").label, V2_BLURB),
        V2_SF57("v2-sf57", "v2 · sf57 " + ShockFav.v("sf57").label, V2_BLURB),
        V2_SF58("v2-sf58", "v2 · sf58 " + ShockFav.v("sf58").label, V2_BLURB),
        V2_SF59("v2-sf59", "v2 · sf59 " + ShockFav.v("sf59").label, V2_BLURB),
        V2_SF60("v2-sf60", "v2 · sf60 " + ShockFav.v("sf60").label, V2_BLURB),
        V2_SF61("v2-sf61", "v2 · sf61 " + ShockFav.v("sf61").label, V2_BLURB),
        // 0.3.68: tweak + size copies of v2 sf41–44 (see ScalpV2.EXTRA_SPECS).
        V2_SF41T1("v2-sf41t1", "v2 · sf41 tweak 1 · " + ScalpV2.spec("v2-sf41t1").let { s -> if (s.walk) "${s.clip} contracts, book-walk fills" else "ask ≤ ${Math.round(s.maxAsk * 100)}¢" }, V2_BLURB),
        V2_SF41T2("v2-sf41t2", "v2 · sf41 tweak 2 · " + ScalpV2.spec("v2-sf41t2").let { s -> if (s.walk) "${s.clip} contracts, book-walk fills" else "ask ≤ ${Math.round(s.maxAsk * 100)}¢" }, V2_BLURB),
        V2_SF41X5("v2-sf41x5", "v2 · sf41 5 contracts · " + ScalpV2.spec("v2-sf41x5").let { s -> if (s.walk) "${s.clip} contracts, book-walk fills" else "ask ≤ ${Math.round(s.maxAsk * 100)}¢" }, V2_BLURB),
        V2_SF41X10("v2-sf41x10", "v2 · sf41 10 contracts · " + ScalpV2.spec("v2-sf41x10").let { s -> if (s.walk) "${s.clip} contracts, book-walk fills" else "ask ≤ ${Math.round(s.maxAsk * 100)}¢" }, V2_BLURB),
        V2_SF42T1("v2-sf42t1", "v2 · sf42 tweak 1 · " + ScalpV2.spec("v2-sf42t1").let { s -> if (s.walk) "${s.clip} contracts, book-walk fills" else "ask ≤ ${Math.round(s.maxAsk * 100)}¢" }, V2_BLURB),
        V2_SF42T2("v2-sf42t2", "v2 · sf42 tweak 2 · " + ScalpV2.spec("v2-sf42t2").let { s -> if (s.walk) "${s.clip} contracts, book-walk fills" else "ask ≤ ${Math.round(s.maxAsk * 100)}¢" }, V2_BLURB),
        V2_SF42X5("v2-sf42x5", "v2 · sf42 5 contracts · " + ScalpV2.spec("v2-sf42x5").let { s -> if (s.walk) "${s.clip} contracts, book-walk fills" else "ask ≤ ${Math.round(s.maxAsk * 100)}¢" }, V2_BLURB),
        V2_SF42X10("v2-sf42x10", "v2 · sf42 10 contracts · " + ScalpV2.spec("v2-sf42x10").let { s -> if (s.walk) "${s.clip} contracts, book-walk fills" else "ask ≤ ${Math.round(s.maxAsk * 100)}¢" }, V2_BLURB),
        V2_SF43T1("v2-sf43t1", "v2 · sf43 tweak 1 · " + ScalpV2.spec("v2-sf43t1").let { s -> if (s.walk) "${s.clip} contracts, book-walk fills" else "ask ≤ ${Math.round(s.maxAsk * 100)}¢" }, V2_BLURB),
        V2_SF43T2("v2-sf43t2", "v2 · sf43 tweak 2 · " + ScalpV2.spec("v2-sf43t2").let { s -> if (s.walk) "${s.clip} contracts, book-walk fills" else "ask ≤ ${Math.round(s.maxAsk * 100)}¢" }, V2_BLURB),
        V2_SF43X5("v2-sf43x5", "v2 · sf43 5 contracts · " + ScalpV2.spec("v2-sf43x5").let { s -> if (s.walk) "${s.clip} contracts, book-walk fills" else "ask ≤ ${Math.round(s.maxAsk * 100)}¢" }, V2_BLURB),
        V2_SF43X10("v2-sf43x10", "v2 · sf43 10 contracts · " + ScalpV2.spec("v2-sf43x10").let { s -> if (s.walk) "${s.clip} contracts, book-walk fills" else "ask ≤ ${Math.round(s.maxAsk * 100)}¢" }, V2_BLURB),
        V2_SF44T1("v2-sf44t1", "v2 · sf44 tweak 1 · " + ScalpV2.spec("v2-sf44t1").let { s -> if (s.walk) "${s.clip} contracts, book-walk fills" else "ask ≤ ${Math.round(s.maxAsk * 100)}¢" }, V2_BLURB),
        V2_SF44T2("v2-sf44t2", "v2 · sf44 tweak 2 · " + ScalpV2.spec("v2-sf44t2").let { s -> if (s.walk) "${s.clip} contracts, book-walk fills" else "ask ≤ ${Math.round(s.maxAsk * 100)}¢" }, V2_BLURB),
        V2_SF44X5("v2-sf44x5", "v2 · sf44 5 contracts · " + ScalpV2.spec("v2-sf44x5").let { s -> if (s.walk) "${s.clip} contracts, book-walk fills" else "ask ≤ ${Math.round(s.maxAsk * 100)}¢" }, V2_BLURB),
        V2_SF44X10("v2-sf44x10", "v2 · sf44 10 contracts · " + ScalpV2.spec("v2-sf44x10").let { s -> if (s.walk) "${s.clip} contracts, book-walk fills" else "ask ≤ ${Math.round(s.maxAsk * 100)}¢" }, V2_BLURB),
        // 1.0.14: tweaked paper copies of the two money makers (own ids, own cards).
        FAIR_T1("fair-t1", "Fair-gap tweak 1 · gap 12¢ · TP +10¢", "Fair-gap entries at gap ≥ 12¢ after fee (original 14¢), take profit +10¢ (original +12¢), stop 8¢, turn-down 15¢, from 5 min left. Paper only."),
        FAIR_T2("fair-t2", "Fair-gap tweak 2 · gap 16¢ · TP +14¢", "Fair-gap entries at gap ≥ 16¢ after fee (original 14¢), take profit +14¢ (original +12¢), stop 8¢, turn-down 15¢, from 5 min left. Paper only."),
        R_LATE_T1("r-late-t1", "Research · Late favourite tight", "r-late copy, tighter: 3–4 min left, 26–42¢, BRTI fair beats price by 12–21¢ after the taker fee; holds to settlement. Re-entries per 15-minute window are unlimited unless you set a limit; each entry still needs a fresh trigger. Paper only."),
        R_LATE_T2("r-late-t2", "Research · Late favourite loose", "r-late copy: 3–6 min left, 22–42¢, BRTI fair beats price by 10–23¢ after the taker fee; holds to settlement. Re-entries per 15-minute window are unlimited unless you set a limit; each entry still needs a fresh trigger. Paper only."),
        /** 10-21 ranking: recorded variant ids that are not an older model code. Only these twelve run. */
        G14("g14-t12-s08-d15-w300", "Fair-gap", "Fair-gap gap ≥ 14¢, target +12¢, stop 8¢, turn-down 15¢, from 5 min left. Paper only."),
        SF16F("sf16f", "sf16 Late fav base", ShockFav.v("sf16").summary()),
        SF17F("sf17f", "sf17 Late fav 60s-avg fair", ShockFav.v("sf17").summary()),
        V2_G10_MK("v2-g10-t12-s08-d15-w300-mk", "v2 · Fair-gap", "v2 exits on maker fair-gap g10-t12-s08-d15-w300. Paper only."),
        V2_DIP_SEED("v2-dip-g05-t08-s06-d10-w180", "v2 · Dip-hunter", "v2 exits on dip-hunter dip-g05-t08-s06-d10-w180. Paper only."),
        FCFADE_H600("fcfade-h600", "Forecast fade", "Forecast fade, horizon 600s, entry band 40–49¢. Paper only."),
        SF41_V1("sf41-v1", ScalpVariants.label("sf41-v1"), ScalpVariants.blurb("sf41-v1")),
        SF41_V2("sf41-v2", ScalpVariants.label("sf41-v2"), ScalpVariants.blurb("sf41-v2")),
        SF41_V3("sf41-v3", ScalpVariants.label("sf41-v3"), ScalpVariants.blurb("sf41-v3")),
        SF42_V1("sf42-v1", ScalpVariants.label("sf42-v1"), ScalpVariants.blurb("sf42-v1")),
        SF42_V2("sf42-v2", ScalpVariants.label("sf42-v2"), ScalpVariants.blurb("sf42-v2")),
        SF42_V3("sf42-v3", ScalpVariants.label("sf42-v3"), ScalpVariants.blurb("sf42-v3")),
        SF43_V1("sf43-v1", ScalpVariants.label("sf43-v1"), ScalpVariants.blurb("sf43-v1")),
        SF43_V2("sf43-v2", ScalpVariants.label("sf43-v2"), ScalpVariants.blurb("sf43-v2")),
        SF43_V3("sf43-v3", ScalpVariants.label("sf43-v3"), ScalpVariants.blurb("sf43-v3")),
        SF04_V1("sf04-v1", ScalpVariants.label("sf04-v1"), ScalpVariants.blurb("sf04-v1")),
        SF04_V2("sf04-v2", ScalpVariants.label("sf04-v2"), ScalpVariants.blurb("sf04-v2")),
        SF04_V3("sf04-v3", ScalpVariants.label("sf04-v3"), ScalpVariants.blurb("sf04-v3")),
        SF07_V1("sf07-v1", ScalpVariants.label("sf07-v1"), ScalpVariants.blurb("sf07-v1")),
        SF07_V2("sf07-v2", ScalpVariants.label("sf07-v2"), ScalpVariants.blurb("sf07-v2")),
        SF07_V3("sf07-v3", ScalpVariants.label("sf07-v3"), ScalpVariants.blurb("sf07-v3")),
        SF08_V1("sf08-v1", ScalpVariants.label("sf08-v1"), ScalpVariants.blurb("sf08-v1")),
        SF08_V2("sf08-v2", ScalpVariants.label("sf08-v2"), ScalpVariants.blurb("sf08-v2")),
        SF08_V3("sf08-v3", ScalpVariants.label("sf08-v3"), ScalpVariants.blurb("sf08-v3")),
        SF15_V1("sf15-v1", ScalpVariants.label("sf15-v1"), ScalpVariants.blurb("sf15-v1")),
        SF15_V2("sf15-v2", ScalpVariants.label("sf15-v2"), ScalpVariants.blurb("sf15-v2")),
        SF15_V3("sf15-v3", ScalpVariants.label("sf15-v3"), ScalpVariants.blurb("sf15-v3")),
        SF16F_V1("sf16f-v1", ScalpVariants.label("sf16f-v1"), ScalpVariants.blurb("sf16f-v1")),
        SF16F_V2("sf16f-v2", ScalpVariants.label("sf16f-v2"), ScalpVariants.blurb("sf16f-v2")),
        SF16F_V3("sf16f-v3", ScalpVariants.label("sf16f-v3"), ScalpVariants.blurb("sf16f-v3")),
        SF17F_V1("sf17f-v1", ScalpVariants.label("sf17f-v1"), ScalpVariants.blurb("sf17f-v1")),
        SF17F_V2("sf17f-v2", ScalpVariants.label("sf17f-v2"), ScalpVariants.blurb("sf17f-v2")),
        SF17F_V3("sf17f-v3", ScalpVariants.label("sf17f-v3"), ScalpVariants.blurb("sf17f-v3")),
        G14_T12_S08_D15_W300_V1("g14-t12-s08-d15-w300-v1", ScalpVariants.label("g14-t12-s08-d15-w300-v1"), ScalpVariants.blurb("g14-t12-s08-d15-w300-v1")),
        G14_T12_S08_D15_W300_V2("g14-t12-s08-d15-w300-v2", ScalpVariants.label("g14-t12-s08-d15-w300-v2"), ScalpVariants.blurb("g14-t12-s08-d15-w300-v2")),
        G14_T12_S08_D15_W300_V3("g14-t12-s08-d15-w300-v3", ScalpVariants.label("g14-t12-s08-d15-w300-v3"), ScalpVariants.blurb("g14-t12-s08-d15-w300-v3")),
        G16_T14_S08_D15_W300_V1("g16-t14-s08-d15-w300-v1", ScalpVariants.label("g16-t14-s08-d15-w300-v1"), ScalpVariants.blurb("g16-t14-s08-d15-w300-v1")),
        G16_T14_S08_D15_W300_V2("g16-t14-s08-d15-w300-v2", ScalpVariants.label("g16-t14-s08-d15-w300-v2"), ScalpVariants.blurb("g16-t14-s08-d15-w300-v2")),
        G16_T14_S08_D15_W300_V3("g16-t14-s08-d15-w300-v3", ScalpVariants.label("g16-t14-s08-d15-w300-v3"), ScalpVariants.blurb("g16-t14-s08-d15-w300-v3")),
        V2_G10_T12_S08_D15_W300_MK_V1("v2-g10-t12-s08-d15-w300-mk-v1", ScalpVariants.label("v2-g10-t12-s08-d15-w300-mk-v1"), ScalpVariants.blurb("v2-g10-t12-s08-d15-w300-mk-v1")),
        V2_G10_T12_S08_D15_W300_MK_V2("v2-g10-t12-s08-d15-w300-mk-v2", ScalpVariants.label("v2-g10-t12-s08-d15-w300-mk-v2"), ScalpVariants.blurb("v2-g10-t12-s08-d15-w300-mk-v2")),
        V2_G10_T12_S08_D15_W300_MK_V3("v2-g10-t12-s08-d15-w300-mk-v3", ScalpVariants.label("v2-g10-t12-s08-d15-w300-mk-v3"), ScalpVariants.blurb("v2-g10-t12-s08-d15-w300-mk-v3")),
        V2_DIP_G05_T08_S06_D10_W180_V1("v2-dip-g05-t08-s06-d10-w180-v1", ScalpVariants.label("v2-dip-g05-t08-s06-d10-w180-v1"), ScalpVariants.blurb("v2-dip-g05-t08-s06-d10-w180-v1")),
        V2_DIP_G05_T08_S06_D10_W180_V2("v2-dip-g05-t08-s06-d10-w180-v2", ScalpVariants.label("v2-dip-g05-t08-s06-d10-w180-v2"), ScalpVariants.blurb("v2-dip-g05-t08-s06-d10-w180-v2")),
        V2_DIP_G05_T08_S06_D10_W180_V3("v2-dip-g05-t08-s06-d10-w180-v3", ScalpVariants.label("v2-dip-g05-t08-s06-d10-w180-v3"), ScalpVariants.blurb("v2-dip-g05-t08-s06-d10-w180-v3")),
        DIP_G05_T08_S06_D10_W180_MK_V1("dip-g05-t08-s06-d10-w180-mk-v1", ScalpVariants.label("dip-g05-t08-s06-d10-w180-mk-v1"), ScalpVariants.blurb("dip-g05-t08-s06-d10-w180-mk-v1")),
        DIP_G05_T08_S06_D10_W180_MK_V2("dip-g05-t08-s06-d10-w180-mk-v2", ScalpVariants.label("dip-g05-t08-s06-d10-w180-mk-v2"), ScalpVariants.blurb("dip-g05-t08-s06-d10-w180-mk-v2")),
        DIP_G05_T08_S06_D10_W180_MK_V3("dip-g05-t08-s06-d10-w180-mk-v3", ScalpVariants.label("dip-g05-t08-s06-d10-w180-mk-v3"), ScalpVariants.blurb("dip-g05-t08-s06-d10-w180-mk-v3")),
        FCFADE_H600_V1("fcfade-h600-v1", ScalpVariants.label("fcfade-h600-v1"), ScalpVariants.blurb("fcfade-h600-v1")),
        FCFADE_H600_V2("fcfade-h600-v2", ScalpVariants.label("fcfade-h600-v2"), ScalpVariants.blurb("fcfade-h600-v2")),
        FCFADE_H600_V3("fcfade-h600-v3", ScalpVariants.label("fcfade-h600-v3"), ScalpVariants.blurb("fcfade-h600-v3")),
        DOS_MM("dos-mm", "DOS-MM", "HJB bounded binary market maker. Paper only."),
        TS_ARB("ts-arb", "TS-Arb", "Final-minute settlement variance. Paper only."),
        MRLA_CS("mrla-cs", "MRLA-CS", "Five-level OFI and Obizhaeva-Wang impact. Paper only."),
        IV_SMILE("iv-smile", "IV smile", "Options-implied vol smile. Paper only.")
    }

    const val V2_BLURB = "v2 paper: new trades default to a 3¢ bid-peak trail active from entry and independent 20% fill-price stop. Existing trades retain saved exits. Preserve positive/inconclusive entry settings; bounded adjustments for sustained losses. Every trade has a protective stop. Exit in the final minute unless fresh CF fair ≥ 97%, bid ≥ 90¢ and holding beats selling. Decisions are estimates, never guaranteed wins. Actual stop/trail and adaptation evidence are in the trade note."
    /**
     * 10-21: losers are off the board (not frozen, not hidden). History of those variant ids still loads
     * and is labeled [KeptScalpers.REMOVED_LABEL]. Stored rows are not deleted.
     * Order matches the ranking, best net first. At most [KeptScalpers.MAX_SLOTS]; empty slots stay empty.
     */
    val WINNERS: List<Model> = listOf(
        Model.SF42, Model.V2_G10_MK, Model.G14, Model.SF16F, Model.SF15, Model.SF17F,
        Model.V2_DIP_SEED, Model.SF07, Model.SF04, Model.SF08, Model.FCFADE_H600, Model.SF41
    )
    /** Kept winners, then paper nudges. Parents stay first and unchanged. */
    val HFT: List<Model> = listOf(Model.DOS_MM, Model.TS_ARB, Model.MRLA_CS, Model.IV_SMILE)
    /** Kept winners, then paper nudges, then the 1.0.37 HFT engines. */
    val ALL: List<Model> = WINNERS + Model.values().filter { ScalpVariants.isLive(it.code) } + HFT
    private val RAW_ALL: List<Model> = Model.values().toList()
    private val KEPT_CODES: Set<String> = ALL.map { it.code }.toSet()
    val REMOVED_CODES: Set<String> = RAW_ALL.map { it.code }.filter { it !in KEPT_CODES }.toSet()
    fun isRemoved(code: String?): Boolean = code != null && code !in KEPT_CODES && !TwentyScalperFleet.isFleet(code)
    /** 0.3.65: v2 copy of an original model (null when none, e.g. sf29 or a v2 itself). */
    fun v2Of(m: Model): Model? = V2_BY_ORIG[m]
    fun originalOf(v2: Model): Model? = ORIG_BY_CODE[ScalpV2.baseOf(v2.code)]?.takeIf { isV2(v2) }
    fun isV2(m: Model): Boolean = m.code.startsWith(ScalpV2.PREFIX)
    fun isResearch(m: Model): Boolean = Research.isResearch(m.code)
    /** Stable id for the incremental stats table (model code, or variantId when unknown). */
    fun scalperIdOf(t: ScalpTrade): String? = if (!t.isPrimary) null else (modelOf(t)?.code ?: t.variantId)
    fun dayEt(atMs: Long): String = java.time.Instant.ofEpochMilli(atMs).atZone(java.time.ZoneId.of("America/New_York")).toLocalDate().toString()
    fun modelByCode(code: String): Model? = BY_CODE[code]
    private val BY_CODE: Map<String, Model> by lazy { ALL.associateBy { it.code } }
    private val RAW_BY_CODE: Map<String, Model> by lazy { RAW_ALL.associateBy { it.code } }
    fun canonicalScalperId(id: String): String = KeptScalpers.canonical(id)
    /** Card label. Unknown and removed variant ids stay readable and do not throw. */
    fun labelFor(variantId: String): String =
        modelByCode(variantId)?.label ?: TwentyScalperFleet.labelOf(variantId) ?: KeptScalpers.REMOVED_LABEL
    private fun mergeStat(a: ScalpStat?, b: ScalpStat): ScalpStat {
        if (a == null) return b
        return a.copy(
            n = a.n + b.n, wins = a.wins + b.wins, netUsd = a.netUsd + b.netUsd, feesUsd = a.feesUsd + b.feesUsd,
            bestUsd = listOfNotNull(a.bestUsd, b.bestUsd).maxOrNull(),
            worstUsd = listOfNotNull(a.worstUsd, b.worstUsd).minOrNull(),
            sumSq = a.sumSq + b.sumSq,
            lastClosedAtMs = listOfNotNull(a.lastClosedAtMs, b.lastClosedAtMs).maxOrNull(),
            openN = a.openN + b.openN, sumWin = a.sumWin + b.sumWin, sumLoss = a.sumLoss + b.sumLoss,
            nLoss = a.nLoss + b.nLoss, negSum = a.negSum + b.negSum, negN = a.negN + b.negN,
            costSum = a.costSum + b.costSum, stopSavedSum = a.stopSavedSum + b.stopSavedSum,
            stopSavedN = a.stopSavedN + b.stopSavedN, noFills = a.noFills + b.noFills
        )
    }
    /** Fold legacy alias ids (sf16 → sf16f) into the kept card. Does not drop the source rows. */
    fun statsById(stats: List<ScalpStat>): Map<String, ScalpStat> {
        val out = LinkedHashMap<String, ScalpStat>()
        for (st in stats) {
            val id = canonicalScalperId(st.scalperId)
            out[id] = mergeStat(out[id], st.copy(scalperId = id))
        }
        return out
    }
    fun dailyById(rows: Collection<ScalpDaily>): Map<String, ScalpStat> =
        statsById(rows.map { it.asStat() })
    /** Build a Row from the incremental stats (+ exit counts). No trade scan, no bootstrap. */
    fun rowFromStat(st: ScalpStat, openN: Int = st.openN, exits: List<ScalpExitAgg> = emptyList(), unrealizedUsd: Double = 0.0): Row {
        val id = canonicalScalperId(st.scalperId)
        val m = modelByCode(id) ?: RAW_BY_CODE[st.scalperId] ?: ALL.first()
        val ci = st.perTradeCi()
        return Row(
            model = m, netUsd = st.netUsd, roundTrips = st.n, wins = st.wins,
            winRate = st.winRate, avgWinUsd = st.avgWin, avgLossUsd = st.avgLoss, roi = st.roi, roiCi = null,
            open = openN, unrealizedUsd = unrealizedUsd, noFills = st.noFills, grossWinUsd = st.sumWin, grossLossUsd = st.negSum,
            losses = st.negN, openContracts = 0, feesUsd = st.feesUsd,
            stopSavedUsd = st.stopSavedSum, stopsHit = st.stopSavedN, stopLabel = stopLabel(m),
            bestUsd = st.bestUsd, worstUsd = st.worstUsd,
            perTradeCi = ci?.let { ClusteredBootstrap.Ci(st.perTrade ?: 0.0, it.first, it.second, st.n, st.n) },
            exitReasons = exitBreakdownFrom(exits)
        )
    }
    fun leaderboardFromStats(stats: List<ScalpStat>, openBy: Map<String, Int> = emptyMap(), exits: List<ScalpExitAgg> = emptyList(),
                             unrealBy: Map<String, Double> = emptyMap()): List<Row> {
        val byId = statsById(stats)
        val ex = exits.groupBy { canonicalScalperId(it.scalperId) }
        return ALL.map { m ->
            val st = byId[m.code] ?: ScalpStat(m.code)
            rowFromStat(st, openN = openBy[m.code] ?: st.openN, exits = ex[m.code].orEmpty(), unrealizedUsd = unrealBy[m.code] ?: 0.0)
        }.sortedWith(compareByDescending<Row> { it.netUsd + it.unrealizedUsd }.thenByDescending { it.roundTrips })
    }
    val V2_MODELS: List<Model> get() = ALL.filter { isV2(it) }
    private val ORIG_BY_CODE: Map<String, Model> by lazy { RAW_ALL.filter { !isV2(it) }.associateBy { it.code } }
    private val V2_BY_ORIG: Map<Model, Model> by lazy { ALL.filter { isV2(it) && !ScalpV2.isExtra(it.code) }.mapNotNull { v -> ORIG_BY_CODE[v.code.removePrefix(ScalpV2.PREFIX)]?.let { it to v } }.toMap() }
    val COUNT: Int get() = ALL.size
    private val SF_BY_CODE: Map<String, Model> by lazy { RAW_ALL.filter { it.code.startsWith(ShockFav.PREFIX) }.associateBy { it.code } }
    val SF_MODELS: List<Model> get() = ALL.filter { it.code.startsWith(ShockFav.PREFIX) }
    /** 0.3.59: true for sf variants with no positive BTC 2 s-delay test CI. */
    fun noEdge2s(m: Model): Boolean = ShockFav.byVariantId(m.code)?.noEdge2s == true
    /** Card order: 2 s-delay survivors first, then legacy models, then no-edge sf variants (fixed order within groups). */
    val CARD_ORDER: List<Model> by lazy {
        ALL.sortedBy { m -> val p = ShockFav.byVariantId(m.code); when { p == null -> 1; !p.noEdge2s -> 0; else -> 2 } }
    }
    /** One combined leaderboard: survivors first, then by net. */
    fun combinedBoard(rows: Map<Model, Row>): List<Row> = ALL.mapNotNull { rows[it] }
        .sortedWith(compareBy<Row> { if (noEdge2s(it.model)) 1 else 0 }.thenByDescending { it.netUsd + it.unrealizedUsd }.thenByDescending { it.roundTrips })

    /** Sixth model: the dip-hunter seed run maker-first (resting bid entry, resting ask exit). */
    val MAKER_DIP_PARAMS: ScalpParams = ScalpParams.STRATEGY_SEEDS.getValue(ScalpStrategy.DIP_HUNTER).copy(maker = true)

    /** 0.3.54: no per-model split — every scalper draws from the whole shared free paper bankroll. */
    fun sliceUsd(bankrollUsd: Double): Double = (bankrollUsd.takeIf { it.isFinite() } ?: 0.0).coerceAtLeast(0.0)

    /** 1.0.14: fair-t1 / fair-t2 param ids → their own models. Kept ids win when the param id is one of the twelve. */
    private val FAIR_TWEAK_MODELS: Map<String, Model> by lazy { mapOf(ScalpParams.FAIR_TWEAKS[0].id to Model.FAIR_T1, ScalpParams.FAIR_TWEAKS[1].id to Model.FAIR_T2) }
    fun modelOf(p: ScalpParams): Model = BY_CODE[p.id] ?: FAIR_TWEAK_MODELS[p.id] ?:
        if (p.hold60 && p.strategy == ScalpStrategy.FAIR_GAP) Model.FAIR_HOLD
        else if (p.target >= ScalpParams.NO_TP - 1e-9) (if (p.strategy == ScalpStrategy.MOMENTUM) Model.MOMO_NOTP else Model.DIP_NOTP)
        else if (p.hold60) (if (p.strategy == ScalpStrategy.MOMENTUM) Model.MOMO_HOLD else Model.DIP_HOLD) else if (p.maker && p.strategy == ScalpStrategy.DIP_HUNTER) Model.MAKER_DIP else modelOf(p.strategy)

    fun modelOf(s: ScalpStrategy): Model = when (s) {
        ScalpStrategy.FAIR_GAP -> Model.FAIR_GAP
        ScalpStrategy.DIP_HUNTER -> Model.DIP_HUNTER
        ScalpStrategy.MOMENTUM -> Model.MOMENTUM
        ScalpStrategy.EXTREME_REVERSION -> Model.EXTREME_REVERSION
        ScalpStrategy.CF_REPRICE -> Model.CF_REPRICE
    }

    /** Model of a primary trade; null for shadow tuning variants. */
    fun modelOf(t: ScalpTrade): Model? {
        if (!t.isPrimary) return null
        // 0.3.66: no per-trade copy for v2 rows (was t.copy() per trade per board pass); cached per variant id.
        // 1.0.10: saved trades of removed strategies resolve to no model (kept in history, not shown/attributed).
        return MODEL_BY_VARIANT.getOrPut(t.variantId) { modelOfVariant(t.variantId) ?: NONE } as? Model
    }
    private val NONE = Any()
    private val MODEL_BY_VARIANT = java.util.concurrent.ConcurrentHashMap<String, Any>()
    /** Exact recorded variant id only. Other ids stay in history and are not attributed to a live card. */
    private fun modelOfVariant(vid: String): Model? = BY_CODE[vid]

    /** 0.3.61: per-bet stop shown on each card / leaderboard row. */
    fun stopLabel(m: Model): String {
        if (isV2(m)) return "stop per trade · default 20% fill stop + 3¢ peak trail; saved rules in trade note; 60s close check"
        if (m.code == KeptScalpers.FAIR_ID) return "stop 8¢"
        ShockFav.byVariantId(m.code)?.let { p -> return "stop ${ShockFav.stopC(p)}¢" + if (ShockFav.stopTrainChosen(p)) "" else " (default)" }
        return when (m) {
            Model.FORECAST_RIDE, Model.FORECAST_FADE, Model.FCFADE_H600 -> "stop ${ForecastScalp.STOP_C}¢ (default)"
            Model.DIP_HOLD -> "stop ${Math.round(ScalpParams.HOLD60_SIBLINGS[0].stop * 100)}¢ after 60s"
            Model.MOMO_HOLD -> "stop ${Math.round(ScalpParams.HOLD60_SIBLINGS[1].stop * 100)}¢ after 60s"
            Model.FAIR_GAP -> "stop per-coin tuned"
            Model.FAIR_HOLD -> "stop ${Math.round(ScalpParams.QUEUED_COPIES[0].stop * 100)}¢ after 60s"
            Model.DIP_NOTP -> "stop ${Math.round(ScalpParams.QUEUED_COPIES[1].stop * 100)}¢"
            Model.MOMO_NOTP -> "stop ${Math.round(ScalpParams.QUEUED_COPIES[2].stop * 100)}¢"
            Model.FAIR_T1 -> "stop ${Math.round(ScalpParams.FAIR_TWEAKS[0].stop * 100)}¢"
            Model.FAIR_T2 -> "stop ${Math.round(ScalpParams.FAIR_TWEAKS[1].stop * 100)}¢"
            Model.DOS_MM, Model.TS_ARB, Model.MRLA_CS, Model.IV_SMILE -> "stop 5-gate · paper"
            else -> ScalpModels.legacyStop(m)
        }
    }
    private fun legacyStop(m: Model): String = ScalpStrategy.values().firstOrNull { modelOf(it) == m }
        ?.let { "stop ${Math.round(ScalpParams.STRATEGY_SEEDS.getValue(it).stop * 100)}¢" } ?: "stop ${Math.round(MAKER_DIP_PARAMS.stop * 100)}¢"
    fun tag(m: Model): String? = (if (isV2(m)) "group: v2" else null) ?: ShockFav.TAGS[m.code] ?: ShockFav.byVariantId(m.code)?.group?.let { "group: $it" }
    /** Σ (actual − no-stop shadow) over closed trades whose note carries "nostop=±x.xx". Positive = the stop saved money. */
    fun stopSaved(trades: List<ScalpTrade>): Pair<Double, Int> {
        var s = 0.0; var n = 0
        for (t in trades) {
            if (t.state != ScalpState.CLOSED) continue
            val v = NOSTOP_RX.find(t.note)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            s += (t.netUsd ?: continue) - v; n++
        }
        return s to n
    }
    private val NOSTOP_RX = Regex("nostop=([+-]?[0-9]+\\.[0-9]+)")

    data class Row(
        val model: Model,
        val netUsd: Double,
        val roundTrips: Int,
        val wins: Int,
        val winRate: Double?,
        val avgWinUsd: Double?,
        val avgLossUsd: Double?,
        /** Net / entry cost over closed round trips. */
        val roi: Double?,
        val roiCi: ClusteredBootstrap.Ci?,
        val open: Int,
        val unrealizedUsd: Double,
        val noFills: Int,
        /** 0.3.52 algorithm cards. */
        val grossWinUsd: Double = 0.0,
        val grossLossUsd: Double = 0.0,
        val losses: Int = 0,
        val openContracts: Int = 0,
        val feesUsd: Double = 0.0,
        /** 0.3.61: Σ(actual − no-stop shadow) over stopped trades (+ = stop saved money) and how many stops fired. */
        val stopSavedUsd: Double = 0.0,
        val stopsHit: Int = 0,
        val stopLabel: String = "",
        /** 0.3.65: best / worst closed trade, $/trade CI (market-clustered), top exit reasons. */
        val bestUsd: Double? = null,
        val worstUsd: Double? = null,
        val perTradeCi: ClusteredBootstrap.Ci? = null,
        val exitReasons: String = ""
    ) {
        val holding: Boolean get() = openContracts > 0
    }

    fun row(model: Model, trades: List<ScalpTrade>, bidFor: (ScalpTrade) -> Double? = { null }, resamples: Int = 400): Row {
        val closed = trades.filter { it.state == ScalpState.CLOSED && it.netUsd != null && it.netUsd.isFinite() }
        val wins = closed.filter { it.netUsd!! > 0.0 }
        val losses = closed.filter { it.netUsd!! <= 0.0 }
        val cost = closed.sumOf { it.entryCostUsd }
        val net = closed.sumOf { it.netUsd!! }
        val active = trades.filter { it.state == ScalpState.OPEN || it.state == ScalpState.PENDING_EXIT || it.state == ScalpState.PENDING_ENTRY }
        val ci = if (resamples <= 0) null else ClusteredBootstrap.meanCi(
            closed.filter { it.entryCostUsd > 0.0 }.map { it.windowKey to it.netUsd!! / it.entryCostUsd },
            resamples = resamples
        )
        val ss = stopSaved(closed)
        return Row(
            model = model,
            netUsd = net,
            roundTrips = closed.size,
            wins = wins.size,
            winRate = if (closed.isEmpty()) null else wins.size.toDouble() / closed.size,
            avgWinUsd = wins.takeIf { it.isNotEmpty() }?.map { it.netUsd!! }?.average(),
            avgLossUsd = losses.takeIf { it.isNotEmpty() }?.map { it.netUsd!! }?.average(),
            roi = if (cost > 0.0) net / cost else null,
            roiCi = ci,
            open = active.size,
            unrealizedUsd = active.sumOf { t -> t.unrealizedUsd(bidFor(t))?.takeIf { it.isFinite() } ?: 0.0 },
            noFills = trades.count { it.state == ScalpState.NO_FILL },
            grossWinUsd = wins.sumOf { it.netUsd!! },
            grossLossUsd = losses.filter { it.netUsd!! < 0.0 }.sumOf { it.netUsd!! },
            losses = losses.count { it.netUsd!! < 0.0 },
            openContracts = active.filter { it.state != ScalpState.PENDING_ENTRY }.sumOf { it.remaining.coerceAtLeast(0) },
            feesUsd = closed.sumOf { it.entryFeeUsd + it.exitFeeUsd },
            stopSavedUsd = ss.first,
            stopsHit = ss.second,
            stopLabel = stopLabel(model),
            bestUsd = closed.maxOfOrNull { it.netUsd!! },
            worstUsd = closed.minOfOrNull { it.netUsd!! },
            perTradeCi = if (resamples <= 0) null else ClusteredBootstrap.meanCi(closed.map { it.windowKey to it.netUsd!! }, resamples = minOf(resamples, 200)),
            exitReasons = exitBreakdown(closed)
        )
    }

    private val EXIT_NUM_RX = Regex("[−+-]?\\d+(\\.\\d+)?[¢s%]?")
    /** "stop 40% · trail 30% · settled 20%" — top 4 exit reasons over closed trades. */
    fun normExit(r: String?): String = (r ?: "?").substringBefore(" → ").replace(EXIT_NUM_RX, "#").trim().take(28)
    /** 0.3.68: same string as [exitBreakdown], from the per-reason counts table. */
    fun exitBreakdownFrom(counts: List<ScalpExitAgg>): String {
        val total = counts.sumOf { it.n }
        if (total == 0) return ""
        return counts.sortedByDescending { it.n }.take(4).joinToString(" · ") { "${it.reason} ${Math.round(it.n * 100.0 / total)}%" }
    }
    fun exitBreakdown(closed: List<ScalpTrade>): String {
        if (closed.isEmpty()) return ""
        return closed.groupingBy { normExit(it.exitReason) }.eachCount().entries.sortedByDescending { it.value }.take(4)
            .joinToString(" · ") { "${it.key} ${Math.round(it.value * 100.0 / closed.size)}%" }
    }

    /** 0.3.65 v2 vs original comparison rows (all v2 models, original alongside). */
    fun v2Compare(rows: Map<Model, Row>): List<ScalpV2.Compare> = V2_MODELS.mapNotNull { v ->
        val o = originalOf(v) ?: return@mapNotNull null
        val vr = rows[v] ?: row(v, emptyList(), resamples = 0)
        val orr = rows[o] ?: row(o, emptyList(), resamples = 0)
        val vpt = if (vr.roundTrips > 0) vr.netUsd / vr.roundTrips else 0.0
        val opt = if (orr.roundTrips > 0) orr.netUsd / orr.roundTrips else 0.0
        ScalpV2.Compare(v, o, vr, orr, ScalpV2.status(vr.roundTrips, vpt, opt))
    }

    /** Leaderboard (all six models, best net first). [coin] null = all coins; [windowKey] null = all-time. */
    fun leaderboard(trades: List<ScalpTrade>, coin: String? = null, windowKey: String? = null, bidFor: (ScalpTrade) -> Double? = { null }, sinceMs: Long? = null, withCi: Boolean = true): List<Row> {
        val byModel = trades.asSequence()
            .filter { sinceMs == null || (it.closedAtMs ?: it.signalAtMs) >= sinceMs }
            .filter { coin == null || it.coin == coin }
            .filter { windowKey == null || it.windowKey == windowKey }
            .mapNotNull { t -> modelOf(t)?.let { it to t } }
            .groupBy({ it.first }, { it.second })
        return ALL.map { m -> row(m, byModel[m].orEmpty(), bidFor, if (withCi) 400 else 0) }
            .sortedWith(compareByDescending<Row> { it.netUsd + it.unrealizedUsd }.thenByDescending { it.roundTrips })
    }

    /** All trades (primary) of one model, newest first. */
    fun tradesOf(model: Model, trades: List<ScalpTrade>, limit: Int = 300): List<ScalpTrade> =
        trades.asSequence().filter { modelOf(it) == model && it.state != ScalpState.NO_FILL }.take(limit).toList()

    /** Cumulative net after fees over closed round trips, oldest → newest (bounded). */
    fun equity(model: Model, trades: List<ScalpTrade>, maxPoints: Int = 400): List<Double> {
        val closed = trades.filter { modelOf(it) == model && it.state == ScalpState.CLOSED && it.netUsd?.isFinite() == true }
            .sortedBy { it.closedAtMs ?: 0L }
        var acc = 0.0
        val pts = closed.map { acc += it.netUsd!!; acc }
        if (pts.size <= maxPoints) return pts
        val step = pts.size.toDouble() / maxPoints
        return (0 until maxPoints).map { pts[(it * step).toInt()] } + pts.last()
    }

    /** Best model = highest net with at least one round trip (null when nobody has traded). */
    fun best(rows: List<Row>): Model? = rows.filter { it.roundTrips > 0 }.maxByOrNull { it.netUsd }?.model

    data class WindowRow(val windowKey: String, val closeMs: Long?, val netByModel: Map<Model, Double>, val tripsByModel: Map<Model, Int>, val best: Model?)

    /** 0.3.68: window list from the per-window aggregate table. */
    fun windowsFromAgg(aggs: List<ScalpWindowAgg>, limit: Int = 48): List<WindowRow> =
        aggs.groupBy { it.windowKey }.map { (w, rows) ->
            val net = rows.mapNotNull { r -> modelByCode(canonicalScalperId(r.scalperId))?.let { it to r.netUsd } }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            val n = rows.mapNotNull { r -> modelByCode(canonicalScalperId(r.scalperId))?.let { it to r.n } }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            WindowRow(w, rows.firstNotNullOfOrNull { it.closeMs }, net, n, net.maxByOrNull { it.value }?.key)
        }.sortedByDescending { it.closeMs ?: 0L }.take(limit)

    /** 0.3.68: one window's leaderboard from the per-window aggregate (net + trips only). */
    fun windowBoardFromAgg(aggs: List<ScalpWindowAgg>, windowKey: String): List<Row> {
        val mine = HashMap<String, Pair<Int, Double>>()
        for (a in aggs) {
            if (a.windowKey != windowKey) continue
            val id = canonicalScalperId(a.scalperId)
            val prev = mine[id]
            mine[id] = if (prev == null) a.n to a.netUsd else (prev.first + a.n) to (prev.second + a.netUsd)
        }
        return ALL.map { m -> val a = mine[m.code]; rowFromStat(ScalpStat(m.code, n = a?.first ?: 0, netUsd = a?.second ?: 0.0), openN = 0) }
            .sortedWith(compareByDescending<Row> { it.netUsd }.thenByDescending { it.roundTrips })
    }

    /** Per 15-minute window: each model's net, and which did best. Newest first, at most [limit] windows. */
    fun perWindow(trades: List<ScalpTrade>, coin: String? = null, limit: Int = 48): List<WindowRow> {
        val closed = trades.filter { it.state == ScalpState.CLOSED && it.netUsd != null && (coin == null || it.coin == coin) }
        return closed.groupBy { it.windowKey }.map { (w, rows) ->
            val tagged = rows.mapNotNull { t -> modelOf(t)?.let { it to t } }
            val net = tagged.groupBy({ it.first }, { it.second.netUsd!! }).mapValues { it.value.sum() }
            val n = tagged.groupBy({ it.first }, { it.second }).mapValues { it.value.size }
            WindowRow(w, rows.firstNotNullOfOrNull { ScalpTicker.closeMs(it.ticker) }, net, n, net.maxByOrNull { it.value }?.key)
        }.sortedByDescending { it.closeMs ?: 0L }.take(limit)
    }

    /** Start of today in ET (for "today" P&L). */
    fun startOfTodayEtMs(nowMs: Long): Long =
        java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.of("America/New_York")).toLocalDate()
            .atStartOfDay(java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()

    /** "BTC 15m · 11:00 AM" (window end time, ET). */
    fun chipLabel(ticker: String, closeMs: Long?): String {
        val coin = ScalpParams.coinOf(ticker)
        val end = (closeMs ?: ScalpTicker.closeMs(ticker))?.let {
            com.dirk.kalshiodds.domain.EtTime.clock(it)
        }
        return if (end == null) "$coin 15m" else "$coin 15m · $end"
    }

    /** Current-window key of a ticker (same cluster as [ScalpTrade.windowKey]). */
    fun windowKeyOf(ticker: String): String = ticker.uppercase().substringAfter('-').substringBefore('-')
}

/**
 * 0.3.50 price ladder for one side of one market (paper). Rows at every whole cent 1..99, highest first:
 * resting bids (our side), asks (1 − other side's bids), and our open paper orders at that price.
 */
object PriceLadder {
    data class Row(val cents: Int, val bidSize: Double, val askSize: Double, val myBuyQty: Int, val mySellQty: Int, val myOrderIds: List<String>) {
        val price: Double get() = cents / 100.0
    }

    data class Order(val id: String, val buy: Boolean, val limitPrice: Double, val remaining: Int)

    fun rows(
        side: String,
        yesBids: List<Pair<Double, Double>>,
        noBids: List<Pair<Double, Double>>,
        myOrders: List<Order> = emptyList()
    ): List<Row> {
        val own = if (side.equals("NO", true)) noBids else yesBids
        val other = if (side.equals("NO", true)) yesBids else noBids
        val bids = HashMap<Int, Double>()
        own.forEach { (p, s) -> if (p.isFinite() && s > 0.0) cents(p)?.let { bids[it] = (bids[it] ?: 0.0) + s } }
        val asks = HashMap<Int, Double>()
        other.forEach { (p, s) -> if (p.isFinite() && s > 0.0) cents(1.0 - p)?.let { asks[it] = (asks[it] ?: 0.0) + s } }
        val mine = myOrders.groupBy { cents(it.limitPrice) ?: -1 }
        return (99 downTo 1).map { c ->
            val m = mine[c].orEmpty()
            Row(c, bids[c] ?: 0.0, asks[c] ?: 0.0, m.filter { it.buy }.sumOf { it.remaining }, m.filter { !it.buy }.sumOf { it.remaining }, m.map { it.id })
        }
    }

    /** Rows around the touch (best bid/ask ± [pad] cents) so the ladder opens where the action is. */
    fun focusIndex(rows: List<Row>): Int {
        val bestAsk = rows.filter { it.askSize > 0 }.minOfOrNull { it.cents }
        val bestBid = rows.filter { it.bidSize > 0 }.maxOfOrNull { it.cents }
        val mid = when {
            bestAsk != null && bestBid != null -> (bestAsk + bestBid) / 2
            else -> bestAsk ?: bestBid ?: 50
        }
        return (99 - mid).coerceIn(0, rows.lastIndex.coerceAtLeast(0))
    }

    fun cents(p: Double): Int? = if (!p.isFinite()) null else Math.round(p * 100).toInt().takeIf { it in 1..99 }

    /** "Sell what I own at +X¢": limit = entry + X, clamped to 1..99¢. Null when nothing is held. */
    fun exitPrice(entry: Double?, plusCents: Int): Double? {
        val e = entry?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val c = (Math.round(e * 100).toInt() + plusCents).coerceIn(1, 99)
        return c / 100.0
    }

    /** Drag repricing: rows moved → new limit, clamped to 1..99¢. */
    fun repriced(limit: Double, rowsMoved: Int): Double = ((Math.round(limit * 100).toInt() - rowsMoved).coerceIn(1, 99)) / 100.0

    val PRESET_SIZES = listOf(1, 5, 10, 25, 50)
    val EXIT_PRESETS_CENTS = listOf(1, 2, 3, 5, 10)
}


/** 0.3.53 Forecast ride / fade rule (paper only). Pure decisions; [ScalpBook.onForecastQuote] applies them. */
object ForecastScalp {
    const val RIDE_PREFIX = "fcride"
    const val FADE_PREFIX = "fcfade"
    const val BAND_LO = 0.40
    const val BAND_HI = 0.60
    /** 1.0.21: fade's own ceiling. Ride stays at [BAND_HI]. A max-entry slider replaces either one. */
    const val FADE_BAND_HI = 0.49
    const val MAX_SPREAD = 0.02
    const val MIN_TAU_ENTRY_S = 60.0
    const val EXIT_BEFORE_CLOSE_S = 30.0
    const val TAKE_PROFIT = 0.90
    const val HARD_FLIP = -0.03
    /** Early reversal must be a real turn. A forecast still near zero is not an exit. */
    const val REVERSAL_DEADBAND_USD = 0.01
    const val ENTRY_LIMIT_TTL_MS = 2_000L
    const val SAFETY = 0.005
    /** 0.3.61 per-bet stop (¢ below entry at the bid). Not studied in bt61 → widest level. */
    const val STOP_C = 25

    fun isForecast(t: ScalpTrade): Boolean = t.variantId.startsWith(RIDE_PREFIX) || t.variantId.startsWith(FADE_PREFIX)
    fun variantId(ride: Boolean, horizonS: Int) = (if (ride) RIDE_PREFIX else FADE_PREFIX) + "-h" + (if (horizonS < 0) "close" else horizonS.toString())

    /** Exact 0 is neutral: neither ride nor fade. Positive rides YES, negative rides NO. */
    fun sideOf(forecast: Double): String? = when {
        forecast > 0.0 -> "YES"
        forecast < 0.0 -> "NO"
        else -> null
    }

    /**
     * Round-trip hurdle in dollars: spread plus the 1-contract entry fee and the 1-contract exit fee.
     * [wantsEntry] adds [marginUsd] on top. This is not the 0.5¢ static safety margin by itself.
     */
    fun minEntryEdgeUsd(spread: Double, ask: Double): Double {
        val px = ask.coerceIn(0.01, 0.99)
        val fee = ScalpRule.orderFee(1, px)
        return spread.coerceAtLeast(0.0) + fee + fee
    }

    /** 0.3.54: predicted move must beat the full round trip (spread + entry fee + exit fee) by [marginUsd]. */
    /** Default band for [code]. Fade is 40–49¢. Ride stays 40–60¢. A price slider replaces either edge. */
    fun entryBand(code: String, s: ScalpStrategySettings?): Pair<Double, Double> {
        val fade = code == FADE_PREFIX || code.startsWith("$FADE_PREFIX-")
        val lo = if (s != null && s.minEntryCents > 1) s.minEntryCents / 100.0 else BAND_LO
        val hi = s?.maxEntryCents?.div(100.0) ?: if (fade) FADE_BAND_HI else BAND_HI
        return lo to maxOf(lo, hi)
    }

    fun wantsEntry(mid: Double, spread: Double, ask: Double, move: Double, tauS: Double, marginUsd: Double = SAFETY, bandLo: Double = BAND_LO, bandHi: Double = BAND_HI): Boolean =
        ScalpRule.buyPriceOk(ask) && mid in bandLo..bandHi && spread <= MAX_SPREAD + 1e-9 && tauS >= MIN_TAU_ENTRY_S &&
            move > minEntryEdgeUsd(spread, ask) + marginUsd.coerceAtLeast(0.0)

    /** Exit when the forecast for the held side turns through the deadband, flips hard, TP, horizon reached, or close. */
    /** [sideForecast] NaN = no fresh forecast: forecast-based exits are skipped (hold until fresh data). */
    fun wantsExit(sideForecast: Double, bid: Double, heldS: Double, horizonS: Int, tauS: Double): String? = when {
        bid >= TAKE_PROFIT - 1e-9 -> "take profit ≥ 90¢"
        !sideForecast.isNaN() && sideForecast < HARD_FLIP -> "forecast flipped hard"
        !sideForecast.isNaN() && sideForecast <= -REVERSAL_DEADBAND_USD -> "forecast turned (move < exit fee)"
        !sideForecast.isNaN() && horizonS > 0 && heldS >= horizonS -> "horizon reached"
        tauS < EXIT_BEFORE_CLOSE_S -> "exit before close"
        else -> null
    }

    fun horizonNote(): String = LeadForecaster.note()
}

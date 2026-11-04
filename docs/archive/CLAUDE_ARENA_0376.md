# Kashi 0.3.76 — Claude Arena UI

Reference: current `Claude` branch (`ffdeb5fa`), `ScalperResultsScreen`, `ArenaParts`, `ArenaChartViews`, and `ArenaCompareViews`.

Ports the reference's four tabs, borderless 20 dp cards, monospace hero profit and six-metric grid, equal pill navigation, group chips, profit stripes, small strategy curves, fee/profit bars and top-four strategy race. Strategy rows open Kashi's existing live 15-minute chart with actual order/fill markers, a new hero, running profit, all-time numbers and older paged history. Settings and manual ladder remain accessible.

The charts use Kashi's own recorded data. Totals use all-time incremental aggregates; window curves start at zero over the displayed recent completed windows. No shared bankroll is invented for separate experiments. Live overview includes primary trades from the current BTC window through one indexed ticker query, at most once per three seconds, latest 600 signals plus active positions; individual strategy pages retain full window detail. Both refresh loops pause when the screen is backgrounded.

Trading decisions, 50-cent algorithm entry ceiling, stops, trailing protection and learning remain unchanged. Theme palette handles dark/light; the orange spot chart token remains intact.

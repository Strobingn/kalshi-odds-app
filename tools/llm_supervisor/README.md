# LLM supervisor (Groq, Mistral fallback)

Python sidecar that watches the 15-minute BTC feed every 60s and tunes the
scalper's parameters live. It never edits code: the LLM can only call
`apply_changes`, which adjusts whitelisted params inside hard bounds
(`PARAM_SPECS`), with a per-tick step limit. No cooldown.

- Learns from every closed trade: each is logged with the params it ran under
  (`supervisor_trades.jsonl`), and the last 25 are sent to the LLM each tick.
- Every change is journaled (`supervisor_journal.jsonl`) and scored after 8
  more trades on that strategy (better/worse) so the LLM sees what helped.
- Env: `GROQ_API_KEY`, `MISTRAL_API_KEY`; optional `GROQ_MODEL`,
  `MISTRAL_MODEL`. `pip install openai` (client for both OpenAI-compatible APIs).
- Wiring: scalper reads params via `StrategyStore.get(strategy, param)`;
  supply `get_feed()` and `get_trades()` (trade dicts need `strategy`,
  `net_pnl_cents`, `ts`, optional `entry_ts`). Status: paper only.

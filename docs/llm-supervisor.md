# AI supervisor (Groq / Mistral)

A chat LLM that watches the live 15-minute BTC feed every 60 s and tunes the **paper** scalper on the fly.
Code: `signal/scalper/LlmSupervisor.kt`. Turn it on under Data → "AI supervisor (paper)" (Groq / Mistral keys
are stored encrypted; Groq is tried first, Mistral is the fallback).

- **What it changes:** the Arena per-strategy settings (`ScalpKnobs`, the same ones as Arena → strategy →
  Settings): enabled, contracts, target, stop, timeout, max per side, entry band, cadence, max spread, bid
  wait, main trigger. The engine already reads these on every decision, so a change takes effect at once.
- **Hard bounds:** every value is clamped to a fixed range and to 25 % of that range per change. The entry
  band stays inside 10–90¢ (no long shots). **No cooldown:** a knob can move again on the next tick.
- **Learns from every trade:** each closed trade is recorded the moment it closes, with the settings it
  ran under. The last 25 go to the model every minute. A change is scored (better / worse) after 8 more
  trades on that strategy and the model sees the verdict, plus its own notes (kept across restarts).
- **Paper only:** it edits settings, never code, and has no path to an order.
- **On disk:** `filesDir/supervisor/supervisor_journal.jsonl` (every applied / rejected change),
  `supervisor_trades.jsonl` (every trade with its settings), `supervisor_errors.jsonl`.
- **Models:** `llama-3.3-70b-versatile` (Groq), `mistral-large-latest` (Mistral); set in `AppContainer`.

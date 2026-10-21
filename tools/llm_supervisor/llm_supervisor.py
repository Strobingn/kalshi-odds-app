"""
llm_supervisor.py - a chat LLM that watches the 15-minute feed every 60s and
tunes the scalper strategies live.

Design: the LLM never edits code. It can only call `apply_changes`, which
adjusts whitelisted parameters inside hard bounds (see PARAM_SPECS). Every
change is validated, step-limited, logged, and later scored so the LLM sees
whether its past changes helped. That is how it "learns" between ticks.

pip install openai        # used only as the client for Groq/Mistral's
                          # OpenAI-compatible APIs
export GROQ_API_KEY=...
export MISTRAL_API_KEY=...
"""
import json
import os
import threading
import time
from dataclasses import dataclass
from pathlib import Path

from openai import OpenAI

# Tried in order each tick; if one errors (rate limit, outage) the next is used.
# Change model names to whatever you have access to.
PROVIDERS = [
    {"name": "groq", "base_url": "https://api.groq.com/openai/v1",
     "key_env": "GROQ_API_KEY",
     "model": os.getenv("GROQ_MODEL", "llama-3.3-70b-versatile")},
    {"name": "mistral", "base_url": "https://api.mistral.ai/v1",
     "key_env": "MISTRAL_API_KEY",
     "model": os.getenv("MISTRAL_MODEL", "mistral-large-latest")},
]
INTERVAL_S = 60
MAX_STEP_PCT = 0.25        # a param can move at most 25% of its range per tick
EVAL_TRADES = 8            # trades to wait before scoring a change
JOURNAL = Path("supervisor_journal.jsonl")
MEMORY = Path("supervisor_memory.json")
TRADE_LOG = Path("supervisor_trades.jsonl")   # every trade + the params it ran under


@dataclass(frozen=True)
class ParamSpec:
    lo: float
    hi: float


# EDIT THIS to match your real strategies/params. Anything not listed here
# cannot be touched by the LLM.
PARAM_SPECS = {
    "dip_hunter": {
        "entry_drop_pct": ParamSpec(0.05, 1.0),
        "take_profit_cents": ParamSpec(2, 25),
        "stop_loss_cents": ParamSpec(2, 25),
        "min_price_cents": ParamSpec(10, 40),   # keeps it off long shots
        "size": ParamSpec(1, 20),
    },
    "momentum_sniper": {
        "lookback_s": ParamSpec(15, 300),
        "min_momentum_pct": ParamSpec(0.02, 0.8),
        "take_profit_cents": ParamSpec(2, 25),
        "stop_loss_cents": ParamSpec(2, 25),
        "min_price_cents": ParamSpec(10, 40),
        "size": ParamSpec(1, 20),
    },
    "extreme_reversion": {
        "extreme_cents": ParamSpec(70, 95),
        "take_profit_cents": ParamSpec(2, 25),
        "stop_loss_cents": ParamSpec(2, 25),
        "size": ParamSpec(1, 20),
    },
}

TOOLS = [{"type": "function", "function": {
    "name": "apply_changes",
    "description": (
        "Adjust scalper parameters and/or enable/disable strategies. "
        "Call with an empty list if no change is warranted. Only change "
        "things you can justify from the data."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "changes": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "strategy": {"type": "string"},
                        "param": {
                            "type": "string",
                            "description": "param name, or 'enabled' (value 0/1)",
                        },
                        "value": {"type": "number"},
                        "reason": {"type": "string"},
                    },
                    "required": ["strategy", "param", "value", "reason"],
                },
            },
            "lesson": {
                "type": "string",
                "description": "One sentence to remember for future ticks.",
            },
        },
        "required": ["changes"],
    },
}}]

SYSTEM = """You supervise a Bitcoin-only Kalshi 15-minute market scalper.
Every 60 seconds you get the live feed, per-strategy recent performance,
current parameters, and a journal of your past changes with their measured
results. Goal: maximize net profit after fees.
Rules:
- Prefer small, incremental changes; change few things at once so results
  stay attributable. No change is a valid answer.
- Use the journal: revert or avoid changes that scored badly; double down
  on ones that scored well.
- Never chase long shots; keep min_price_cents sensible.
- You see every closed trade together with the exact params it ran under.
  Learn from each one: what worked, what lost, and why. You may adjust on
  any tick; weigh a single trade less than a pattern across several.
- Near window close, prefer tighter stops / lower size.
Respond only by calling apply_changes."""


class StrategyStore:
    """Thread-safe param store. Your scalper should READ from this each
    decision (store.get('dip_hunter', 'size')) instead of using constants."""

    def __init__(self, defaults=None):
        self._lock = threading.Lock()
        self.params = {
            s: {p: (spec.lo + spec.hi) / 2 for p, spec in ps.items()}
            for s, ps in PARAM_SPECS.items()
        }
        self.enabled = {s: True for s in PARAM_SPECS}
        if defaults:
            for s, ps in defaults.items():
                self.params.setdefault(s, {}).update(ps)

    def get(self, strategy, param):
        with self._lock:
            return self.params[strategy][param]

    def is_enabled(self, strategy):
        with self._lock:
            return self.enabled.get(strategy, False)

    def snapshot(self):
        with self._lock:
            return {
                s: {"enabled": self.enabled[s], **self.params[s]}
                for s in self.params
            }

    def set(self, strategy, param, value):
        with self._lock:
            if param == "enabled":
                self.enabled[strategy] = bool(round(value))
            else:
                self.params[strategy][param] = value


class Supervisor:
    def __init__(self, store, get_feed, get_trades, paper_only=True):
        """
        get_feed()   -> dict: btc price, yes/no odds, time left in window,
                        recent price ticks, spread, volume... whatever you have.
        get_trades() -> list of closed trades, newest last, each a dict with
                        at least: strategy, net_pnl_cents, ts.
        """
        self.store = store
        self.get_feed = get_feed
        self.get_trades = get_trades
        self.paper_only = paper_only
        self.clients = [
            (p, OpenAI(base_url=p["base_url"], api_key=os.environ[p["key_env"]]))
            for p in PROVIDERS if os.getenv(p["key_env"])
        ]
        if not self.clients:
            raise RuntimeError("Set GROQ_API_KEY and/or MISTRAL_API_KEY")
        self.tick = 0
        self.pending = []               # changes awaiting scoring
        self.last_trade_ts = 0.0
        self.recent_learned = []        # every trade + params in effect
        self.param_history = [(0.0, self.store.snapshot())]
        self.memory = (
            json.loads(MEMORY.read_text()) if MEMORY.exists()
            else {"lessons": [], "scored": []}
        )
        self._stop = threading.Event()

    # ---------- public ----------
    def start(self):
        t = threading.Thread(target=self._loop, daemon=True)
        t.start()
        return t

    def stop(self):
        self._stop.set()

    # ---------- internals ----------
    def _loop(self):
        while not self._stop.is_set():
            started = time.time()
            try:
                self._tick()
            except Exception as e:  # never let the loop die
                self._log({"event": "error", "error": repr(e)})
            self._stop.wait(max(0, INTERVAL_S - (time.time() - started)))

    def _perf(self, trades):
        out = {}
        for t in trades:
            s = out.setdefault(t["strategy"], {"n": 0, "net": 0.0, "wins": 0})
            s["n"] += 1
            s["net"] += t["net_pnl_cents"]
            s["wins"] += t["net_pnl_cents"] > 0
        return out

    def _score_pending(self, trades):
        """Score past changes once enough new trades closed on that strategy."""
        still = []
        for ch in self.pending:
            after = [t for t in trades
                     if t["strategy"] == ch["strategy"] and t["ts"] > ch["ts"]]
            if len(after) >= EVAL_TRADES:
                avg_after = sum(t["net_pnl_cents"] for t in after) / len(after)
                rec = {**ch, "avg_net_after": round(avg_after, 2),
                       "verdict": "better" if avg_after > ch["avg_net_before"]
                       else "worse"}
                self.memory["scored"] = (self.memory["scored"] + [rec])[-40:]
                self._log({"event": "scored", **rec})
            else:
                still.append(ch)
        self.pending = still
        MEMORY.write_text(json.dumps(self.memory, indent=1))

    def _params_at(self, ts):
        snap = self.param_history[0][1]
        for t, p in self.param_history:
            if t <= ts:
                snap = p
        return snap

    def _learn_from_trades(self, trades):
        """Record EVERY newly closed trade with the params it ran under."""
        for t in trades:
            if t["ts"] <= self.last_trade_ts:
                continue
            entry = t.get("entry_ts", t["ts"])
            rec = {**t,
                   "result": "win" if t["net_pnl_cents"] > 0 else "loss",
                   "params_at_entry": self._params_at(entry).get(t["strategy"])}
            self.recent_learned = (self.recent_learned + [rec])[-60:]
            with TRADE_LOG.open("a") as f:
                f.write(json.dumps(rec, default=str) + "\n")
            self.last_trade_ts = max(self.last_trade_ts, t["ts"])

    def _tick(self):
        self.tick += 1
        feed = self.get_feed()
        trades = self.get_trades()
        self._learn_from_trades(trades)
        self._score_pending(trades)

        context = {
            "tick": self.tick,
            "feed": feed,
            "params": self.store.snapshot(),
            "bounds": {s: {p: [v.lo, v.hi] for p, v in ps.items()}
                       for s, ps in PARAM_SPECS.items()},
            "recent_perf_last_20_trades": self._perf(trades[-20:]),
            "last_trades_with_params": self.recent_learned[-25:],
            "pending_unscored_changes": [
                {k: c[k] for k in ("strategy", "param", "old", "new")}
                for c in self.pending],
            "scored_changes": self.memory["scored"][-10:],
            "lessons": self.memory["lessons"][-10:],
        }
        args = self._ask(context)
        if args is None:
            return
        lesson = args.get("lesson")
        if lesson:
            self.memory["lessons"] = (self.memory["lessons"] + [lesson])[-30:]
        for ch in args.get("changes", []):
            self._apply(ch, trades)

    def _ask(self, context):
        for prov, client in self.clients:
            try:
                resp = client.chat.completions.create(
                    model=prov["model"],
                    max_tokens=1024,
                    temperature=0.2,
                    tools=TOOLS,
                    tool_choice="required",
                    messages=[
                        {"role": "system", "content": SYSTEM},
                        {"role": "user", "content": json.dumps(context)},
                    ],
                    timeout=40,
                )
                calls = resp.choices[0].message.tool_calls
                if not calls:
                    continue
                return json.loads(calls[0].function.arguments)
            except Exception as e:
                self._log({"event": "llm_error", "provider": prov["name"],
                           "error": repr(e)[:300]})
        return None

    def _apply(self, ch, trades):
        s, p, v = ch["strategy"], ch["param"], float(ch["value"])
        if s not in PARAM_SPECS or (p != "enabled" and p not in PARAM_SPECS[s]):
            return self._log({"event": "rejected", "why": "unknown", **ch})

        old = self.store.snapshot()[s]["enabled" if p == "enabled" else p]
        if p == "enabled":
            new = 1.0 if v >= 0.5 else 0.0
            old = 1.0 if old else 0.0
        else:
            spec = PARAM_SPECS[s][p]
            step = (spec.hi - spec.lo) * MAX_STEP_PCT
            new = min(max(v, old - step), old + step)     # step limit
            new = min(max(new, spec.lo), spec.hi)         # hard bounds
        if new == old:
            return
        self.store.set(s, p, new)
        self.param_history.append((time.time(), self.store.snapshot()))
        mine = [t for t in trades if t["strategy"] == s][-EVAL_TRADES:]
        before = (sum(t["net_pnl_cents"] for t in mine) / len(mine)) if mine else 0
        rec = {"ts": time.time(), "strategy": s, "param": p, "old": old,
               "new": new, "reason": ch["reason"],
               "avg_net_before": round(before, 2)}
        self.pending.append(rec)
        self._log({"event": "applied", **rec})

    def _log(self, obj):
        obj.setdefault("ts", time.time())
        with JOURNAL.open("a") as f:
            f.write(json.dumps(obj) + "\n")

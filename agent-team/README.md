# Agent Team — Multi-Platform Setup Kit

A five-agent system (planner, researcher, builder, reviewer, router) ported to four runtimes: Grok, ChatGPT, Claude, and Mistral. Web chatbots cannot run shell hooks or a filesystem, so each platform gets an equivalent: hooks become startup instructions and routing rules inside the platform's own configuration mechanism.

## Contents

- `core/agents.md` — the five core agent definitions (identical on every platform, keep verbatim)
- `core/operating-logic.md` — the router preamble used on all platforms
- `playbooks/content/thread.md`
- `playbooks/content/article.md`
- `playbooks/product/feature-spec.md`
- `playbooks/growth/offer.md`
- `playbooks/LEARNINGS.md`
- `platforms/grok.md`
- `platforms/chatgpt.md`
- `platforms/claude.md`
- `platforms/mistral.md`

## Quick start

1. Read `core/agents.md` — these four specialist definitions plus the router are the engine.
2. Pick your platform guide in `platforms/` and follow it.
3. Enforce the cross-platform rules in `core/rules.md` — especially: router is the entry point, reviewer verdicts are mandatory, one learning line saved per completed task.

## Cross-platform summary

| Platform | Entry point | Where agents live | Where playbooks live | Hook enforcement |
|---|---|---|---|---|
| Grok | Router agent | 5 custom agents | Memory / pasted instructions | Prompt rules only |
| ChatGPT | Project or Router GPT | Project instructions or 5 GPTs | Project files | Prompt rules only |
| Claude | Claude Code or Project | Subagents or project instructions | Repo / project knowledge | Real shell hooks (Code) |
| Mistral | Router agent | 5 Le Chat agents | Space docs | Prompt rules only |

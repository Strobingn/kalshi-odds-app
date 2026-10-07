# Platform: Grok (grok.com / X)

Grok supports custom agents ("Agent" mode with persistent instructions and memory). Build five agents, one per role.

## Setup

1. Create five agents in Grok, named Planner, Researcher, Builder, Reviewer, Router.
2. Paste each core definition from `core/agents.md` into that agent's instructions.
3. Router is your entry point. Add the Operating Logic block (`core/operating-logic.md`) to its instructions plus: "You coordinate the other four agents. Give the user exact text to paste into each agent, and take their replies back."
4. Reviewer gets: "You review drafts against the plan's done-criteria."
5. Keep one chat note or Grok memory entry as LEARNINGS: after each task, ask the router to output one learning line and save it to memory ("Remember: ...").

## Hook equivalents

- session-start.sh → first line of Router instructions: "At the start of each session, restate the active goal and ask for any saved learnings."
- pre-tool-use.sh → add to every agent: "Refuse work outside the current task's scope; log the tangent instead."
- post-tool-use.sh / stop.sh → Router instruction: "After completing a task, output one learning line for the user to save."

## Limitation

No automatic file access. Playbooks and skills live as pasted text in each agent's instructions or in Grok memory.

# Cross-Platform Rules

1. The router is always the entry point. Humans talk to the router, the router dispatches.
2. One learning line saved per completed task. This is what compounds.
3. Reviewer verdicts are mandatory. No SHIP, no done.
4. Max two fix loops before escalating to you.
5. When pasting between agents, paste the router's dispatch prompt plus the prior step's output — nothing else. Fresh context is the point.

## Hook equivalents (non-Claude-Code platforms)

- session-start.sh → first line of Router instructions: "At the start of each session, restate the active goal and ask for any saved learnings."
- pre-tool-use.sh → add to every agent: "Refuse work outside the current task's scope; log the tangent instead."
- post-tool-use.sh / stop.sh → Router instruction: "After completing a task, output one learning line for the user to save."

# Platform: Claude (claude.ai Projects or Claude Code)

Claude Code gets the full native build (hooks, settings.json, subagents). If you have Claude Code, use the "Agent Team — Complete Setup Kit" canvas from earlier — it contains the Claude Code version in full, including the four shell hooks.

## claude.ai Projects build

1. Create a Project "Agent Team". Set project instructions to the Operating Logic (`core/operating-logic.md`) plus all five agent definitions (`core/agents.md`) with the line: "For each task, execute plan → research → build → review → route, labeling which agent is producing each output."
2. Add playbooks (`playbooks/`) and LEARNINGS.md as Project knowledge files.
3. Start tasks with "New task: <task>". The reviewer output must include a verdict line before you accept the result.

The Claude Project's context window handles the whole pipeline in one thread. For heavier tasks, split per agent across conversations and paste the router's dispatch prompts between them.

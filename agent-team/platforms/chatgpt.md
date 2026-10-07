# Platform: ChatGPT (ChatGPT Plus/Pro)

Two workable builds. Pick one.

## Build A — Projects (simplest)

1. Create a Project "Agent Team". Upload your playbook/skill files to Project files.
2. Put the Operating Logic (`core/operating-logic.md`) plus all five agent definitions (`core/agents.md`) in the Project's custom instructions, prefixed: "Work as a 5-agent team. For every task, internally run plan → research → build → review → route, announcing which agent is speaking before each output."
3. ChatGPT role-plays each specialist sequentially in one thread. Ask "New task: <task>" to start, "Router: status" to check flow.

## Build B — five custom GPTs (true fresh context)

1. Create five GPTs: Agent Team Planner, Researcher, Builder, Reviewer, Router.
2. Paste each core definition into that GPT's instructions.
3. Enable web search on the Researcher GPT only. Enable code interpreter on Builder only.
4. Router's instructions get the Operating Logic plus: "Produce the exact prompt the user should paste into the next agent, and tell them which agent to open."
5. Store LEARNINGS in the Project (Build A) or re-paste them into Router's conversation opener each session.

## Hook equivalents

Custom instructions carry the scope rule ("stay on task, log tangents") and the end-of-task learning line. There is no automatic enforcement — the reviewer GPT is your quality gate.

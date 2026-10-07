# Platform: Mistral (Le Chat Agents)

Le Chat supports custom agents with instructions, and Agents/Spaces for file context.

## Setup

1. Create five agents in Le Chat (side panel → New agent): Planner, Researcher, Builder, Reviewer, Router.
2. Paste each core definition (`core/agents.md`) into the agent's system prompt. Give Researcher web search; give Builder code execution if the task is technical.
3. Router gets the Operating Logic block (`core/operating-logic.md`) plus: "Output the exact prompt for the next agent and name it."
4. Put playbooks and skills into a Space and attach it to the relevant agents, or paste playbook text directly into the agent prompts.
5. Use a conversation or a Space doc as LEARNINGS.md; after each task, have Router emit one line and add it to the doc.

## Hook equivalents

Same as Grok — instructions carry the scope rule and learning capture; nothing executes automatically, so the router prompt discipline is the guardrail.

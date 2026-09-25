# Agents

Stuck, or the user asked to search, research, check, why, find, cache, persist, transfer, or survive pages: follow [`skills/story-trace/SKILL.md`](skills/story-trace/SKILL.md). Tiny budget first. State lifecycle before any design. If missing_edges > 2, stop with insufficient_evidence — no architecture.

Meeting room skill: [`skills/raz-grok-main-sync/SKILL.md`](skills/raz-grok-main-sync/SKILL.md)

Cursor can also be prompted: “read .raz-grok-main-sync/outbox.jsonl + git log -1 on RAZStudio-Room-Android”.

After idle: `git fetch` then compare `HEAD` vs `origin/main`, and read `.raz-grok-main-sync/outbox.jsonl`.

While this chat is open: `/loop 15m read .raz-grok-main-sync/outbox.jsonl + git log -1 on RAZStudio-Room-Android`.

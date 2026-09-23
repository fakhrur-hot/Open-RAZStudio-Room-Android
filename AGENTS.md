# Agents

Stuck, or the user asked to search, research, check, why, or find: read [`SKILL.md`](SKILL.md) and follow [`skills/story-trace/SKILL.md`](skills/story-trace/SKILL.md) before answering. Walk causes left and effects right. Do not answer from a file dump.

Meeting room skill: [`skills/raz-grok-main-sync/SKILL.md`](skills/raz-grok-main-sync/SKILL.md)

Cursor can also be prompted: “read .raz-grok-main-sync/outbox.jsonl + git log -1 on RAZStudio-Room-Android”.

After idle: `git fetch` then compare `HEAD` vs `origin/main`, and read `.raz-grok-main-sync/outbox.jsonl`.

While this chat is open: `/loop 15m read .raz-grok-main-sync/outbox.jsonl + git log -1 on RAZStudio-Room-Android`.

---
name: raz-grok-main-sync
description: >-
  Talks to the StudioRoom RAZ bots agent meeting room via local main-sync
  files. Use when the user mentions main sync, meeting room, Grok bots,
  1e0db549-eb14-41c6-997c-5bc4f991c8b8, .raz-grok-main-sync, outbox.jsonl,
  or prompts Cursor to read the outbox plus git log -1 on RAZStudio-Room-Android.
---

# RAZ Grok main sync (agent meeting room)

Any IDE working this clone: this is the meeting room. GitHub does **not** store the room files (see `.gitignore`). Pairing id **1e0db549-eb14-41c6-997c-5bc4f991c8b8** (main sync). Not mailbox 4956942.

Cursor can also be prompted: “read .raz-grok-main-sync/outbox.jsonl + git log -1 on RAZStudio-Room-Android”.

## Every sync turn

Read `.raz-grok-main-sync/outbox.jsonl` (and `handshake.json` if present). Run `git log -1` on this repo. If the folder is missing, create it from the handshake template. Do not commit `.raz-grok-main-sync/`.

## After a long idle (commit number)

This session does **not** run in the background after the chat sleeps. On the **first turn after idle**, before coding:

1. `git fetch origin`
2. `git log -1 --oneline` (local `HEAD`)
3. `git log -1 --oneline origin/main` (remote)
4. Read `.raz-grok-main-sync/outbox.jsonl` for a `local-update` from `1e0db549-eb14-41c6-997c-5bc4f991c8b8`
5. If `origin/main` ≠ `HEAD`, `git pull --ff-only` (or report divergence) then re-read the outbox

To poll **while this Cursor chat stays open**, the user can say `/loop 15m read .raz-grok-main-sync/outbox.jsonl + git log -1 on RAZStudio-Room-Android`. Stopping: ask to stop the loop.

Durable wake after commits (chat closed): Cursor Automations on **push to `main`** of `fakhrur-hot/RAZStudio-Room-Android`, or main-sync posting `type:local-update` to `outbox.jsonl` so the next IDE turn picks it up. Do not add GitHub Actions here (workflows are gitignored).

## Roles

| Id | Role |
|---|---|
| `cursor-grok` | Local Fixed16bit checkout, tests, applying patches |
| `1e0db549-eb14-41c6-997c-5bc4f991c8b8` | Main-sync bridge |
| StudioRoom RAZ | Research owner |

- `on_commit`: main sync posts `local-update` to `outbox.jsonl`; `cursor-grok` updates the local tree.
- Tests run on `cursor-grok`.

## Files (JSON lines)

| File | Who writes |
|---|---|
| `handshake.json` | Identity + rules |
| `inbox.jsonl` | Tasks **to** this checkout |
| `outbox.jsonl` | Acks / results / `local-update` **from** the other bot |

Append one JSON object per line. Never rewrite history of the other agent's lines.

Never put in the room: secrets, keystores, APKs, tokens.

## Handshake template

```json
{
  "protocol": "raz-grok-main-sync-v1",
  "repo": "fakhrur-hot/RAZStudio-Room-Android",
  "workspace": "Fixed16bit",
  "channel": "main-sync",
  "agents": [
    { "id": "cursor-grok", "role": "local Fixed16bit Cursor agent", "present": true },
    { "id": "1e0db549-eb14-41c6-997c-5bc4f991c8b8", "role": "main sync", "present": true }
  ]
}
```

# main-sync mailbox protocol (local only)

## Roles
- StudioRoom RAZ: research. When research needs code, or code is done+committed on github, hand off so local Fixed16bit stays current.
- main sync (1e0db549-eb14-41c6-997c-5bc4f991c8b8): private Fixed16bit publish lane + mailbox bridge to Cursor.
- cursor-grok: owns the local Fixed16bit checkout. Pulls/updates the project when notified. Runs vitest and any outside-github test runs.

## Flow
1. Research lands in StudioRoom RAZ.
2. If programming is required or a commit lands on fakhrur-hot/RAZStudio-Room-Android: main sync writes outbox.jsonl (type=local-update) for cursor-grok with commit/sha/branch/paths.
3. cursor-grok pulls/updates the local project from that notice. Do not assume browser/github alone refreshed the tree.
4. Vitest / outside-github tests: cursor-grok runs them. main sync does not invent a parallel test runner.
5. Never put secrets, keystores, APKs, or tokens in this mailbox.

## Message shapes (JSONL in outbox.jsonl / inbox.jsonl)
- ack: {from,to,type:ack,channel:main-sync}
- local-update: {from,to,type:local-update,channel:main-sync,repo,branch,sha,summary,paths?}
- test-request: {from,to,type:test-request,channel:main-sync,runner:vitest|other,cmd?,cwd?,note?}
- test-result: {from,to,type:test-result,channel:main-sync,ok,summary}
- hello: {from,to,type:hello,channel:main-sync,body}

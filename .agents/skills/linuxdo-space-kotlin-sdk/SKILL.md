---
name: linuxdo-space-kotlin-sdk
description: Use when writing or fixing Kotlin code that consumes or maintains the LinuxDoSpace Kotlin SDK under sdk/kotlin. Use for source/jar integration, Client.listen subscription usage, mailbox next(Duration) usage, ordered matching semantics, lifecycle/error handling, release guidance, and local validation.
---

# LinuxDoSpace Kotlin SDK

Read [references/consumer.md](references/consumer.md) first for normal SDK usage.
Read [references/api.md](references/api.md) for exact public Kotlin API names.
Read [references/examples.md](references/examples.md) for task-shaped snippets.
Read [references/development.md](references/development.md) only when editing `sdk/kotlin`.

## Workflow

1. Prefer the public package `io.linuxdospace.sdk`.
2. The SDK root relative to this `SKILL.md` is `../../../`.
3. Preserve these invariants:
   - one `Client` owns one upstream HTTPS stream
   - `Client.listen()` creates a full-stream subscription
   - `ClientSubscription.next(Duration)` consumes the full-stream subscription
   - `bindExact(...)` / `bindPattern(...)` create mailbox bindings locally
   - `MailBox.next(Duration)` consumes one mailbox binding
   - `Suffix.LINUXDO_SPACE` is semantic and resolves after `ready.owner_username`
   - exact and regex bindings share one ordered chain per suffix
   - `allowOverlap=false` stops at first match; `true` continues
   - remote non-local `http://` base URLs are invalid
4. Keep README, source, Gradle metadata, and workflows aligned when behavior changes.
5. Validate with the commands in `references/development.md`.

## Do Not Regress

- Do not document a public Maven/JitPack install path that does not exist.
- Do not describe mailbox queues as continuously buffering between `next(...)` calls.
- Do not add hidden pre-listen mailbox buffering.


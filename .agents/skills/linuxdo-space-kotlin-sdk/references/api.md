# API Reference

## Paths

- SDK root: `../../../`
- Gradle metadata: `../../../build.gradle.kts`
- Settings: `../../../settings.gradle.kts`
- Public package: `../../../src/main/kotlin/io/linuxdospace/sdk`
- Consumer README: `../../../README.md`

## Public surface

- Types: `Client`, `ClientOptions`, `ClientSubscription`, `MailBox`, `MailMessage`, `Suffix`, `LinuxDoSpaceException`, `AuthenticationException`, `StreamException`
- Client:
  - constructor `Client(...)`
  - `listen()`
  - `bindExact(...)`
  - `bindPattern(...)`
  - `route(message)`
  - `isConnected()`
  - `close()`
- ClientSubscription:
  - `next(Duration)`
  - `close()`
- MailBox:
  - `next(Duration)`
  - `close()`
  - metadata accessors such as `address`, `patternText()`, `isClosed()`

## Semantics

- `Suffix.LINUXDO_SPACE` is semantic, not literal.
- Exact and regex bindings share one ordered chain per suffix.
- Full-stream messages use a primary projection address.
- Mailbox messages use matched-recipient projection addresses.
- Mailbox buffering is only active while `next(...)` is currently waiting.

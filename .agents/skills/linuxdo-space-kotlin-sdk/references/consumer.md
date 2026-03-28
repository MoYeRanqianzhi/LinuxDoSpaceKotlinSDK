# Consumer Guide

## Integrate

Current release workflow publishes build artifacts to GitHub Release. The
repository does not currently publish to Maven Central or JitPack.

Package shape:

```kotlin
import io.linuxdospace.sdk.Client
import io.linuxdospace.sdk.MailBox
import io.linuxdospace.sdk.MailMessage
import io.linuxdospace.sdk.Suffix
```

## Full stream

```kotlin
val client = Client("lds_pat...")
val subscription = client.listen()
val item = subscription.next(Duration.ofSeconds(30))
subscription.close()
client.close()
```

## Mailbox binding

```kotlin
val client = Client("lds_pat...")
val alice = client.bindExact("alice", Suffix.LINUXDO_SPACE, false)
val alerts = client.bindExact("alerts", Suffix.LINUXDO_SPACE.withSuffix("foo"), false)
val item = alice.next(Duration.ofSeconds(30))
alerts.close()
alice.close()
client.close()
```

## Key semantics

- Full-stream subscriptions and mailbox queues are different consumption paths.
- Mailbox delivery is active only while `next(...)` is currently waiting.
- `route(message)` is local matching only.
- `Suffix.LINUXDO_SPACE` defaults to `<owner_username>-mail.linuxdo.space`.
- `Suffix.LINUXDO_SPACE.withSuffix("foo")` derives `<owner_username>-mailfoo.linuxdo.space`.
- The SDK synchronizes active semantic `-mail<suffix>` fragments to `/v1/token/email/filters`.

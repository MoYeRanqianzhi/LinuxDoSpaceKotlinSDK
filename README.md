# LinuxDoSpace Kotlin SDK

This Kotlin SDK follows the same semantics as the Python and Java SDKs:

- one `Client` owns one upstream NDJSON stream connection
- full stream events can be consumed through client-level subscriptions
- local mailbox bindings support exact and regex rules in one ordered chain
- ordered matching is strictly by bind order
- `allowOverlap=false` stops at first match
- `allowOverlap=true` allows later matches
- mailbox queue activates only when listening starts (no pre-listen backlog)

## Requirements

- Kotlin 1.9+
- JDK 21+

## Build

```bash
./gradlew build
```

## Environment note

The current workspace does not include `kotlinc`, so this SDK could not be
compiled locally in this run. The project files and source are complete, but
you should run the build in an environment where Kotlin is installed.

## Public API

- `Client`
- `Suffix`
- `MailMessage`
- `ClientOptions`
- `LinuxDoSpaceException`
- `AuthenticationException`
- `StreamException`
- `ClientSubscription`
- `MailBox`

Core methods:

- `Client.listen()`
- `Client.bindExact(...)`
- `Client.bindPattern(...)`
- `Client.route(message)`
- `Client.close()`
- `MailBox.next(timeout)`
- `MailBox.close()`

Routing semantics:

- exact and regex bindings share one ordered chain per suffix
- `allowOverlap=false` stops at the first matched binding
- `allowOverlap=true` continues to later bindings
- `route(message)` matches only `message.address`
- mailbox queues activate only when listening starts and do not backfill history

## Quick start

```kotlin
import io.linuxdospace.sdk.Client
import io.linuxdospace.sdk.Suffix
import java.time.Duration

fun main() {
    Client("lds_pat.example").use { client ->
        val all = client.bindPattern(".*", Suffix.LINUXDO_SPACE, allowOverlap = true)
        val alice = client.bindExact("alice", Suffix.LINUXDO_SPACE)
        val message = alice.next(Duration.ofSeconds(60))
        println(message?.subject)
        all.close()
        alice.close()
    }
}
```

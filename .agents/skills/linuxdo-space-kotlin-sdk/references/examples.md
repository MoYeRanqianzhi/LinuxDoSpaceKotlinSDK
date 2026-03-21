# Task Templates

## Create one exact mailbox

```kotlin
val alice = client.bindExact("alice", Suffix.LINUXDO_SPACE, false)
```

## Create one catch-all

```kotlin
val catchAll = client.bindPattern(".*", Suffix.LINUXDO_SPACE, true)
```

## Route one message locally

```kotlin
val targets = client.route(message)
```


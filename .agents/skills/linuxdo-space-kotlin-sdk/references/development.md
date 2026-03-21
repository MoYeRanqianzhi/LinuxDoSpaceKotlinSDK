# Development Guide

## Workdir

```bash
cd sdk/kotlin
```

## Validate

```bash
gradle --no-daemon build
```

README also documents:

```bash
./gradlew build
```

## Release model

- Workflow file: `../../../.github/workflows/release.yml`
- Trigger: push tag `v*`
- Current release output is uploaded from `build/libs/*` to GitHub Release

## Keep aligned

- `../../../build.gradle.kts`
- `../../../settings.gradle.kts`
- `../../../src/main/kotlin/io/linuxdospace/sdk`
- `../../../README.md`
- `../../../.github/workflows/ci.yml`
- `../../../.github/workflows/release.yml`

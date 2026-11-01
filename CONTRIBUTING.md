# Contributing

Thanks for looking at DSH Mobile. It is a preview project; small, focused PRs are easiest to review.

## Development setup

- JDK 17+ and Android SDK (compileSdk per `app/build.gradle.kts`).
- Node.js for the host plugin tests.
- GNU/Linux, macOS or Windows; the Gradle wrapper is included.

Useful checks before opening a PR:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
node --test --test-force-exit 'host-plugin/test/*.test.mjs'
node --test --test-force-exit 'tools/fake-host/*-test.mjs'
```

Windows uses `gradlew.bat` instead of `./gradlew`.

## Areas

- `app/` — Kotlin/Compose client. UI code should use the existing theme/tokens and keep accessibility labels.
- `host-plugin/` — DSH adapter and Mobile API implementation. Keep the contract in `docs/mobile-api-v1.md` in sync and add tests for validation/redaction/visibility changes.
- `tools/fake-host/` — keep its behaviour aligned with the contract; it is used by tests and UI development.

## Rules

- Never commit credentials, device tokens, pairing codes, private keys, real session logs or personal filesystem paths. Use synthetic fixtures.
- Add or update tests for behaviour changes; do not weaken a privacy/redaction test to make a new case pass.
- Prefer small changes scoped to one area. Do not reformat unrelated files.
- Document user-visible changes in the README/CHANGELOG and protocol changes in `docs/mobile-api-v1.md`.
- By contributing you agree that your contribution is licensed under the project's MIT licence.

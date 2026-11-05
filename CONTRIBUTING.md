# Contributing to DSH Android

Thanks for taking a look. DSH Android is a small, community-run preview project; focused pull requests
are much easier to review than broad rewrites.

## Before you start

- Read [README.md](README.md) and the [documentation index](docs/README.md).
- For protocol work, read [`docs/mobile-api-v1.md`](docs/mobile-api-v1.md).
- Search existing issues and pull requests before opening a new one.
- Never include real host URLs, device tokens, pairing codes, session transcripts, private keys or
  personal filesystem paths in an issue, test or commit.

## Development setup

Requirements:

- JDK 17+
- Android SDK with build-tools (see `app/build.gradle.kts` for compile/target SDK)
- Gradle wrapper (included)
- Node.js for host-plugin and fake-host tests

Useful checks before opening a pull request:

```bash
# Android app
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

# Host plugin and fake host
node --test --test-force-exit 'host-plugin/test/*.test.mjs'
node --test --test-force-exit 'tools/fake-host/*-test.mjs'
```

On Windows use `gradlew.bat` instead of `./gradlew`.

## Where things live

| Path | Responsibility |
| --- | --- |
| `app/` | Kotlin/Jetpack Compose Android client |
| `host-plugin/` | DSH host adapter and Mobile API implementation |
| `tools/fake-host/` | Contract-compatible fake host for tests and UI work |
| `docs/` | User, developer and maintainer documentation |

## Pull request guidelines

- Keep a pull request focused on one area. Do not reformat unrelated files.
- Add or update tests for behaviour changes. Do not weaken privacy/redaction tests.
- Keep the Mobile API contract in `docs/mobile-api-v1.md` in sync with protocol changes.
- Update `CHANGELOG.md` for user-visible changes.
- UI code should use the existing theme/tokens, preserve accessibility labels and keep 48dp touch
  targets.
- By contributing, you agree that your contribution is licensed under the project’s MIT licence.

## Reporting bugs

Use the GitHub issue templates. Include:

- app version and Android version;
- whether you use the Play, GitHub preview or a local debug build;
- host-plugin version and platform;
- reproduction steps and expected/actual behaviour.

Attach logs only after removing tokens, host URLs, session titles and other private data.

## Security reports

Do not open a public issue for vulnerabilities. Use a private GitHub security advisory as described in
[SECURITY.md](SECURITY.md).

## Code of conduct

Be respectful and assume good faith. Harassment, discrimination and disclosure of another person’s
private data are not welcome. Maintainers may close or remove contributions that violate this rule.

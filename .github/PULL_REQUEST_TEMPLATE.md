## Summary

<!-- What changed and why? -->

## Area

- [ ] Android app
- [ ] Host plugin / Mobile API
- [ ] Fake host / tests
- [ ] Documentation / release tooling

## Verification

- [ ] `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
- [ ] `node --test --test-force-exit 'host-plugin/test/*.test.mjs'`
- [ ] `node --test --test-force-exit 'tools/fake-host/*-test.mjs'`
- [ ] Relevant manual check described below

## Screenshots / notes

<!-- Optional. Remove private host URLs, tokens and session data. -->

## Privacy / security checklist

- [ ] No tokens, pairing codes, private keys, personal host URLs or real session logs are included.
- [ ] Protocol changes update `docs/mobile-api-v1.md`.
- [ ] User-visible changes are noted in `CHANGELOG.md`.

# Google Play and release documentation

These pages are for maintainers preparing releases. User-facing setup lives in [`../setup.md`](../setup.md).

| Document | Purpose |
| --- | --- |
| [Release checklist](release-checklist.md) | Build, signing, tracks and Play Console prerequisites |
| [Store listing](store-listing.md) | Title, short/full description, category and graphics |
| [App access](app-access.md) | Reviewer instructions for demo/host access |
| [Data safety](data-safety.md) | Play Data safety answers |
| [App content answers](app-content-answers.md) | Content rating, audience, category and declarations |
| [Foreground-service declaration](fgs-declaration.md) | `dataSync` rationale and demo video |

The Android app is built from `app/`; the Play build uses
`-PplayApplicationId=com.dsh.unofficial.app` and is signed with the upload key described in the
release checklist. Never commit signing material or Play service-account credentials.
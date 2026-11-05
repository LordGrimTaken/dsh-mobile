# Update feed and releases

DSH Android is distributed as a **debug-signed preview APK** from this repository's GitHub Releases. The source, the release feed and the APKs now live together in **`LordGrimTaken/dsh-mobile`**.

| Thing | Value |
| --- | --- |
| Repository | `LordGrimTaken/dsh-mobile` |
| Update feed | `https://github.com/LordGrimTaken/dsh-mobile/releases/latest/download/latest.json` |
| Release pages | `https://github.com/LordGrimTaken/dsh-mobile/releases` |
| APK asset naming | `dsh-android-<versionName>-preview-debug.apk` |

## Feed schema

`latest.json` is public, unauthenticated UTF-8 JSON. `versionCode` is the authoritative upgrade order.

| Field | Type | Meaning |
| --- | --- | --- |
| `schemaVersion` | integer | Feed format version. Currently `1`. |
| `versionCode` | integer | Android `versionCode` of the published APK (monotonic). |
| `versionName` | string | Android `versionName`, e.g. `0.2.141-preview`. |
| `tag` | string | Release tag, `v<versionName>`. |
| `publishedAt` | string | ISO-8601 UTC timestamp. |
| `apkUrl` | string | Direct HTTPS download URL for the APK asset. |
| `apkAssetName` | string | Release asset file name. |
| `apkSizeBytes` | integer | APK size in bytes. |
| `sha256` | string | Lower-case hex SHA-256 of the APK bytes. |
| `minSdk` | integer | Minimum supported Android API level. |
| `notes` | string | Markdown release notes. |
| `releaseUrl` | string | Human-facing release page. |

The app checks the feed at most once a day and verifies the downloaded APK's SHA-256 before handing it to Android's package installer.

## Signature compatibility (important)

An in-app update only installs when the new APK has the **same signing certificate** as the installed app.

- The preview APK in Releases is debug-signed with the publisher's debug keystore.
- A locally built debug APK is signed with *your* debug keystore and will not update over the published build (and vice versa).
- If you mix builds from different machines, uninstall/reinstall once.
- For a serious distribution, switch to a real release signing key and publish builds signed with it.

## Publishing a release

The project publishes a release from a machine that can build the APK:

```bash
# 1. Bump versionCode/versionName in app/build.gradle.kts
# 2. Run tests, build and lint
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

# 3. Tag and create the release with the APK and updated feed
git tag v0.3.3-preview
git push origin main v0.3.3-preview
gh release create v0.3.3-preview \
  app/build/outputs/apk/debug/app-debug.apk \
  latest.json \
  --title "DSH Android v0.3.3-preview" \
  --notes-file RELEASE_NOTES.md \
  --latest

# 4. Verify latest.json SHA/size against the asset
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Use a tag such as `v<versionName>-preview`; the update checker compares `versionCode`, not the tag.

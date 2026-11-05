# Google Play release checklist

This file tracks everything needed to submit DSH Android (`com.dsh.unofficial.app`) to the Google Play
Console. It is a working document; it does not grant permission to publish automatically.

## 1. Play Console account and app

- [ ] Google Play Developer account (personal accounts may need identity verification).
- [x] Create app: Play title **DSH Android Unofficial Harness**, default language English (US), app/game **App**, free. Package **`com.dsh.unofficial.app`**.
- [x] Package name **`com.dsh.unofficial.app`** (matches the uploaded AAB).
- [ ] Accept the Developer Program Policies and US export laws.
- [ ] Complete **App content** (still needs Play Console UI):
  - [ ] Privacy policy URL: `https://github.com/LordGrimTaken/dsh-mobile/blob/main/PRIVACY.md`
  - [ ] Ads: **No**
  - [ ] App access: see `docs/play/app-access.md` (demo/host access instructions)
  - [ ] Content rating questionnaire: developer utility, no user-generated content, no ads.
  - [ ] Target audience: 18+ (developer tool); not directed at children.
  - [ ] Data safety: see `docs/play/data-safety.md`.
  - [ ] News app: No. COVID-19 app: No. Financial features: No. Government app: No.
  - [ ] Health: No.
  - [ ] Photo/video permissions: none requested (system photo picker handles attachments).
- [ ] New personal developer accounts: complete Google's required **closed testing** (12 testers,
  14 days) before production access. This applies to new personal accounts and cannot be bypassed
  by the project.

## 2. Upload key and App Signing

- [ ] Back up the upload keystore and passwords outside this repository.
  - Local path used during development: `.signing/upload-keystore.jks` (gitignored).
  - Properties: `keystore.properties` (gitignored: store password, key alias, key password).
  - If lost, Play App Signing can reset the upload key, but keep a backup anyway.
- [ ] Enrol in **Play App Signing**. Google manages the production app-signing key; the upload key
  above signs the AAB you upload.
- [ ] The Play build is signed with a different key than the sideload/debug APK. Existing sideload
  users must uninstall once before installing the Play build (or the Play build can use a new
  application id in a future major version; not recommended).

## 3. Build the AAB

```powershell
# from the repository root
.\gradlew.bat :app:bundleRelease
```

Expected output: `app/build/outputs/bundle/release/app-release.aab`.

Build the Play variant with the Play application id:

```powershell
$env:ORG_GRADLE_PROJECT_playApplicationId = "com.dsh.unofficial.app"
.\gradlew.bat :app:bundleRelease
```

The debug/sideload build keeps `dev.dsh.mobile`.

The release build:

- targets SDK 36, minSdk 26;
- reads `.signing/upload-keystore.jks` through `keystore.properties` (or CI env vars);
- strips `REQUEST_INSTALL_PACKAGES`;
- disables the sideload updater/feed (`PLAY_BUILD` BuildConfig);
- keeps the FileProvider used by share/export features.

Verify the signing certificate matches the upload key before uploading.

## 4. Store listing

- App name: **DSH Android Unofficial Harness**
- Short description (≤80 chars): **Remote client for your self-hosted DeepSeek Harness**
- Full description: `docs/play/store-listing.md`
- Category: **Tools**
- Tags: developer tools, remote access, self-hosted, AI
- Contact email: use a project contact address (GitHub issues URL is not an email; Play requires a
  monitored email address—set one in the Play Console).
- Website: `https://github.com/LordGrimTaken/dsh-mobile`
- Privacy policy: the URL above.
- Graphics:
  - [x] App icon **512×512 PNG** — uploaded to Play listing.
  - [x] Feature graphic **1024×500 PNG** — uploaded to Play listing.
  - [x] Six 9:16 phone screenshots uploaded to the listing (`docs/play/screenshots/store-*.png`). They are built from actual demo-mode captures; refresh them whenever the UI changes.
  - [ ] Optional 7"/10" tablet screenshots.

## 5. Testing tracks

- [ ] Internal testing: upload the AAB, add testers, install from Play, verify host pairing, chat,
  approvals, files, background notifications and demo mode.
- [ ] Closed testing: required for new personal accounts; gather tester feedback for 14 days.
- [ ] Production: gradual rollout (e.g. 10% → 50% → 100%).

## 6. Play policy review notes

- The app is a **remote client for a self-hosted developer tool**, not a hosted AI service.
- No account creation, no ads, no analytics, no purchases.
- Host pairing is user-initiated with a one-time code; the host is loopback-only and exposed through
  a private tunnel such as Tailscale.
- The app does not download or install APKs in the Play build.
- Demo mode is available for reviewers without a host: describe it in App access.
- Requests for **admin/os-level permission forwarding** must be described accurately if added: the
  Play app itself never escalates privileges; a separate desktop helper performs allowlisted local
  actions after an explicit device-approved request. Do not ship an app that silently bypasses
  Windows UAC.

## 7. Release notes template

```
First Google Play release of DSH Android.

- Connect to your own DeepSeek Harness host over a private network.
- Live sessions, streaming chat, approvals, files, plans, teams, jobs and context usage.
- Terminal-first UI with adaptive layout and accessible controls.
- No analytics, no ads, no developer cloud.
```

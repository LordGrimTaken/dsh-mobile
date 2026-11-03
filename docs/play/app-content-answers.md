# Play Console App content — answers to copy

Use this when completing **Play Console → App content** for `com.dsh.unofficial.app`.

## Privacy policy

URL: `https://github.com/LordGrimTaken/dsh-mobile/blob/main/PRIVACY.md`

The policy covers both the Play package and the sideload `dev.dsh.mobile` build.

## Ads

**No ads.**

## App access

All functionality is available without a developer account. A **demo mode** on the welcome screen
provides sample sessions and approvals without a host. A real host is optional for review.
See `docs/play/app-access.md`.

## Content rating

Category: **Developer tools / Utility**.

Questionnaire answers:
- Violence, sexuality, nudity, language, controlled substances, gambling: **No**
- User-generated content: **No** (the app displays content from the user's own self-hosted agent)
- Users can interact/communicate with other users: **No** (no social features)
- Shares location: **No**
- Purchases: **No**
- Ads: **No**
- Unrestricted web access: **No** (the app is not a browser; it opens links in the system browser)
- Misc: **No**

## Target audience and content

- Target age group: **18+** (developer tool)
- Designed for children: **No**
- Appeals to children: **No**

## Data safety

See `docs/play/data-safety.md`. Summary:

- Does the app collect or share required user data? **No**
- No developer-operated backend, analytics, advertising or tracking SDK.
- Data stays on the device and the user-configured host.
- The Play build has no self-updater/APK installer.

## News / COVID-19 / government / financial / health declarations

All **No**.

## Contact details (store listing)

Play Console requires a monitored contact email. Use a project address you control; do not use a
placeholder. Website: `https://github.com/LordGrimTaken/dsh-mobile`.

## Reviewer notes

- App is a remote client for a self-hosted developer tool.
- No account/login; use demo mode to inspect the UI.
- Remote hosting is user-provided; no public test host is provided.
- No ads, analytics, purchases or developer cloud.

## Play Console click-by-click answers

### Set privacy policy
Privacy policy URL: `https://github.com/LordGrimTaken/dsh-mobile/blob/main/PRIVACY.md`.
The page is public and returns HTTP 200 (GitHub web page, not a PDF). If Play Console rejects
GitHub blob URLs, use a GitHub Pages deployment of the same file.

### Sign in details / App access
- Does the app require users to sign in? **No.**
- Is any functionality restricted? **No.** Reviewers can use **demo mode**.
- Review instructions: "Open DSH Mobile. On the welcome screen choose **Use demo data**. This loads
  sample sessions, messages and approvals without a host. No credentials are required."
- Do not provide a public test host; the app is intentionally local/self-hosted.

### Ads
**No, my app does not contain ads.**

### Content rating
- Category: **Utility, Productivity, Communication, or Other**.
- Violence, sexuality, nudity, profanity, controlled substances, gambling, horror/fear: **No**.
- User-generated content/sharing with other users: **No public sharing**. The app displays prompts
  and AI output from the user's own self-hosted agent; there is no social feed or public UGC.
- Users can interact/communicate with other users: **No**.
- Shares location: **No**.
- Allows purchases: **No**.
- Contains ads: **No**.
- Unrestricted web access: **No** (links open in the system browser).
- AI-generated content: the app is a client for a self-hosted AI agent; it does not generate or
  moderate content itself. If the questionnaire asks, answer that it displays user-requested AI
  output and has no public sharing/moderation surface.

### Target audience
- Age groups: **18 and over only**.
- Is the app designed for children? **No.**
- Appeals to children? **No.**

### Data safety
See `docs/play/data-safety.md`:
- Does the app collect or share required user data? **No.**
- No developer-operated backend, analytics, ads or tracking SDKs.
- Data stays on the device and the user-configured host; uninstall/clear storage removes local data.

### Government apps
**No.**

### Financial features
**No.**

### Health
**No.**

### Select app category and provide contact details
- Category: **Tools**.
- Website: `https://github.com/LordGrimTaken/dsh-mobile`
- Contact email: **a monitored project email you control** (Play Console requires one; no placeholder).
- Phone: optional.
- Privacy policy: URL above.

### Store listing
Already populated via Play API: title **DSH Mobile Unofficial**, short/full description, icon,
feature graphic and two 9:16 phone screenshots. Replace screenshots with real device captures when
available.
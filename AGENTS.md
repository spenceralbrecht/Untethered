# Untethered Focus Instructions

## Untethered Launcher Product Rules

- Home blocker functionality must appear as first-class settings rows on the launcher settings screen; do not hide core blocker controls behind a single dashboard entry.
- The home UV stat should use free Open-Meteo UV data, matching the Vitamin-D-Tracker pattern: current or saved location coordinates with `current=uv_index` and `timezone=auto`.
- The home UV stat must not remain stuck at `--` just because Android location permission is missing; use saved Home Rules coordinates or a no-permission network/IP coordinate fallback before giving up.
- The home battery circle must be a real progress ring driven by the current battery percent, not a static decorative circle around the number.
- The home year-progress graph must use 52 weekly dots and reader-facing copy should say weeks remaining rather than `x of y weeks complete`.
- Selected home app shortcuts should stay visually clean without decorative rings, but empty home slots must remain visible with a clear placeholder target such as a faint circle plus button.
- Home app counts from 1 through 8 must render in the same bottom shortcut area with consistent icon sizing; never put overflow shortcuts in a separate top mini row.
- Home design QA must explicitly check phone screenshots for app shortcut clipping at the screen edges and overlap between shortcuts, the year-progress graph, and the year-progress label.

## Firebase App Distribution

- This fork should be handed off through Firebase App Distribution after meaningful Android changes are built and validated.
- Use `scripts/firebase-distribute-android.sh` for local distribution.
- The default build task is `assembleDebug`, producing `app/build/outputs/apk/debug/app-debug.apk`.
- The distributed debug APK package name is `app.untethered.debug`; the Firebase Android app id must belong to an Android app registered for that package.
- Firebase project: `olauncher-focus-sspen`.
- Firebase Android app id: `1:1702988366:android:6cb561b20112533c0dd11f`.
- Default tester: `s.spencer.a@gmail.com`.
- Override distribution configuration only when intentionally targeting a different Firebase app:
  - `UNTETHERED_FIREBASE_PROJECT` or `FIREBASE_PROJECT`
  - `UNTETHERED_FIREBASE_APP_ID` or `FIREBASE_APP_ID`
  - `UNTETHERED_FIREBASE_GROUPS` or `FIREBASE_GROUPS`, or `UNTETHERED_FIREBASE_TESTERS` or `FIREBASE_TESTERS`
- Legacy `OLAUNCHER_FIREBASE_*` overrides are still accepted by the distribution script.
- Preferred authentication is a service account via `GOOGLE_APPLICATION_CREDENTIALS` or `FIREBASE_SERVICE_ACCOUNT_JSON`.
- Do not use local Firebase CLI login unless `FIREBASE_ALLOW_LOCAL_LOGIN=1` is explicitly set for that run; this avoids distributing personal builds to the wrong Firebase account.
- First Firebase App Distribution release for the original debug package was uploaded on 2026-06-18 as version `v6.6.17` build `108`, release id `681gior6f9l3o`.
- First Firebase app id for the Untethered debug package `app.untethered.debug`: `1:1702988366:android:6cb561b20112533c0dd11f`.

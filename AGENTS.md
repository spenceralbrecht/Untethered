# Olauncher Focus Instructions

## Silo Launcher Product Rules

- Home blocker functionality must appear as first-class settings rows on the launcher settings screen; do not hide core blocker controls behind a single dashboard entry.
- The home UV stat should use free Open-Meteo UV data, matching the Vitamin-D-Tracker pattern: current or saved location coordinates with `current=uv_index` and `timezone=auto`.
- Home design QA must explicitly check phone screenshots for app shortcut clipping at the screen edges and overlap between shortcuts, the year-progress graph, and the year-progress label.

## Firebase App Distribution

- This fork should be handed off through Firebase App Distribution after meaningful Android changes are built and validated.
- Use `scripts/firebase-distribute-android.sh` for local distribution.
- The default build task is `assembleDebug`, producing `app/build/outputs/apk/debug/app-debug.apk`.
- The distributed debug APK package name is `app.olauncher.debug`; the Firebase Android app id must belong to an Android app registered for that package.
- Firebase project: `olauncher-focus-sspen`.
- Firebase Android app id: `1:1702988366:android:371720708d5907240dd11f`.
- Default tester: `s.spencer.a@gmail.com`.
- Override distribution configuration only when intentionally targeting a different Firebase app:
  - `OLAUNCHER_FIREBASE_PROJECT` or `FIREBASE_PROJECT`
  - `OLAUNCHER_FIREBASE_APP_ID` or `FIREBASE_APP_ID`
  - `OLAUNCHER_FIREBASE_GROUPS` or `FIREBASE_GROUPS`, or `OLAUNCHER_FIREBASE_TESTERS` or `FIREBASE_TESTERS`
- Preferred authentication is a service account via `GOOGLE_APPLICATION_CREDENTIALS` or `FIREBASE_SERVICE_ACCOUNT_JSON`.
- Do not use local Firebase CLI login unless `FIREBASE_ALLOW_LOCAL_LOGIN=1` is explicitly set for that run; this avoids distributing personal builds to the wrong Firebase account.
- First Firebase App Distribution release was uploaded on 2026-06-18 as version `v6.6.17` build `108`, release id `681gior6f9l3o`.

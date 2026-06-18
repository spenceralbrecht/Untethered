# Olauncher Focus Instructions

## Firebase App Distribution

- This fork should be handed off through Firebase App Distribution after meaningful Android changes are built and validated.
- Use `scripts/firebase-distribute-android.sh` for local distribution.
- The default build task is `assembleDebug`, producing `app/build/outputs/apk/debug/app-debug.apk`.
- The distributed debug APK package name is `app.olauncher.debug`; the Firebase Android app id must belong to an Android app registered for that package.
- Required distribution configuration:
  - `OLAUNCHER_FIREBASE_PROJECT` or `FIREBASE_PROJECT`
  - `OLAUNCHER_FIREBASE_APP_ID` or `FIREBASE_APP_ID`
  - `OLAUNCHER_FIREBASE_GROUPS` or `FIREBASE_GROUPS`, or `OLAUNCHER_FIREBASE_TESTERS` or `FIREBASE_TESTERS`
- Preferred authentication is a service account via `GOOGLE_APPLICATION_CREDENTIALS` or `FIREBASE_SERVICE_ACCOUNT_JSON`.
- Do not use local Firebase CLI login unless `FIREBASE_ALLOW_LOCAL_LOGIN=1` is explicitly set for that run; this avoids distributing personal builds to the wrong Firebase account.
- If the Firebase project/app has not been created yet, stop after building the APK and report that Firebase App Distribution is blocked on the project id, app id, tester target, and service-account source.

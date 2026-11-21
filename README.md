# WestCon

An Android student skill-sharing app built with Kotlin, Jetpack Compose, Firebase Authentication and Cloud Firestore. Features include profiles, skill posts, exchanges, peer ratings, a freedom wall and direct messaging.

## Build

Use Android Studio with JDK 21 and Android SDK 36. Create your own Firebase project, enable email/password Authentication, register `com.example.westcon`, and place its configuration at the ignored `app/google-services.json`. The checked-in example is synthetic and cannot access a live backend. It is used only for CI compilation.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

For a build-only example, copy `app/google-services.example.json` to `app/google-services.json`. Do not use that example for a deployed app. Gradle downloads dependencies on the first build.

## Security validation

Use Node 24.12 or later and JDK 21:

```sh
npm install --ignore-scripts
npm test
node --test .github/scripts/repository-guard.test.cjs
node .github/scripts/repository-guard.cjs
```

The Firestore emulator tests use only the synthetic `demo-westcon` project. They cover unauthorized access, cross-account writes, spoofed messages, forged/duplicate peer ratings, exchange acceptance, immutable authorship, and atomic likes/comments. CI also builds and lints the Android app.

## Deployment boundary

**A GitHub merge does not deploy Firebase rules or activate GitHub branch protection.** Before releasing this version, follow [SECURITY.md](SECURITY.md) to migrate existing data, deploy and verify the Firestore rules, and apply `.github/branch-protection.json` with an authenticated repository-admin GitHub CLI.

New profile scores are derived from validated peer-rating records. The displayed swap count is the number of completed exchanges with a received peer rating. Legacy profile aggregates are no longer treated as authoritative; preserve/export them before migration if needed.

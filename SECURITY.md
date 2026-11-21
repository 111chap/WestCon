# Security and release requirements

## Repository protections

The repository includes owner CODEOWNERS, read-only CI, application and Firestore tests, and checks for tracked secrets/runtime files. To enforce them on `main`, run as the repository owner with administration permissions:

```sh
gh api --method PUT repos/111chap/WestCon/branches/main/protection --input .github/branch-protection.json
```

This requires pull requests, a fresh owner review, all three checks, resolved conversations and an up-to-date branch; force pushes and deletion are disabled. The owner retains an emergency admin bypass. The configuration file does not enable these settings by itself.

## Firebase rules rollout

No deployment credentials are stored in this repository. The checked-in rules must be deployed using your own authenticated Firebase CLI; CI uses an isolated emulator and never touches production.

1. Export/back up the existing Firestore database using your administrator tooling. Never commit that export.
2. Migrate public `users` documents so `email` is an empty string. Keep account email only in Firebase Authentication. Preserve editable profile fields, set stored `rating` and `swaps` to zero, and normalize nested skill-score fields to zero/default values. The app derives displayed scores from immutable `exchange_ratings` documents instead of trusting profile-supplied aggregates. Existing rating totals are not automatically converted.
3. For historical chats, create `messages/{chatId}` parent documents with exactly two sorted participant UIDs in `members`. Verify the existing message sender/receiver fields and canonical `chatId` before doing so. Never guess membership from untrusted display names. Existing notification and exchange documents also need their immutable participant fields checked.
4. Run the emulator tests. Then deploy the rules to the intended project with `firebase deploy --only firestore:rules --project YOUR_PROJECT_ID`, and verify the deployed version in Firebase Console. Distribute the updated app together with the rules; old clients' cross-account writes will be rejected.
5. Confirm that an unverified/non-WVSU account cannot read data and that two verified WVSU accounts can exchange messages, complete an exchange and submit a single peer rating. Configure Firebase Auth password policy and abuse quotas, restrict the project's Firebase API key to the required APIs/app, and enable App Check after validating your supported devices. Those are project-console settings, not enabled by this source change.

## Client hardening

The app no longer places passwords in Fragment arguments. It refreshes the Firebase token after email verification, validates identifiers/content/ratings, checks authenticated ownership, commits messages/exchange acceptance/ratings atomically, avoids persistent Firestore caching and excludes app data from Android backups/device transfer. Plaintext network traffic is disabled.

Firestore rules remain the authorization boundary; client checks alone cannot stop a modified app. Anonymous wall posts conceal the display name only: their author UID is still present for ownership checks. Do not describe this feature as strong anonymity.

Firebase client API keys identify a project and are not service-account secrets. The real client configuration is removed from the current tree so forks must use their own backend. Historical copies remain in Git history. Restrict exposed keys appropriately; rotate any actual signing/admin credentials that were ever exposed. This update does not rewrite history or alter production users/data.

Report vulnerabilities privately to @111chap, rather than posting credentials or user records in public issues.

# Formbricks 5.4.2 compatibility review

Checked September 14, 2026 for app v0.5.2. This version adds per-survey `show_in_app` selection through the initial value of a Formbricks Text variable. Only active surveys with `YES` are shown; missing values mean NO. Hidden active surveys stay cached so queued response uploads are unaffected. The control variable was added with YES to all 12 active surveys, preserving existing variables, hidden fields, and survey status.

## Result

The app now accepts current Workspace IDs and legacy Environment IDs. The current workspace's 12 active surveys, containing 202 questions, parse and cache successfully in local tests. A sample answer path through each survey reaches an ending. This is evidence for the current survey definitions, not a claim of full Formbricks feature parity or a physical-device end-to-end certification.

The compatibility checks used the live API only to read workspace metadata and survey definitions. No test responses or uploaded files were sent to the live server. After the review, the user separately authorized adding `surveyor_id`: it was added and enabled on all 12 active surveys, with existing hidden fields preserved and each change verified by GET. Credentials and live survey snapshots are not committed to this repository. The installed server's exact version was not independently determined; its API payloads were checked alongside the upstream 5.4.2 source.

## Changes

| Area | Result |
| --- | --- |
| Setup | Validates the Workspace ID against the API key's workspace before saving. Resolves the v1 legacy ID automatically. Wrong IDs produce an error instead of a successful connection with an empty list. |
| Existing settings | Refresh resolves Workspace IDs that older app versions stored in the environment preference. Existing queued responses retain their original routing ID. No Room schema change. |
| Setup QR | Carries both IDs for compatibility with older app versions. New scanners also accept legacy and workspace-only QR payloads. |
| Survey list | Loads active surveys only; v5 rejects submissions to paused/completed surveys. Refresh runs when returning to the list after setup. |
| CSAT and CES | Added numeric scoring and range validation for CSAT 1–5 and CES 1–5/1–7. Score controls wrap on narrow screens. Labels are localized. They use numeric chips, rather than reproducing the web survey's smiley/star artwork. |
| Response uploads | Collector identity is no longer sent as respondent `userId`, which v5 restricts through Contacts licensing. Hidden-field collector attribution remains available. |
| File uploads | Presign requests now include the question's `elementId`, required by 5.4.2. Existing queued files already contain that ID. |
| Older Android versions | Enabled Java time desugaring for Android 7.x; guarded Android 8+ installer settings APIs; corrected the CameraX image-access opt-in. |

## Current workspace findings

- 25 survey definitions returned: 12 active, 10 drafts, 3 paused.
- Active question types: 72 single-choice, 74 CSAT, 30 CES, 6 NPS, and 20 open-text questions.
- All active blocks contain one element. The v1 endpoint returns derived questions with `jumpToQuestion` actions and element operands that the app can read.
- No custom `validation.rules` were found in the active questions. The inspected active definitions have no PIN, enabled reCAPTCHA, enabled email verification, or enabled single-use setting.
- Initially none of the 12 active surveys declared `surveyor_id`. Following explicit user authorization, that field is now enabled on all 12, with existing fields preserved. Refresh the survey cache on devices to pick up the change. Draft and paused surveys were not modified. The app's existing refresh logic may also register other instrumentation fields when the key allows survey writes.

## Validation performed

The following command passed with 21 tests, including the optional local live-snapshot check and survey visibility regressions:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
  -PcompatibilityFixtures=/path/to/private/read-only/snapshots
```

The optional directory contains the raw GET responses as `me.json` and `surveys.json`, without credentials. Without the property, the snapshot test is skipped; the synthetic regression tests still run.

Tests cover workspace/legacy resolution, rejected IDs, old/new QR payloads, filtering non-active and foreign surveys, retaining cache after validation failure, CSAT/CES ranges, translated choice branching, numeric response payloads without Contacts identification, and file presigning/upload/URL substitution. The seven previous duplicate-response regression tests also pass.

The snapshot test uses local HTTP fixtures, removes image URLs to prevent image downloads during the test, and walks one representative answer path per active survey. It does not exhaust every branch or validate real server acceptance of submissions.

The APK builds and lint has no errors. No physical Android device was connected, so QR scanning, GPS, real offline/reconnect behavior, and installer flows have not been retested on hardware for this change.

## Remaining limits

- Custom validation rules and dynamic `requireAnswer` actions are not fully evaluated locally. A survey using them can collect answers that the server later rejects.
- PIN, reCAPTCHA, verified-email and single-use workflows are not implemented by this offline runner. Do not treat these as supported merely because the survey can be listed.
- Quota responses and quota-specific endings are not represented in the app's UI.
- Cal.com uses the existing manual confirmation card; it does not make a booking.
- The v1 API translates block logic into questions, but the app presents one question per screen rather than matching multi-element block layouts. Rich formatting, question randomization and some presentation settings do not have full web-renderer parity.
- Keys scoped to multiple workspaces or only an organization need a future `/api/v2/me` selection flow. Current setup uses the single-workspace v1 `/me` endpoint.
- The existing ten-minute response claim protects against concurrent sync. A lost server acknowledgement followed by an eventual retry is still not an exactly-once guarantee.

## Sources pinned to 5.4.2

- [Workspace and legacy-ID response mapping](https://github.com/formbricks/formbricks/blob/5.4.2/apps/web/app/api/v1/management/me/route.ts)
- [Client API accepts workspace and legacy environment IDs](https://github.com/formbricks/formbricks/blob/5.4.2/apps/web/lib/utils/resolve-client-id.ts)
- [Derived questions and translated block logic](https://github.com/formbricks/formbricks/blob/5.4.2/apps/web/app/lib/api/survey-transformation.ts)
- [CSAT and CES element schemas](https://github.com/formbricks/formbricks/blob/5.4.2/packages/types/surveys/elements.ts)
- [Response endpoint status, Contacts, and protection checks](https://github.com/formbricks/formbricks/blob/5.4.2/apps/web/app/api/v1/client/%5BworkspaceId%5D/responses/route.ts)
- [Storage request schema](https://github.com/formbricks/formbricks/blob/5.4.2/packages/types/storage.ts)

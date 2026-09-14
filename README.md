# Ahlan VOC

Android app for offline Formbricks survey collection at stadiums in KSA. Surveyors load surveys from `https://ksa.formbricks.com` while online, run them fully offline, and the app pushes responses (and any captured files) back the moment a network is available.

## Capabilities

**Onboarding**
- Admin device validates the API key and Workspace ID together, then generates a setup QR containing both the workspace and legacy environment mapping. Existing Environment IDs and older setup QRs remain supported.
- Surveyor devices scan the QR, enter their staff ID, and the app caches active surveys in the configured workspace. Paused, completed, and draft surveys are excluded because the current client API rejects their submissions.
- Image assets (welcome card, ending card, picture-selection thumbnails) are pre-warmed into Coil's 256 MB disk cache during refresh, so they render at the venue with no connectivity.

**Question types — v1 covers every type Formbricks v1 emits:**

| Type | UI | Stored value |
|---|---|---|
| `openText` | text/email/url/number/phone with optional char-limit counter | `string` |
| `multipleChoiceSingle` | radio list with "other" free-text support | `string` (label) |
| `multipleChoiceMulti` | checkbox list with "other" free-text support | `string[]` (labels) |
| `rating` | chip strip 1..N with low/high labels | `number` |
| `csat` | scores 1..5 with localized low/high labels | `number` |
| `ces` | scores 1..5 or 1..7 with localized low/high labels | `number` |
| `nps` | chip strip 0..10 with low/high labels | `number` |
| `cta` | primary + dismiss button | `"clicked"` / `""` |
| `consent` | single checkbox card | `"accepted"` / `""` |
| `pictureSelection` | 2-col image grid (Coil cached) | `string[]` (choice ids) |
| `date` | Material 3 date picker | ISO `YYYY-MM-DD` |
| `matrix` | rows × columns radio grid (horizontally scrollable) | `Map<rowLabel, colLabel>` |
| `ranking` | up/down reorder list | `string[]` (labels in chosen order) |
| `address` | 6 toggleable fields (line1, line2, city, state, zip, country) | positional `string[6]` |
| `contactInfo` | 5 toggleable fields (first, last, email, phone, company) | positional `string[5]` |
| `fileUpload` | system file picker, queued upload, multi-file support | `string[]` (URLs after upload) |
| `cal` | offline-friendly manual confirmation card | `"booked"` / `null` |

**Branching logic** — full Formbricks v1 logic engine in pure Kotlin (`domain/LogicEngine.kt`):
- Recursive condition trees (`{ connector: "and"|"or", conditions: [...] }`) with arbitrary nesting.
- Operators: `equals`, `doesNotEqual`, `contains`, `doesNotContain`, `startsWith`/`endsWith` and their negations, `isGreaterThan`/`isLessThan`/`Equal`, `equalsOneOf`/`isAnyOf`, `includesAllOf`/`includesOneOf`, `doesNotIncludeAllOf`/`OneOf`, `isBefore`/`isAfter`, plus unary `isSubmitted`/`isSkipped`/`isClicked`/`isNotClicked`/`isAccepted`/`isBooked`/`isPartiallySubmitted`/`isCompletelySubmitted`/`isSet`/`isNotSet`/`isEmpty`/`isNotEmpty`.
- Operands: static values, question/element answers, variables, hidden fields. Sub-field operands (`meta.row`, `meta.field`) work for matrix and address/contactInfo.
- Actions: `jumpToQuestion` (target may be a question id OR an ending id — same fallback semantics as the server), `calculate` (assign / concat / add / subtract / multiply / divide on text or number variables), `requireAnswer` (no-op at runtime — static `required` is still authoritative).
- `logicFallback` honoured when no rule matches.

**Endings** — both `endScreen` (thank-you card with optional image) and `redirectToUrl` (taps open in the device browser).

**Multi-language switching** — top-bar globe icon when `survey.showLanguageSwitch === true` and the survey has more than one enabled language. The chosen code rides in `response.language` and is used for every `localized()` lookup; `default` is the universal fallback.

**Variables and hidden fields** — collected in `LogicContext`, snapshotted into the queued response, and posted in `request.variables` / `request.hiddenFields`.

**Offline persistence and sync**
- Three Room tables: cached `surveys`, `queued_responses` (with `variablesJson` + `hiddenFieldsJson` snapshots), `queued_files` (file-upload queue).
- API key is stored in `EncryptedSharedPreferences` (Keystore-backed AES-GCM).
- Three WorkManager workers, all with `CONNECTED` constraint and exponential backoff:
  - `SurveyRefreshWorker` (every 6 h) refreshes survey definitions and pre-warms images.
  - `FileUploadWorker` (every 15 min) uploads queued files via the Formbricks public storage endpoint (handles S3 POST presign, S3 PUT presign, and self-hosted local PUT with signing headers).
  - `ResponseSyncWorker` (every 15 min) POSTs responses, but skips any whose bound files haven't finished uploading yet.
- Captured-then-orphaned dedup: each response carries a stable client UUID in `meta.source = "fbint:<uuid>"`, plus a client-side in-flight marker (`queued_responses.sendingAt`, set just before each POST) keeps concurrent worker runs and retried POSTs from re-sending a row whose 200 OK we never saw — see "Why responses don't duplicate" below.

**Sync status screen** — pending / synced / stuck counts plus a "sync now" button (kicks off both file-upload and response-sync one-shots).

## Install on a phone (no build, no GitHub login)

Scan this QR on the surveyor's Android phone:

![Install QR](docs/install-qr.png)

It downloads the latest signed-by-debug-key APK. The phone will ask "allow installs from this source" once — say yes — then the app installs. Same flow as any sideloaded APK.

If you ever cut a v0.2.0 / v0.3.0 release, **don't regenerate the QR** — it points at GitHub's `latest` redirect, which always serves the most recent release. Surveyors keep using the same printed/projected QR.

Direct URL the QR encodes: <https://github.com/essamharoon/ahlan-voc/releases/latest/download/app-debug.apk>

## Build

You will need:

- Android Studio Ladybug or newer (AGP 8.7.x).
- JDK 17 (Studio bundles one; or `brew install openjdk@17`).
- Android SDK 35 platform + build-tools.

Open `/Users/essamharoon/FBINT` in Android Studio. The first sync will:
- Generate the Gradle wrapper (`gradlew` + jar) under `gradle/wrapper/` (the version is pinned in `gradle/wrapper/gradle-wrapper.properties`).
- Install AGP, Kotlin, Compose, Hilt, Room, Coil, ML Kit, CameraX, WorkManager from the configured repositories.
- Build & run on a connected device.

CLI build once Studio has installed the SDK:

```sh
cd /Users/essamharoon/FBINT
./gradlew :app:assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. Install with `adb install -r ...` or sideload to the surveyor devices.

## Operator runbook

### Admin (one device, online, one-time per project)

1. In Formbricks, create an API key with **Read** permission for the workspace that holds the surveys. The legacy `/me` endpoint used by this app requires a key scoped to exactly one workspace.
2. Copy the **Workspace ID** from the workspace's connection settings. Legacy Environment IDs also work.
3. Install Ahlan VOC on the admin device, open it, choose **Admin**.
4. Enter base URL (`https://ksa.formbricks.com`), the API key, and Workspace ID. Tap **Validate & continue** — the app verifies the ID belongs to the key and resolves the legacy ID needed by existing cached surveys and queued responses.
5. The next screen shows the **setup QR**. Hand each surveyor's device to them and have them scan it.

### Surveyor (one-time per device)

1. Install Ahlan VOC, open it, choose **Surveyor**.
2. Grant camera permission, scan the admin's QR.
3. Enter your name or staff ID — this is attached to every response.
4. The app downloads the surveys list. From there, tap any survey to start collecting.

### During the event

- The runner works fully offline. Responses pile up in the local queue with a status badge on the survey list.
- File-upload questions copy each picked file into private app storage immediately; the queue sends them once a network is back.
- Whenever the device sees a network, WorkManager wakes up: files first, then responses (a response only POSTs once every file it depends on has uploaded).
- Tap the cloud icon to open **Sync status** for live counts and a manual **Sync now**.

### Choose which surveys appear in the app

In Formbricks, edit the survey, open **Questions**, scroll to **Variables**, and set the Text variable `show_in_app`'s **Initial value** to `YES` or `NO`. Save the survey, then refresh the app while online.

- `YES`: the active survey appears on every device after refresh.
- `NO` (or no variable): the survey is hidden from new collection in this app. Existing queued responses still upload and retain access to cached survey metadata.
- Draft, paused, and completed surveys remain hidden even with `YES`.
- This does not change the survey's Formbricks status or disable its web link. Offline devices keep their last refreshed settings. Do not use survey logic to change this administrator setting.

Survey visibility is supported in v0.5.2 and newer; v0.5.1 ignores this variable.

### Recovering a stuck device

Open Sync status. If items show "Retry x3+" with the same error, the survey or file may have been deleted in Formbricks (the client returns 4xx and we mark it fatal). Confirm by opening that survey in Formbricks; if it's gone, the queued items cannot be recovered.

## Architecture

```
admin entry  ──▶  /me validate ──▶  QR (baseUrl, envId, apiKey, project)
                                             │
                                             ▼ scan
surveyor entry ──▶ name capture ──▶ Survey list (Room + Coil pre-warm)
                                             │
                                             ▼ tap
                              Survey runner (Compose + LogicEngine)
                                             │
                                             ▼ pick file        ▼ submit
                            QueuedFileEntity              QueuedResponseEntity
                                             │                   │
                                             ▼ network           │
                       FileUploadWorker ──▶ POST /storage         │
                       (S3 POST | S3 PUT | local PUT)             ▼ network
                                                       ResponseSyncWorker ──▶ POST /responses
                                                       (waits for bound files)
```

- `ConfigRepository` — `EncryptedSharedPreferences` (AES-GCM via Keystore) holds base URL, API key, environment ID, project name, surveyor ID.
- `SurveyRepository` — calls list+detail, caches full JSON in Room, pre-warms image cache via Coil.
- `LogicEngine` (pure Kotlin) — evaluates `question.logic[]` against the in-memory `LogicContext` (answers + variables + hidden fields) and returns the next step (question id, ending id, or done).
- `ResponseRepository` — captures response into the queue, binds referenced files, and (on sync) substitutes file URLs into `data` before POSTing.
- `FileQueueRepository` — copies picked URIs into private storage, requests signed URLs from Formbricks, uploads bytes via OkHttp, and writes back the canonical `fileUrl`.
- `SyncScheduler` — owns the periodic + one-shot WorkManager jobs.

## Security

- API key lives in `EncryptedSharedPreferences`; it's hard but not impossible to extract on a rooted device. Issue a **read-only** key — the QR cannot delete or modify surveys even if leaked.
- The QR encodes the API key in plaintext. Treat it like a password; don't photograph or share.
- Cleartext HTTP is disabled (`networkSecurityConfig`). Self-hosted servers must serve TLS.
- Backups (cloud + device transfer) are off so prefs and the local DB never leave the device.
- File uploads use the Formbricks **private** storage path (`/api/v1/client/{envId}/storage`) — uploaded files inherit Formbricks' configured access controls.

## Hidden fields the app auto-stamps

Every response can carry context the surveyor never types — the app fills these in **only when the matching Hidden Field ID exists on the survey in Formbricks**. Add any of the names below as a Hidden Field in your survey settings to receive that value; surveys that don't declare a name simply skip it. User-entered hidden fields always override the auto-stamps.

| Hidden Field ID | What it captures | Use case |
|---|---|---|
| `surveyor_id` | Staff name/ID entered on first launch | Attribution in CSV exports |
| `device_install_id` | Stable UUID per app install | Detect multiple surveyors sharing one device |
| `app_version` | Build's `versionName` | Track which build a response came from |
| `started_at` | ISO timestamp when the runner opened the survey | Time-of-day analysis |
| `submitted_at` | ISO timestamp at submit | Latency between start and finish |
| `time_to_complete_seconds` | Seconds between started and submitted | **Primary fake-detection signal** — flag responses below `questionCount × 3` |
| `surveyor_pace_today` | Count of responses this surveyor has captured today | Burst / fatigue detection |
| `language_used` | Active runner language at submit (e.g. `default`, `ar-SA`) | Confirms which language the respondent used |
| `is_offline_capture` | `"true"` if the device had no internet at submit | Separates field captures from venue-WiFi tests |
| `location` | `"lat,lng"` from the device GPS at submit | Single-field venue placement |
| `location_lat` | Latitude as a decimal string | Precise mapping |
| `location_lng` | Longitude as a decimal string | Precise mapping |
| `location_accuracy_m` | Reported accuracy radius in metres | Filter out poor fixes |

Location stamps require the surveyor to grant location permission once during onboarding. If they deny, the four location fields just stay empty — nothing breaks. Other auto-stamps work without any permission.

### Building a confidence score from these fields

The app does not compute a quality score on the device — that's tamper-bait. Instead, **stamp the raw signals above** and have your Formbricks dashboard (or a sheet) compute the score. A starting rubric:

- `time_to_complete_seconds` < `questionCount × 3` → -40 (rushed)
- All Likert / rating answers identical (straight-lining) → -30
- `surveyor_pace_today` over 6/hour for a 5-minute survey → -20
- `location_lat`/`location_lng` outside venue polygon → -25
- Same `data` hash twice within an hour from the same `device_install_id` → -50

Score starts at 100; subtract penalties; below 50 = manual review.

## Response duplicate protection

Formbricks' public `POST /responses` is not idempotent — there's no server-side dedup key, so any retry after a server-side success creates a fresh response. We saw this in production: pairs of identical submissions ~30 s apart on the same device, matching the one-shot `ResponseSyncWorker`'s exponential backoff. Causes that triggered it:

- Periodic `ResponseSyncWorker` and the one-shot variant (different unique work names) both picking up the same pending row.
- Process death between the POST returning 200 and our DB write recording it as synced.
- A 200 OK lost on the way back to the device — the client throws, the row stays "pending", the next worker run re-POSTs.

Mitigation is purely client-side (we don't control Formbricks):

1. **Stable client UUID** in `meta.source = "fbint:<uuid>"` so any duplicate that does slip through is identifiable for manual cleanup.
2. **Atomic in-flight claim** (`queued_responses.sendingAt`, added in DB v4) is acquired just before each POST. `pendingOnce` is only a snapshot: two workers waking after an offline period can read the same backlog. `claimForSending` conditionally updates a row only if it is still unsynced and its previous claim is absent or expired. Only the worker that updates one row may POST; the other skips it, including if the first worker already finished it. No database migration is needed for this change.
3. **Non-interrupting sync requests.** New one-shot sync requests use `APPEND_OR_REPLACE`, so capturing another survey or tapping Sync now queues another pass without cancelling an active upload. Cancellation propagates without clearing an uncertain claim. Once the API returns success, recording that success runs in a non-cancellable database write.
4. **Ten-minute retry delay for uncertain outcomes.** On a confirmed 4xx rejection (except 429), the marker is cleared. On network/5xx/429 or cancellation, it remains until the stale window expires. If the server accepted a request but its acknowledgement was lost, retrying after that window can still duplicate it. This is not an exactly-once guarantee; that requires server-side idempotency or reconciliation.

Regression check: `./gradlew :app:testDebugUnitTest`. The Room + mock HTTP server test forces two workers to read the same offline backlog and verifies exactly one POST per response.

## Known limits

- Cal.com bookings render an offline-friendly "mark as booked" card because the iframe can't load offline. If you need true Cal scheduling, the surveyor should hand the respondent a phone with connectivity for that step.
- The app uses Formbricks' v1 compatibility API. In 5.4.2 it derives `questions[]` from blocks and translates jump targets. The app presents one question per screen, rather than reproducing multi-element block layouts.
- `requireAnswer` logic actions are no-ops; static `required` is always honoured. (The runtime mutation is rare in field-collection workflows and would complicate offline state.)
- Custom v5 validation rules, PIN/reCAPTCHA/email verification flows, single-use links, and quota endings are not fully implemented in the offline runner. These need separate support before using surveys that depend on them. See [compatibility review](docs/formbricks-5.4.2-compatibility.md).
- Surveyor IDs are sent through declared `surveyor_id` hidden fields, not as respondent `userId` values (which v5 gates behind Contacts licensing). Add that hidden field when using a read-only API key if collector attribution is required.

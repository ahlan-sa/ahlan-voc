# Ahlan VOC — Stadium Pilot Triage (2026-05-14)

**Scope:** 5 surveys fetched live from ksa.formbricks.com (3 `inProgress` production, 1 `draft`, 1 test).
DTO/LogicEngine audit against real JSON. Post-pilot commits since 2026-04-30 analysed.
No `ISSUES.md` found; field notes reconstructed from commit history.

---

## 1. Schema Gaps

### 1a. Silently-ignored fields (no crash today)

| Field | Location | Risk |
|---|---|---|
| `insightsEnabled` | Every `openText` question (always `false`) | Harmless — Formbricks AI feature flag; app ignores correctly |
| `slug`, `projectOverwrites`, `recaptcha`, `autoComplete`, `customHeadScriptsMode`, `metadata`, `isCaptureIpEnabled`, `customHeadScripts` | Survey root | Harmless; none affect offline runner behaviour |
| `redirectUrl` | Survey root (null in all production surveys) | If populated, link-type surveys redirect after completion — app never follows it. Low risk now. |

### 1b. `blocks[]` — latent high-severity gap

Every survey returns `"blocks": []` today. Formbricks' new block editor populates `blocks[]` and
leaves `questions[]` empty. The `SurveyDto` has no `blocks` field; once a survey is authored in the
new editor the app renders a blank question list. **No immediate impact, but one Formbricks upgrade
away from a silent failure.**

Action: add `val blocks: List<Any> = emptyList()` to `SurveyDto` and surface a warning log / "survey
not supported" card when `questions` is empty but `blocks` is not.

### 1c. `VariableDto.value: Any?`

Moshi can deserialize JSON primitives into `Any?` but will fail or produce `LinkedTreeMap` for
nested objects. No production survey uses variables; acceptable for now. Should be constrained to
`String | Double | Boolean | null` if variables are introduced.

### 1d. Unrendered question types

Production surveys only use `rating`, `nps`, `multipleChoiceSingle`, `openText`. All render. No
unknown types observed.

---

## 2. Logic-Engine Misses

### 2a. Production logic — passes

Both production logic rules use `equals(question, static-choiceId) → jumpToQuestion`. The engine
correctly translates stored choice labels back to choice IDs via `translateAnswerLabelToId` (HTML
stripped, case-insensitive). All jump targets resolve to valid question IDs. **No logic-engine bugs
in the production surveys.**

### 2b. Unimplemented / mis-implemented operators (no production impact)

| Operator / Action | Issue |
|---|---|
| `requireAnswer` action | Silently dropped — `applyActions()` only handles `calculate`. Intentional at v1 per code comment. No production survey uses it. |
| `isPartiallySubmitted` / `isCompletelySubmitted` | Both treated as `isPresent(left)`. Per Formbricks `logic.ts`, `isCompletelySubmitted` should verify all required sub-fields of matrix/address/contactInfo. Not used in production. |
| `isBefore` / `isAfter` | Lexicographic string comparison on the stored value. Correct for ISO-8601 dates but wrong for `MM/DD` format. No date-logic in production surveys. |
| `isNotClicked` | Returns `true` for null (unanswered CTA) — may not match editor intent. Not used. |

### 2c. `calculate` actions

Fully implemented (assign, concat, add, subtract, multiply, divide). Not used in any production
survey. No issues found.

---

## 3. File-Upload Backend Behaviour

The KSA Formbricks instance returns the self-hosted signing path (`signingData` with
`X-File-Signature` / `X-Timestamp` / `X-UUID` headers), not S3 presigned POST. The three-branch
upload handler in `FileQueueRepository.uploadOne()` covers this correctly.

**Bug (low probability): no `sendingAt` guard on file uploads.**
`QueuedFileDao.pendingOnce()` has no staleness filter. If `FileUploadWorker` periodic and one-shot
run concurrently, both pick up the same pending file, both request a signed URL, and both PUT the
bytes. The second `markUploaded` call overwrites the first URL — content is identical, but the first
signed URL becomes an orphaned upload on the server. No response data loss; low probability (narrow
overlap window). Fix: mirror the `sendingAt` pattern from `queued_responses` (requires DB v5
migration).

**Storage leak:** `purgeUploadedFile` deletes file bytes but never deletes the `queued_files` DB
row. The table grows forever. Not urgent at pilot scale but worth a pruning job before the next
event.

---

## 4. Response-Queue Duplicates

**Root cause (fixed in v0.5.0):** `ResponseSyncWorker` periodic and one-shot both queried
`pendingOnce()` without mutual exclusion, so a response was POST'd twice — visible as ~30-second-gap
pairs and batch-flush events in the Formbricks dashboard.

**Fix:** `sendingAt` timestamp set before each POST; `pendingOnce(staleBefore)` skips rows in-flight
within a 10-minute stale window. Cleared on success or confirmed 4xx; held through network/5xx so a
server-processed request whose 200 OK was lost doesn't get re-sent inside the window.

**Remaining risks (minor):**
- Process death after `markSending` but before the POST network call → row held for up to 10 minutes
  before retry. Accepted trade-off.
- `markFailure` and `clearSending` are separate DB writes on 4xx; a crash between them leaves
  `sendingAt` set. Row unblocks after the stale window. Inconsequential in practice.

---

## 5. Surveyor UX Feedback

Reconstructed from post-pilot commits. All items are resolved; no open bugs.

| Symptom | Commit | Classification | Status |
|---|---|---|---|
| Date picker would not open | `60c8209` | Bug — Android tap-target conflict | Fixed |
| Picture-selection Back taps swallowed | `f8a8a7c` | Bug — `LazyVerticalGrid` focus stealing | Fixed |
| HTTP 400 on submit (wrong language code) | `1fd2786`, `dac69f3` | Bug — `"default"` sent to server | Fixed |
| Auto-stamp hidden fields shown on pre-survey screen | `346c2e6` | UX bug | Fixed |
| Duplicate responses (concurrent workers) | `b63c823` / `9fcb718` | Bug — no in-flight guard | Fixed |
| Auto-stamps dropped when survey cache was stale at capture | `9f66fb1` | Bug — premature filtering | Fixed |
| No way to pair existing device to a new admin QR | `fa8e723` | Feature request | Shipped |
| App version not shown in overflow menu | `87bde7d` | Feature request | Shipped |

---

## Open Items (prioritised)

1. **`blocks[]` schema gap** *(medium, 1 day)* — add field + unsupported-survey guard before next
   Formbricks version bump. One upgrade away from blank surveys in production.
2. **File-upload de-dup** *(low, 0.5 day)* — add `sendingAt` to `queued_files` + DB v5 migration.
   Mirror existing response-queue pattern exactly.
3. **`queued_files` row pruning** *(low, 0.5 day)* — prune rows with `uploadedAt` older than N days
   to bound table growth at scale.
4. **`requireAnswer` warning** *(info, trivial)* — log a warning when a `requireAnswer` action is
   encountered so survey designers know it is not enforced by the app.

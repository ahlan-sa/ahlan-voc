Fixes recovery delays that could leave saved responses waiting after network failures.

- Sync now starts a separate manual job instead of waiting behind an older automatic retry. Repeated taps cannot create parallel manual jobs, and active uploads are not cancelled.
- Responses in the temporary upload-verification hold keep automatic recovery scheduled instead of prematurely ending the retry cycle.
- Transport tracking identifies failures before any request headers were attempted, allowing prompt retries when DNS/connectivity returns. Failures after transmission begins, including DNS errors after redirects, retain the safety hold and server reconciliation to prevent blind duplicate submissions.
- The sync screen shows queued/running/completed manual checks, the last completed check time, and a result summary. Old row errors are explicitly labeled Last recorded error. Response IDs can be selected for support.
- Stuck is renamed Needs retry. Existing saved rows, response IDs and answers are retained.

Update safety: same application ID and signing certificate as v0.5.9; database schema remains version 5, with existing migrations retained. Install over the current app. Do not uninstall or clear app storage.

Validation: 49 tests passed; one optional external snapshot test skipped. Release build and lint passed. Tests cover blocked manual work, held-response rescheduling, DNS recovery without losing answers, DNS failure after a redirect without a second POST, concurrent upload claims, existing-response reconciliation, old database migrations, and reopening a version-5 database with stuck responses, cached surveys and attachments intact.

This update fixes application recovery behavior. It cannot repair an unavailable mobile network, DNS service or server. The affected physical phone was not connected for direct verification; no test responses were submitted to production.

Improves offline collection reliability and makes recovery and sync status clearer.

- Saves encrypted survey drafts with answers, language, original surveyor, timing and a stable response identifier. Resume after leaving or restarting the app.
- Verifies uncertain uploads against Formbricks response history before retrying. Keeps queued responses and attachments tied to their original server and preserves older databases through explicit migrations.
- Requires collector, duration and location hidden fields before starting; retains mandatory device location at submission. Failed location retries preserve answers and roll back calculations.
- Adds survey search, last refresh time, GPS readiness and separate saved-on-device/synced receipts. Sync history shows survey names and timestamps.
- Places setup/reset behind administrator unlock using the configured API key and prevents setup changes while responses are pending.
- Imports setup QR codes from images, improves scanner resource cleanup and validates update download completeness and available SHA-256 digests.
- Removes HTTP body logging and ships a non-debuggable build signed with the existing installation certificate. The APK asset keeps its existing filename for updater compatibility.

Validation: 34 automated tests passed; debug and release lint passed; release APK signature and upgrade installation checked. Migration tests cover database versions 2, 3 and 4 to 5. No synthetic responses were submitted to production.

Field validation remains necessary on a physical Android device for extended offline collection, weak GPS and camera scanning. Retry reconciliation is not a server-side exactly-once guarantee. Administrator unlock is a local control, not a replacement for scoped server credentials.

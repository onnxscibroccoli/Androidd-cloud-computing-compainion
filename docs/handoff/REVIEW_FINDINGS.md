# Independent review and dispositions

A review agent inspected the implementation without modifying it. Five high-priority findings were returned before the transfer.

| Finding | Implementation response | Verification limit |
|---|---|---|
| In-flight jobs still QUEUED could expire and poison result replay | Introduced DISPATCHED, preserve late authoritative results | Store regression test passes; live device/network scenario still needed |
| Local Android results could enter the cloud outbox as unknown IDs | `local:` ID namespace excluded from Journal pending upload | Static fix; Android acceptance test still needed |
| Worker stream cancellation killed child but left RUNNING forever | Persist UNKNOWN on CancelledError, then propagate cancellation | Normal execution/timeout tests pass; explicit stream-cancellation test still needed |
| Registry changes/revocation were ignored until restart | Reload registry and recheck device on active dispatch | Registry unit test passes; real established-stream revocation test still needed |
| Expired offline queued jobs blocked capacity indefinitely | Expire undelivered queued rows transactionally before admission count | Code review; dedicated 10,000-job offline-capacity regression still needed |

Subsequent self-review found that the UserService destroy method needed Shizuku's explicit reserved AIDL transaction. The migration includes `destroy() = 16777114` plus explicit IDs for other methods, matching the upstream Shizuku API example. This is later than the historical signed APK.

Re-enrollment journal isolation and a defensive Binder-readiness check are also included after that APK build. No independent production approval or end-to-end device acceptance has occurred.

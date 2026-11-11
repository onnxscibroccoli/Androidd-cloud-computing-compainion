# Portfolio reviewer readiness — Androidd-cloud-computing-compainion

**Focus:** Android cloud computing companion application  
**Current evidence status:** `SOURCE_PRESENT / DEVICE_NOT_VERIFIED` (2026-11-11 snapshot; **not** production approval).  
**Tracking:** [repository issue](https://github.com/onnxscibroccoli/Androidd-cloud-computing-compainion/issues/4) · [portfolio master ledger](https://github.com/onnxscibroccoli/broccoli-core/issues/102)  


## Inspect actual source
- [app](https://github.com/onnxscibroccoli/Androidd-cloud-computing-compainion/tree/main/app)
- [build.gradle.kts](https://github.com/onnxscibroccoli/Androidd-cloud-computing-compainion/blob/main/build.gradle.kts)
- [docs](https://github.com/onnxscibroccoli/Androidd-cloud-computing-compainion/tree/main/docs)

**CI evidence (updated 2026-11-11; supersedes the initial audit note):**
- [Run 38596844382](https://github.com/onnxscibroccoli/Androidd-cloud-computing-compainion/actions/runs/38596844382): `Android APK CI` on a `push` to `main` at `869987b700588a767930c7ec283a2397fb183433` (the current `main` head), success. It covers `testDebugUnitTest` and `assembleDebug`.
- [Run 43091454377](https://github.com/onnxscibroccoli/Androidd-cloud-computing-compainion/actions/runs/43091454377): the same workflow on this readiness PR head `c0964df`, success.
- That main run published the pre-release [`android-test-12`](https://github.com/onnxscibroccoli/Androidd-cloud-computing-compainion/releases/tag/android-test-12), tag at `869987b`.
  - Its `app-debug.apk` GitHub asset digest `sha256:cce9d70731b9cf2af3de9a009c45275ff16af05447b61d692cee5ab54d537011` matches the attached `app-debug.sha256`.
  - It is a **debug, not production-signed** test build.
  - Physical-device installation and cloud execution remain NOT_VERIFIED.

## First reviewer command (check prerequisites before executing)

```sh
gradle tasks   # no ./gradlew script is committed; CI uses a system Gradle via gradle/actions/setup-gradle
```

This is a proposed reproduction command, **not a claim of executed testing**. Confirm the command exists in the current repository; record missing scripts/dependencies as NOT_VERIFIED rather than masking failures.

## Ordered implementation queue

1. [ ] Identify app package, Android permission and service boundaries in current Gradle sources.
2. [ ] Add a reproducible Gradle compile/lint CI path, keeping secrets out of build logs.
3. [ ] Publish a checksum-verified debug artifact only after a successful build.
4. [ ] Test Android 15 on authorized hardware before mobile usability claims.

## Evidence promotion gate

A project is not ready for a public professional demonstration merely because it has a README, an Actions schedule, or a test result unrelated to its claimed behavior. Provide exact source SHA, real test scope, negative controls, and meaningful limitations. Production or hardware assertions require an authorized real environment and appropriately scoped acceptance.

## Safe resumption

Inspect this issue and the master ledger, refresh the repo's `main` head and relevant PRs, implement the first unchecked item on a draft PR, attach exact test evidence and update the tracker. Avoid mass merging, secret exposure, false human review, and speculative employment claims.

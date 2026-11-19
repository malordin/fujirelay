# Verification notes

## 2026-11-19 · 0.3.0 interface

- Build and Android lint passed; the updated APK was installed on Pixel 7 Pro.
- All 21 queue/protocol checks passed after adding the dashboard's numeric state.
- `python3 tests/ui_check.py` passed on the real phone: one primary action,
  collapsed/expanded journal, clickable entries with copy action, settings
  navigation, and diagnostics in the overflow menu. The test sends no photos
  and does not change server settings.
- Camera discovery was retained; the main action automatically chooses queued
  upload when the camera is absent and pending files exist. During an active
  operation, it requests a pause after the current operation completes.
- The real Immich deployment remains pending configuration as noted below.

## 2026-11-19 · 0.2.0

| Check | Result |
|---|---|
| Debug APK compilation | Passed |
| Android lint | Passed, no errors; localization/target/deprecation warnings remain |
| Installation on Pixel 7 Pro | Passed, update of the previous USB importer |
| Synthetic device-side protocol suite | **21 checks passed** |
| Real X-T30 II catalog scan | **3517 objects, 3455 JPEGs, 40.4 GB** |
| New private queue after catalog scan | **0 files, 0 bytes** |
| Connection to the user's real Immich server | Pending server update/configuration |
| Full camera → real Immich batch | Pending |

The initial USB prototype also copied two real JPEGs, about 8.6 MB each, in
0.58 s and 0.49 s. One copied file was validated as a 4416×2944 JPEG with EXIF
present. Those measurements describe USB import, not complete relay throughput.

## Device-side protocol checks

Run after installing the debug APK, with one authorized ADB device and no active
transfer:

```sh
python3 tests/protocol_check.py
```

A temporary HTTP server binds to localhost on an available port. `adb reverse`
routes phone requests to it; that mapping is removed after the check. Fixtures
use an isolated test database and test cache directories, with synthetic bytes
and a synthetic API key. No real Immich credentials or camera photos are used.
The key-encryption check uses isolated test preferences and clears them afterward.

Covered scenarios:

- URL normalization and rejection of unintended HTTP / embedded credentials.
- Android Keystore encryption and key round trip.
- Count/byte admission limits and rejection of unconfirmed deletion.
- Immich 2.x and 3.x multipart formats, checksum confirmation, local cleanup,
  destination isolation, and SQLite reopen.
- Wrong or missing checksum, wrong owner, trashed/offline asset.
- Server failure, permission failure, redirect, unknown upload status, malformed response.
- Upload accepted but response lost, followed by duplicate-safe retry.
- SQLite receipt insertion failure after a successful server confirmation.
- Crash after receipt commit but before cache deletion.
- Local file corruption and incomplete-copy / rename recovery.
- A full 100-file cache: failed confirmation preserves 100 files; successful
  confirmation makes room for exactly one replacement.

These are protocol contract tests, not a substitute for testing an actual
Immich deployment. In particular, they do not establish real-server quota,
reverse-proxy, indexing, or storage behavior.

## Real camera catalog check

```sh
python3 tests/camera_check.py
```

The phone must be unlocked, the app visible near the top, and USB permission
already granted. This enumerates camera objects without copying photo data or
making Immich requests.

## Inspect a run

```sh
adb shell run-as dev.malordin.fuji.usb cat files/last-import.log
adb shell run-as dev.malordin.fuji.usb ls -l files/relay
```

Do not post raw diagnostics publicly without checking source filenames. API keys
and response bodies are not intentionally included in these logs. Pending queue
files and production receipts must not be manually removed as part of testing.

For field-test priorities, see [the roadmap](../todo.md).

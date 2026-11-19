# FujiRelay · roadmap

Keep the app focused: a reliable bridge from the camera to Immich, with bounded
phone storage and explicit delivery receipts.

## Before trusting a full shooting session

- [ ] Update the target Immich instance and record the exact version tested.
- [ ] Run a one-photo end-to-end test against that instance; inspect the original in Immich.
- [ ] Verify duplicate detection for a photo already uploaded by another client.
- [ ] Test a small real batch with a 3-photo cache, then a 100-photo cache.
- [ ] Exercise airplane mode, server restart, full server disk, and an expired API key during a real run.
- [ ] Exercise camera disconnect, screen-off transfer, force-stop, and phone restart.
- [ ] Run a 600–1000-photo field test and record peak cache bytes, elapsed time, and battery use.

## Reliability

- [ ] Automatic retry with backoff when connectivity returns; respect Android service limits.
- [ ] A visible per-file queue with retry details and delivery timestamps.
- [ ] Export/import receipt history without exporting the API key.
- [ ] Explicit audit / re-send flow for assets removed from Immich after delivery.
- [ ] An optional download-and-hash verification mode for independent server read-back.
- [ ] Better classification of quota, storage, auth, and transient network failures.
- [ ] Handle an API version outside the tested 2.x/3.x contracts after reviewing its schema.

## Experience

- [ ] English localization and Android string resources.
- [ ] A compact progress panel with camera, queue, and server counts.
- [ ] Network policy controls: Wi-Fi only / mobile data allowed.
- [ ] Optional destination album in Immich.
- [ ] A queue/history export button and redacted diagnostic export.
- [ ] Accessibility pass with large fonts and TalkBack.

## After measuring the bottleneck

- [ ] Overlap one USB producer and one uploader behind the same count/byte budget.
- [ ] Reuse camera catalog metadata safely between runs.
- [ ] Add RAW/RAF and video as separate opt-ins with suitable cache-size handling.
- [ ] Broaden camera support only with real device tests.

## Public distribution

- [ ] Attach a reviewed APK to the first release after the remote repository is provided.
- [ ] Decide release signing and keep the signing key outside the repository.
- [ ] Add CI for build, lint, and the device-side fixture checks.
- [ ] Publish a compatibility table with exact phone, Android, camera firmware, and Immich versions.

## Implemented

- [x] Native USB/PTP import on X-T30 II, preserving original JPEG bytes.
- [x] App-private persistent queue; default cap of 100 photos / 1 GiB and a 512 MiB reserve.
- [x] SQLite source journal and server/account-scoped delivery receipts.
- [x] Direct Immich upload, duplicate check, and checksum/owner confirmation before cleanup.
- [x] Keep pending photos on upload or confirmation failure.
- [x] Recover incomplete downloads, lost upload responses, and deferred local cleanup.
- [x] Android Keystore protection for the API key.
- [x] Branded Android dashboard, launcher icon, project banner, README, and build instructions.
- [x] Device-side fault tests using a local synthetic Immich API fixture.

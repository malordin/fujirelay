package dev.malordin.fuji;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Debug-only, real SQLite + filesystem + HTTP checks with synthetic bytes. */
public final class ProtocolCheck extends Instrumentation {
    private String base;
    private RelayDb db;
    private RelayQueue queue;
    private int sequence;
    private final ArrayList<String> passed = new ArrayList<>();
    @Override public void onCreate(Bundle args) { super.onCreate(args); base = args.getString("base", "http://127.0.0.1:8765"); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            getTargetContext().deleteDatabase("protocol-check.db");
            File root = new File(getTargetContext().getCacheDir(), "protocol-check");
            erase(root);
            db = new RelayDb(getTargetContext(), "protocol-check.db");
            queue = new RelayQueue(root, db);
            expect(Settings.normalizeEndpoint("https://photos.example.com/", false).equals("https://photos.example.com/api"), "URL normalization");
            fails(() -> Settings.normalizeEndpoint("http://photos.example.com", false));
            fails(() -> Settings.normalizeEndpoint("https://key@photos.example.com", false));
            pass("URL validation");
            android.content.Context isolated = new android.content.ContextWrapper(getTargetContext()) {
                @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                    return super.getSharedPreferences("protocol-check-" + name, mode);
                }
            };
            Settings.save(isolated, base, "synthetic-test-key", 100, 1024 * Settings.MIB, true);
            expect(new Settings(isolated).apiKey.equals("synthetic-test-key"), "Encrypted key round trip");
            expect(!isolated.getSharedPreferences("relay-settings",0).getString("api-key","").contains("synthetic-test-key"), "Key is not stored in plaintext");
            isolated.getSharedPreferences("relay-settings",0).edit().clear().commit();
            pass("Android Keystore API-key encryption");

            RelayDb.Item first = fixture();
            fails(() -> queue.cleanup(first, "unconfirmed"));
            expect(queue.file(first.key()).exists(), "Unconfirmed file survived cleanup attempt");
            expect(!queue.fits(1, 1, 1024 * 1024), "Count cap");
            expect(!queue.fits(1, 100, first.size()), "Byte cap");
            expect(queue.fits(1, 2, 1024 * 1024), "Space for one more");
            pass("Hard count/byte bounds and deletion guard");

            for (String version : List.of("v2", "v3")) {
                ImmichClient client = client(version); String target = client.connect();
                RelayDb.Item item = version.equals("v2") ? first : fixture();
                queue.send(item, client, target);
                expect(!queue.file(item.key()).exists() && db.confirmed(item.key(), target), "Verified file cleaned");
                expect(!db.confirmed(item.key(), target + "-other-user"), "Receipt is scoped to destination");
                db.close(); db = new RelayDb(getTargetContext(), "protocol-check.db");
                queue = new RelayQueue(root, db);
                expect(db.confirmed(item.key(), target), "Receipt survived reopening SQLite");
                pass("Immich " + version + " upload, checksum confirmation, cleanup, restart");
            }
            for (String fault : List.of("wrong-hash", "trashed", "offline", "wrong-owner", "missing-hash", "server-error", "unauthorized", "redirect", "bad-status", "malformed")) {
                ImmichClient client = client(fault); String target = client.connect();
                RelayDb.Item item = fixture();
                fails(() -> queue.send(item, client, target));
                expect(queue.file(item.key()).exists(), fault + " retained local file");
                expect(!db.confirmed(item.key(), target), fault + " has no receipt");
                pass(fault + " preserves cache");
            }
            RelayDb.Item lostResponse = fixture();
            ImmichClient retry = client("lost-response"); String retryTarget = retry.connect();
            fails(() -> queue.send(lostResponse, retry, retryTarget));
            expect(queue.file(lostResponse.key()).exists(), "Lost response kept the file");
            queue.send(lostResponse, retry, retryTarget);
            expect(!queue.file(lostResponse.key()).exists(), "Retry finds remote duplicate and verifies it");
            pass("Upload accepted but response lost: duplicate-safe retry");

            RelayDb.Item failedCommit = fixture();
            ImmichClient good = client("commit"); String target = good.connect();
            db.getWritableDatabase().execSQL("CREATE TRIGGER fail_receipt BEFORE INSERT ON receipts BEGIN SELECT RAISE(FAIL, 'test disk failure'); END");
            fails(() -> queue.send(failedCommit, good, target));
            expect(queue.file(failedCommit.key()).exists(), "Failed receipt commit retained cache");
            db.getWritableDatabase().execSQL("DROP TRIGGER fail_receipt");
            queue.send(failedCommit, good, target);
            pass("Receipt commit failure cannot delete a file");

            RelayDb.Item cleanupCrash = fixture();
            String assetId = good.uploadAndVerify(cleanupCrash, queue.file(cleanupCrash.key()));
            db.confirm(cleanupCrash, target, assetId); // Simulate a crash before local deletion.
            db.close(); db = new RelayDb(getTargetContext(), "protocol-check.db"); queue = new RelayQueue(root, db);
            queue.cleanup(cleanupCrash, target);
            expect(!queue.file(cleanupCrash.key()).exists(), "Durable receipt permits deferred cleanup");
            pass("Crash after confirmation, before cleanup");

            RelayDb.Item corrupt = fixture();
            try (FileOutputStream out = new FileOutputStream(queue.file(corrupt.key()), true)) { out.write(1); }
            fails(() -> queue.send(corrupt, good, target));
            expect(queue.file(corrupt.key()).exists(), "Corrupt cache kept for inspection");
            pass("Local checksum mismatch cannot delete a file");

            RelayDb.Item interrupted = new RelayDb.Item(String.format(Locale.ROOT,"%064x", ++sequence),"crash.jpg",3,1000,1000,null);
            db.remember(interrupted);
            try (FileOutputStream out = new FileOutputStream(queue.file(interrupted.key()))) { out.write(new byte[]{1,2,3}); }
            File partial = queue.part(String.format(Locale.ROOT,"%064x", ++sequence));
            try (FileOutputStream out = new FileOutputStream(partial)) { out.write(1); }
            // Remove the deliberate corrupt fixture before testing pending recovery.
            expect(queue.file(corrupt.key()).delete(), "Remove only synthetic corrupt fixture");
            queue = new RelayQueue(root, db);
            expect(!partial.exists(), "Incomplete .part removed");
            RelayDb.Item recovered = queue.pending().stream().filter(i -> i.key().equals(interrupted.key())).findFirst().orElseThrow(() -> new IllegalStateException("Fixture missing"));
            expect(recovered.checksum() != null, "Recover rename-before-database-update crash");
            pass("Interrupted copy and rename recovery");
            db.close(); getTargetContext().deleteDatabase("protocol-limit-check.db");
            db = new RelayDb(getTargetContext(), "protocol-limit-check.db");
            File bounded = new File(getTargetContext().getCacheDir(), "protocol-limit-check"); erase(bounded);
            queue = new RelayQueue(bounded, db);
            while (queue.fits(128, 100, 1024 * 1024)) fixture();
            expect(queue.count() == 100 && !queue.fits(1, 100, 1024 * 1024), "Exactly 100 cached files; no 101st");
            ImmichClient failed = client("wrong-hash"); String failedTarget = failed.connect();
            RelayDb.Item head = queue.pending().get(0);
            fails(() -> queue.send(head, failed, failedTarget));
            expect(queue.count() == 100, "Failed upload did not make room by deleting a file");
            ImmichClient drain = client("bounded"); String boundedTarget = drain.connect();
            queue.send(head, drain, boundedTarget);
            expect(queue.count() == 99 && queue.fits(128,100,1024 * 1024), "Verified cleanup allows one replacement");
            fixture(); expect(queue.count() == 100, "Replacement stays within cap");
            pass("Full 100-file cache: failure preserves all files; confirmed upload frees one slot");
            java.time.ZoneId zone=java.time.ZoneId.of("Asia/Yekaterinburg");
            java.time.LocalDate today=java.time.LocalDate.of(2026,11,19);
            PhotoSelection todayFilter=PhotoSelection.day("today",today,zone);
            PhotoSelection yesterday=PhotoSelection.day("yesterday",today,zone);
            expect(todayFilter.matches("any",todayFilter.from)&&!todayFilter.matches("any",todayFilter.until),"Half-open today boundaries");
            expect(yesterday.until==todayFilter.from&&!yesterday.matches("any",todayFilter.from),"Yesterday excludes today");
            java.time.ZoneId dst=java.time.ZoneId.of("Europe/Berlin");
            PhotoSelection spring=PhotoSelection.range("range",java.time.LocalDate.of(2026,3,29),java.time.LocalDate.of(2026,3,29),dst);
            expect(spring.until-spring.from==23*60*60*1000L,"DST day is not assumed to be 24 hours");
            PhotoSelection manual=new PhotoSelection("manual",0,Long.MAX_VALUE,java.util.Set.of("picked"));
            expect(manual.matches("picked",0)&&!manual.matches("excluded",todayFilter.from),"Explicit keys override date");
            pass("Today, yesterday, inclusive date range, DST, explicit file selection");
            RelayDb.Item stopped=fixture();
            RunControl cancellation=new RunControl();
            ImmichClient slow=new ImmichClient(base+"/slow/api","synthetic-test-key",cancellation);String slowTarget=slow.connect();
            java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
            Thread upload=new Thread(()->{try{queue.send(stopped,slow,slowTarget);}catch(Throwable e){failure.set(e);}});
            upload.start(); Thread.sleep(500);long stopAt=System.nanoTime();cancellation.stop();upload.join(2500);
            expect(!upload.isAlive()&&failure.get()!=null,"Stop interrupts a stalled HTTP request");
            expect(queue.file(stopped.key()).exists()&&!db.confirmed(stopped.key(),slowTarget),"Cancelled upload keeps cache without a receipt");
            expect((System.nanoTime()-stopAt)<2500_000_000L,"Network stop within 2.5 seconds");
            pass("Full stop interrupts stalled HTTP and retains unconfirmed cache");
            result.putString("stream", "\nPASS " + passed.size() + " checks\n" + String.join("\n", passed) + "\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL: " + error + "\nPassed: " + passed + "\n"); finish(1,result);
        } finally { if (db != null) db.close(); }
    }
    private ImmichClient client(String route) { return new ImmichClient(base + "/" + route + "/api", "synthetic-test-key"); }
    private RelayDb.Item fixture() throws Exception {
        String key = String.format(Locale.ROOT,"%064x", ++sequence);
        byte[] data = ("synthetic JPEG fixture " + sequence).getBytes(StandardCharsets.UTF_8);
        RelayDb.Item item = new RelayDb.Item(key, "test-" + sequence + ".jpg", data.length, 1700000000000L,1700000000000L,null);
        db.remember(item);
        try (FileOutputStream out = new FileOutputStream(queue.part(key))) { out.write(data); }
        queue.finish(item);
        return queue.pending().stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow(() -> new IllegalStateException("Fixture missing"));
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void fails(Action action) throws Exception { try { action.run(); } catch (Exception expected) { return; } throw new AssertionError("Expected failure"); }
    private interface Action { void run() throws Exception; }
    private void pass(String text) { passed.add(text); }
    private static void erase(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) { File[] files=file.listFiles(); if(files==null) throw new IOException("Test directory unavailable"); for(File child:files) erase(child); }
        if (!file.delete()) throw new IOException("Cannot clear synthetic test fixture");
    }
}

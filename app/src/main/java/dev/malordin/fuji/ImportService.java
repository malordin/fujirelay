package dev.malordin.fuji;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.mtp.*;
import android.os.*;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class ImportService extends Service {
    static volatile boolean busy, cancel;
    static volatile String status = "", phase = "Готов к подключению", cacheSummary = "", deliverySummary = "";
    static volatile int cachedFiles, confirmedFiles;
    static volatile long cachedBytes;
    static volatile boolean failed;
    private final StringBuilder history = new StringBuilder();
    private static final String CHANNEL = "relay";
    private MtpDevice mtp;
    private String camera;
    private RelayQueue queue;
    private RelayDb db;
    private String target;
    private int delivered;
    private record Photo(MtpObjectInfo info, String path) {}

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (busy) return START_NOT_STICKY;
        busy = true; cancel = false; delivered = 0; failed = false;
        history.setLength(0);
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, "Передача в Immich", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, notification("Подготовка…"));
        String mode = intent == null ? "scan" : intent.getAction();
        new Thread(() -> runImport(mode), "fujirelay").start();
        return START_NOT_STICKY;
    }
    private Notification notification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setContentTitle("FujiRelay")
            .setContentText(message).setSmallIcon(dev.malordin.fuji.R.drawable.ic_relay)
            .setContentIntent(open).setOngoing(true).build();
    }
    private void report(String text) {
        Log.i("FujiRelay", text);
        history.append(text).append('\n');
        if (history.length() > 16000) history.delete(0, history.indexOf("\n", history.length() - 12000) + 1);
        status = history.toString();
        getSystemService(NotificationManager.class).notify(1, notification(text));
        try (FileOutputStream out = openFileOutput("last-import.log", MODE_APPEND)) {
            out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { Log.e("FujiRelay", "Writing log", e); }
    }
    private void stats() throws Exception {
        cachedFiles = queue.count(); cachedBytes = queue.bytes(); confirmedFiles = db.confirmedCount();
        cacheSummary = String.format(Locale.US, "%d фото · %.1f МиБ", queue.count(), queue.bytes() / (double)Settings.MIB);
        deliverySummary = delivered + " за этот запуск · " + db.confirmedCount() + " подтверждений в журнале";
    }
    private void runImport(String mode) {
        PowerManager.WakeLock wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FujiRelay:transfer");
        wake.acquire(6 * 60 * 60 * 1000L);
        try {
            try (FileOutputStream out = openFileOutput("last-import.log", MODE_PRIVATE)) { /* new run */ }
            db = new RelayDb(this);
            queue = new RelayQueue(new File(getFilesDir(), "relay"), db);
            stats();
            if ("scan".equals(mode)) {
                phase = "Читаю карту";
                List<Photo> photos = scan();
                long bytes = 0; for (Photo photo : photos) bytes += photo.info.getCompressedSizeLong();
                report(String.format(Locale.US, "Найдено JPEG: %d · %.1f ГБ", photos.size(), bytes / 1e9));
                phase = cancel ? "Остановлено" : "Камера готова";
                report("Проверка завершена. Кэш: " + cacheSummary);
                return;
            }
            Settings settings = new Settings(this);
            ImmichClient client = new ImmichClient(settings.endpoint, settings.apiKey);
            phase = "Подключаюсь к Immich";
            target = client.connect();
            report("Immich подключён. Лимит: " + settings.maxFiles + " фото / " + settings.maxBytes / Settings.MIB + " МиБ");
            int limit = "one".equals(mode) ? 1 : Integer.MAX_VALUE;
            drain(client, limit);
            if (!cancel && queue.count() > 0 && delivered < limit) throw new IOException("В кэше есть файлы без записи в журнале. Передача остановлена для проверки");
            if ("drain".equals(mode) || cancel || delivered >= limit) { finish(); return; }
            phase = "Читаю карту";
            List<Photo> photos = scan();
            int index = 0, skipped = 0;
            // ponytail: sequential batches leave USB idle during upload; overlap one producer/uploader only if measurements justify it.
            while (index < photos.size() && !cancel && delivered < limit) {
                while (index < photos.size() && !cancel) {
                    Photo photo = photos.get(index);
                    RelayDb.Item item = item(photo);
                    if (db.confirmed(item.key(), target)) { index++; skipped++; continue; }
                    if (!queue.fits(item.size(), Math.min(settings.maxFiles, limit - delivered), settings.maxBytes)) {
                        if (queue.count() == 0) throw new IOException("Недостаточно места для следующего JPEG: увеличь лимит кэша или освободи память. Резерв: 512 МиБ");
                        break;
                    }
                    phase = "Камера → кэш";
                    report("Сохраняю " + item.name() + " · " + (index + 1) + "/" + photos.size());
                    copy(photo, item);
                    index++; stats();
                }
                if (!cancel) {
                    drain(client, limit);
                    if (queue.count() > 0 && delivered < limit) throw new IOException("Не удалось освободить кэш. Новые фотографии не скачиваются");
                }
            }
            report("Уже доставленные кадры пропущены: " + skipped);
            finish();
        } catch (Exception e) {
            failed = true;
            phase = "Передача приостановлена";
            // Keep credentials and response bodies out of diagnostic logs.
            report("Ошибка: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\nНеподтверждённые фотографии остаются в кэше. Можно повторить передачу.");
        } finally {
            if (mtp != null) { mtp.close(); mtp = null; }
            if (db != null) db.close();
            if (wake.isHeld()) wake.release();
            busy = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }
    private void drain(ImmichClient client, int limit) throws Exception {
        for (RelayDb.Item item : queue.pending()) {
            if (cancel || delivered >= limit) return;
            phase = "Кэш → Immich";
            report("Передаю " + item.name() + "…");
            queue.send(item, client, target);
            delivered++; stats();
            report("Подтверждено в Immich: " + item.name() + ". Место в кэше освобождено");
        }
    }
    private void finish() throws Exception {
        stats();
        phase = cancel ? "Остановлено" : "Передача завершена";
        report(phase + " · доставлено: " + delivered + " · кэш: " + cacheSummary);
    }
    private List<Photo> scan() throws Exception {
            UsbManager manager = getSystemService(UsbManager.class);
            UsbDevice device = null;
            for (UsbDevice candidate : manager.getDeviceList().values()) {
                if (candidate.getVendorId() == 0x04cb && candidate.getProductId() == 0x02f3) { device = candidate; break; }
            }
            if (device == null) throw new IOException("Камера отключена");
            if (!manager.hasPermission(device)) throw new IOException("Нужно разрешение USB: открой приложение и нажми проверку");
            UsbDeviceConnection connection = manager.openDevice(device);
            if (connection == null) throw new IOException("Не удалось открыть USB. Закрой Fudge и другие импортёры");
            mtp = new MtpDevice(device);
            if (!mtp.open(connection)) {
                connection.close();
                throw new IOException("Не удалось открыть PTP-сессию. Закрой другие импортёры и переподключи камеру");
            }
            MtpDeviceInfo deviceInfo = mtp.getDeviceInfo();
            if (deviceInfo == null) throw new IOException("Камера не ответила на GetDeviceInfo");
            camera = deviceInfo.getModel() + "/" + deviceInfo.getSerialNumber();
            report("Подключена: " + deviceInfo.getManufacturer() + " " + deviceInfo.getModel());
            int[] storages = mtp.getStorageIds();
            if (storages == null || storages.length == 0) throw new IOException("Камера не вернула SD-карту");
            List<Photo> photos = new ArrayList<>();
            for (int storage : storages) {
                // Association 0 means all objects, including those inside DCIM subfolders.
                int[] handles = mtp.getObjectHandles(storage, 0, 0);
                if (handles == null) throw new IOException("Не удалось получить список файлов");
                report("Читаю каталог: " + handles.length + " объектов…");
                HashMap<Integer, MtpObjectInfo> infos = new HashMap<>();
                for (int handle : handles) {
                    if (cancel) break;
                    MtpObjectInfo info = mtp.getObjectInfo(handle);
                    if (info == null) throw new IOException("Не удалось прочитать сведения об объекте " + handle);
                    infos.put(handle, info);
                    if (infos.size() % 200 == 0) report("Каталог: " + infos.size() + "/" + handles.length);
                }
                for (MtpObjectInfo info : new ArrayList<>(infos.values())) {
                    String name = info.getName();
                    if (name != null && (name.toLowerCase(Locale.ROOT).endsWith(".jpg") || name.toLowerCase(Locale.ROOT).endsWith(".jpeg"))) {
                        String path = name;
                        int parent = info.getParent();
                        Set<Integer> visited = new HashSet<>();
                        while (parent != 0 && parent != -1 && visited.add(parent)) {
                            MtpObjectInfo folder = infos.get(parent);
                            if (folder == null) { folder = mtp.getObjectInfo(parent); if (folder == null) throw new IOException("Не удалось прочитать папку " + parent); infos.put(parent, folder); }
                            path = folder.getName() + "/" + path;
                            parent = folder.getParent();
                        }
                        photos.add(new Photo(info, Integer.toUnsignedString(storage) + "/" + path));
                    }
                }
            }
            photos.sort(Comparator.comparingLong((Photo p) -> p.info.getDateCreated()).reversed().thenComparing(Photo::path));
            return photos;
    }
    private RelayDb.Item item(Photo photo) throws Exception {
        MtpObjectInfo info = photo.info;
        return new RelayDb.Item(key(photo), info.getName(), info.getCompressedSizeLong(), info.getDateCreated(), info.getDateModified(), null);
    }
    private String key(Photo photo) throws Exception {
        MtpObjectInfo info = photo.info;
        String identity = camera + "/" + photo.path + "/" + info.getCompressedSizeLong() + "/" + info.getDateCreated() + "/" + info.getDateModified();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }


    private void copy(Photo photo, RelayDb.Item item) throws Exception {
        db.remember(item);
        File temp = queue.part(item.key());
        try {
            long start = SystemClock.elapsedRealtime();
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE)) {
                if (!mtp.importFile(photo.info.getObjectHandle(), fd)) throw new IOException("Передача с камеры прервалась: " + item.name());
                if (fd.getStatSize() != item.size()) throw new IOException("Размер полученного JPEG не совпал");
                android.system.Os.fsync(fd.getFileDescriptor());
            }
            queue.finish(item);
            report(String.format(Locale.US, "%s · %.1f МБ · %.2f с", item.name(), item.size() / 1e6, (SystemClock.elapsedRealtime() - start) / 1000.0));
        } finally {
            if (temp.exists() && !temp.delete()) report("Не удалось удалить незавершённую копию");
        }
    }
    @Override public void onTimeout(int startId, int fgsType) {
        cancel = true; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
}

package dev.malordin.fuji;

import android.util.Base64;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

final class RelayQueue {
    static final long RESERVE = 512 * Settings.MIB;
    final File directory;
    final RelayDb db;
    RelayQueue(File directory, RelayDb db) throws IOException {
        this.directory = directory; this.db = db;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Не удалось создать кэш");
        for (File file : files()) if (file.getName().endsWith(".part") && !file.delete()) throw new IOException("Не удалось убрать незавершённую копию");
    }
    File file(String key) {
        if (!key.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid source key");
        return new File(directory, key + ".jpg");
    }
    File part(String key) { return new File(file(key).getPath() + ".part"); }
    private File[] files() throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Кэш недоступен");
        return files;
    }
    int count() throws IOException { return files().length; }
    long bytes() throws IOException { long sum = 0; for (File file : files()) sum += file.length(); return sum; }
    boolean fits(long next, int maxFiles, long maxBytes) throws IOException {
        return next > 0 && count() < maxFiles && bytes() + next <= maxBytes && directory.getUsableSpace() - next >= RESERVE;
    }
    void finish(RelayDb.Item item) throws Exception {
        File temp = part(item.key());
        if (temp.length() != item.size()) throw new IOException("Размер JPEG не совпал с камерой");
        String checksum = checksum(temp);
        if (!temp.renameTo(file(item.key()))) throw new IOException("Не удалось завершить запись JPEG");
        db.cached(item.key(), checksum);
    }
    List<RelayDb.Item> pending() throws Exception {
        List<RelayDb.Item> result = new ArrayList<>();
        for (RelayDb.Item item : db.items()) {
            File file = file(item.key());
            if (!file.exists()) continue;
            if (file.length() != item.size()) throw new IOException("Повреждён локальный кэш: " + item.name());
            if (item.checksum() == null) { // Recover a crash after rename, before the database update.
                String checksum = checksum(file); db.cached(item.key(), checksum);
                item = new RelayDb.Item(item.key(), item.name(), item.size(), item.created(), item.modified(), checksum);
            }
            result.add(item);
        }
        return result;
    }
    void send(RelayDb.Item item, ImmichClient client, String target) throws Exception {
        client.control.check();
        if (!db.confirmed(item.key(), target)) {
            if (!checksum(file(item.key())).equals(item.checksum())) throw new IOException("SHA-1 локального JPEG изменился. Кэш сохранён");
            String id = client.uploadAndVerify(item, file(item.key()));
            client.control.check();
            db.confirm(item, target, id); // Commit the receipt BEFORE deleting the only phone copy.
        }
        client.control.check();
        cleanup(item, target);
    }
    void cleanup(RelayDb.Item item, String target) throws IOException {
        if (!db.confirmed(item.key(), target)) throw new IOException("Нет подтверждения Immich: удаление запрещено");
        File file = file(item.key());
        if (file.exists() && !file.delete()) throw new IOException("JPEG подтверждён в Immich, но кэш не удалось очистить");
    }
    static String checksum(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[128 * 1024]; int count;
            while ((count = in.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        return Base64.encodeToString(hash.digest(), Base64.NO_WRAP);
    }
}

package dev.malordin.fuji;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.net.URI;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Settings {
    static final long MIB = 1024L * 1024L;
    final String endpoint, apiKey;
    final int maxFiles;
    final long maxBytes;

    Settings(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("relay-settings", Context.MODE_PRIVATE);
        endpoint = prefs.getString("endpoint", "");
        String encrypted = prefs.getString("api-key", "");
        apiKey = encrypted.isEmpty() ? "" : decrypt(encrypted);
        maxFiles = prefs.getInt("max-files", 100);
        maxBytes = prefs.getLong("max-bytes", 1024 * MIB);
    }

    static String normalizeEndpoint(String text, boolean allowHttp) throws Exception {
        URI uri = new URI(text.trim());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Укажи адрес сервера без логина, параметров и #");
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !(allowHttp && "http".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalArgumentException("Нужен HTTPS. Для домашнего HTTP включи переключатель ниже");
        String base = uri.toString().replaceAll("/+$", "");
        return base.endsWith("/api") ? base : base + "/api";
    }

    static void save(Context context, String url, String apiKey, int count, long bytes, boolean allowHttp) throws Exception {
        if (count < 1 || count > 1000 || bytes < 16 * MIB || bytes > 32768 * MIB)
            throw new IllegalArgumentException("Кэш: 1–1000 фото, 16–32768 МиБ");
        if (apiKey.isBlank() || apiKey.contains("\n") || apiKey.contains("\r")) throw new IllegalArgumentException("Введи API-ключ Immich");
        String endpoint = normalizeEndpoint(url, allowHttp);
        if (!context.getSharedPreferences("relay-settings", Context.MODE_PRIVATE).edit()
                .putString("endpoint", endpoint).putString("api-key", encrypt(apiKey.trim()))
                .putInt("max-files", count).putLong("max-bytes", bytes).commit())
            throw new IllegalStateException("Не удалось сохранить настройки");
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        String alias = "fujirelay-api-key";
        if (store.containsAlias(alias)) return (SecretKey)store.getKey(alias, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private static String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }
    private static String decrypt(String value) throws Exception {
        String[] parts = value.split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
    }
}

package dev.malordin.fuji;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

final class ImmichClient {
    private final String endpoint, apiKey;
    private String userId;
    private int major;
    ImmichClient(String endpoint, String apiKey) { this.endpoint = endpoint; this.apiKey = apiKey; }

    String connect() throws Exception {
        if (endpoint.isEmpty() || apiKey.isEmpty()) throw new IOException("Сначала укажи сервер и API-ключ в настройках Immich");
        JSONObject version = json("GET", "/server/version", null);
        major = version.getInt("major");
        if (major < 2 || major > 3) throw new IOException("Эта версия Immich ещё не проверена: " + major);
        userId = json("GET", "/users/me", null).getString("id");
        if (userId.isBlank()) throw new IOException("Immich не подтвердил пользователя");
        return endpoint + "#" + userId;
    }

    String uploadAndVerify(RelayDb.Item item, File file) throws Exception {
        JSONObject request = new JSONObject().put("assets", new JSONArray().put(new JSONObject()
            .put("id", item.key()).put("checksum", item.checksum())));
        JSONArray results = json("POST", "/assets/bulk-upload-check", request).getJSONArray("results");
        if (results.length() != 1) throw new IOException("Immich вернул неоднозначный результат проверки");
        JSONObject result = results.getJSONObject(0);
        if (!item.key().equals(result.getString("id"))) throw new IOException("Immich вернул другую фотографию");
        String id;
        if ("reject".equals(result.getString("action"))) {
            if (!"duplicate".equals(result.optString("reason"))) throw new IOException("Immich отклонил файл");
            id = result.getString("assetId");
        } else if ("accept".equals(result.getString("action"))) {
            id = upload(item, file);
        } else throw new IOException("Неизвестный ответ Immich");
        UUID.fromString(id);
        JSONObject asset = json("GET", "/assets/" + id, null);
        // Missing fields fail closed: never infer successful backup from a filename or HTTP 2xx alone.
        if (!id.equals(asset.getString("id")) || !userId.equals(asset.getString("ownerId")) ||
                !item.checksum().equals(asset.getString("checksum")) || asset.getBoolean("isTrashed") || asset.getBoolean("isOffline"))
            throw new IOException("Immich не подтвердил доступный оригинал с тем же SHA-1. Кэш сохранён");
        return id;
    }

    private String upload(RelayDb.Item item, File file) throws Exception {
        String boundary = "FujiRelay" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder fields = new StringBuilder();
        field(fields, boundary, "fileCreatedAt", Instant.ofEpochMilli(Math.max(0, item.created())).toString());
        field(fields, boundary, "fileModifiedAt", Instant.ofEpochMilli(Math.max(0, item.modified())).toString());
        // These fields are required by Immich 2.x and removed from the 3.x API.
        if (major == 2) { field(fields, boundary, "deviceId", "fujirelay"); field(fields, boundary, "deviceAssetId", item.key()); }
        String name = item.name().replaceAll("[^A-Za-z0-9._-]", "_");
        fields.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"assetData\"; filename=\"")
            .append(name).append("\"\r\nContent-Type: image/jpeg\r\n\r\n");
        byte[] prefix = fields.toString().getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = open("POST", "/assets");
        try {
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("x-immich-checksum", item.checksum());
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(prefix.length + item.size() + suffix.length);
            try (OutputStream out = connection.getOutputStream(); InputStream in = new FileInputStream(file)) {
                out.write(prefix);
                byte[] buffer = new byte[128 * 1024];
                int length;
                while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
                out.write(suffix);
            }
            JSONObject response = response(connection);
            if (!"created".equals(response.optString("status")) && !"duplicate".equals(response.optString("status")))
                throw new IOException("Immich не подтвердил загрузку");
            return response.getString("id");
        } finally { connection.disconnect(); }
    }

    private static void field(StringBuilder out, String boundary, String name, String value) {
        out.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"")
            .append(name).append("\"\r\n\r\n").append(value).append("\r\n");
    }
    private HttpURLConnection open(String method, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(endpoint + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000); connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("x-api-key", apiKey);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }
    private JSONObject json(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = open(method, path);
        try {
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(data.length);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) { out.write(data); }
            }
            return response(connection);
        } finally { connection.disconnect(); }
    }
    private static JSONObject response(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new IOException("Immich: HTTP " + code + ". Кэш сохранён; проверь адрес и права API-ключа");
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > 1024 * 1024) throw new IOException("Слишком большой ответ Immich");
                out.write(buffer, 0, count);
            }
            return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        }
    }
}

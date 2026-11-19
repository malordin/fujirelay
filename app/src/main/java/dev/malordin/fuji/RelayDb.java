package dev.malordin.fuji;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

final class RelayDb extends SQLiteOpenHelper {
    record Item(String key, String name, long size, long created, long modified, String checksum) {}
    RelayDb(Context context) { this(context, "relay.db"); }
    RelayDb(Context context, String name) { super(context, name, null, 1); setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE items (source_key TEXT PRIMARY KEY, name TEXT NOT NULL, size INTEGER NOT NULL, created INTEGER NOT NULL, modified INTEGER NOT NULL, checksum TEXT)");
        db.execSQL("CREATE TABLE receipts (source_key TEXT NOT NULL, target TEXT NOT NULL, asset_id TEXT NOT NULL, checksum TEXT NOT NULL, verified_at INTEGER NOT NULL, PRIMARY KEY(source_key,target))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) { throw new IllegalStateException("Unsupported database version"); }
    void remember(Item item) {
        ContentValues values = new ContentValues();
        values.put("source_key", item.key); values.put("name", item.name); values.put("size", item.size);
        values.put("created", item.created); values.put("modified", item.modified);
        getWritableDatabase().insertWithOnConflict("items", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }
    void cached(String key, String checksum) {
        ContentValues values = new ContentValues(); values.put("checksum", checksum);
        if (getWritableDatabase().update("items", values, "source_key=?", new String[]{key}) != 1)
            throw new IllegalStateException("Unknown cache item");
    }
    boolean confirmed(String key, String target) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT 1 FROM receipts WHERE source_key=? AND target=?", new String[]{key,target})) { return cursor.moveToFirst(); }
    }
    void confirm(Item item, String target, String assetId) {
        ContentValues values = new ContentValues();
        values.put("source_key", item.key); values.put("target", target); values.put("asset_id", assetId);
        values.put("checksum", item.checksum); values.put("verified_at", System.currentTimeMillis());
        if (getWritableDatabase().insertWithOnConflict("receipts", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1)
            throw new IllegalStateException("Не удалось записать подтверждение Immich");
    }
    List<Item> items() {
        List<Item> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT source_key,name,size,created,modified,checksum FROM items ORDER BY rowid", null)) {
            while(c.moveToNext()) result.add(new Item(c.getString(0),c.getString(1),c.getLong(2),c.getLong(3),c.getLong(4),c.getString(5)));
        }
        return result;
    }
    int confirmedCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM receipts", null)) { c.moveToFirst(); return c.getInt(0); }
    }
}

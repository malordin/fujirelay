package dev.malordin.fuji;

import android.content.*;
import java.time.*;
import java.util.*;

final class PhotoSelection {
    final String mode;
    final long from,until;
    final Set<String> keys;
    PhotoSelection(String mode,long from,long until,Set<String> keys) { this.mode=mode;this.from=from;this.until=until;this.keys=Set.copyOf(keys); }
    static PhotoSelection load(Context context) {
        SharedPreferences p=context.getSharedPreferences("photo-selection",0);
        String mode=p.getString("mode","all");
        if(mode.equals("today")||mode.equals("yesterday")) return day(mode,LocalDate.now(ZoneId.systemDefault()),ZoneId.systemDefault());
        return new PhotoSelection(mode,p.getLong("from",0),p.getLong("until",Long.MAX_VALUE),p.getStringSet("keys",Set.of()));
    }
    static PhotoSelection day(String mode,LocalDate today,ZoneId zone) {
        LocalDate date=mode.equals("yesterday")?today.minusDays(1):today;
        return range(mode,date,date,zone);
    }
    static PhotoSelection range(String mode,LocalDate first,LocalDate last,ZoneId zone) {
        if(last.isBefore(first)) throw new IllegalArgumentException("Конец периода раньше начала");
        return new PhotoSelection(mode,first.atStartOfDay(zone).toInstant().toEpochMilli(),last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),Set.of());
    }
    boolean matches(String key,long created) { return mode.equals("all") || (mode.equals("manual")?keys.contains(key):created>=from&&created<until); }
    void save(Context context) {
        if(!context.getSharedPreferences("photo-selection",0).edit().putString("mode",mode).putLong("from",from).putLong("until",until).putStringSet("keys",keys).commit()) throw new IllegalStateException("Не удалось сохранить выбор");
    }
    String title() { return switch(mode) { case "today"->"Сегодня";case "yesterday"->"Вчера";case "manual"->"Выбрано: "+keys.size();case "range"->java.time.format.DateTimeFormatter.ofPattern("d MMM",Locale.getDefault()).format(Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault()))+" — "+java.time.format.DateTimeFormatter.ofPattern("d MMM",Locale.getDefault()).format(Instant.ofEpochMilli(until-1).atZone(ZoneId.systemDefault()));default->"Все фотографии"; }; }
}

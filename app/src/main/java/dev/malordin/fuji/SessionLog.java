package dev.malordin.fuji;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class SessionLog {
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss",Locale.ROOT);
    static String format(String message,Instant instant,ZoneId zone){
        String prefix="["+TIME.format(instant.atZone(zone))+"] ";
        StringBuilder out=new StringBuilder();
        for(String line:message.split("\\R"))if(!line.isBlank())out.append(prefix).append(line).append('\n');
        return out.toString();
    }
}

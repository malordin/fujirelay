package dev.malordin.fuji;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class PhotoCatalog {
    record Shot(String key,String name,long created,long size) {}
    static volatile List<Shot> shots=List.of();
    static volatile long revision;
    static File preview(Context context,String key) {
        if(!key.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid photo key");
        return new File(new File(context.getCacheDir(),"previews"),key+".jpg");
    }
    static void save(Context context,List<Shot> list) throws Exception {
        JSONArray array=new JSONArray();
        for(Shot shot:list) array.put(new JSONObject().put("key",shot.key()).put("name",shot.name()).put("created",shot.created()).put("size",shot.size()));
        AtomicFile file=new AtomicFile(new File(context.getFilesDir(),"catalog.json"));
        FileOutputStream out=file.startWrite();
        try { out.write(array.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out); } catch(Exception e){file.failWrite(out);throw e;}
        shots=List.copyOf(list);revision++;
    }
    static void load(Context context) throws Exception {
        if(!shots.isEmpty()) return;
        File file=new File(context.getFilesDir(),"catalog.json");if(!file.exists()) return;
        JSONArray array=new JSONArray(new String(new AtomicFile(file).readFully(),StandardCharsets.UTF_8));
        List<Shot> list=new ArrayList<>();
        for(int i=0;i<array.length();i++){JSONObject o=array.getJSONObject(i);list.add(new Shot(o.getString("key"),o.getString("name"),o.getLong("created"),o.getLong("size")));}
        shots=List.copyOf(list); revision++;
    }
}

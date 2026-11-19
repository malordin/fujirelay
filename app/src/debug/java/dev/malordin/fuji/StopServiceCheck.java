package dev.malordin.fuji;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.Button;
import java.io.File;

/** Stops a read-only catalog operation through the real screen; never uploads anything. */
public final class StopServiceCheck extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        if(ImportService.busy)throw new IllegalStateException("A transfer is already running");
        File cache=new File(getTargetContext().getFilesDir(),"relay");long[] before=usage(cache);
        MainActivity screen=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        runOnMainSync(()->getTargetContext().startForegroundService(new Intent(getTargetContext(),ImportService.class).setAction("scan")));
        long deadline=SystemClock.elapsedRealtime()+3000;
        while(!ImportService.busy&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(10);
        if(!ImportService.busy)throw new AssertionError("Catalog did not start");
        Thread.sleep(100);
        runOnMainSync(()->{try{var render=MainActivity.class.getDeclaredMethod("render");render.setAccessible(true);render.invoke(screen);Button stop=find(screen.getWindow().getDecorView());if(stop==null||stop.getVisibility()!=View.VISIBLE)throw new AssertionError("Stop button missing");stop.performClick();}catch(Exception e){throw new RuntimeException(e);}});
        long start=SystemClock.elapsedRealtime();
        while(ImportService.busy&&SystemClock.elapsedRealtime()-start<3000)Thread.sleep(10);
        if(ImportService.busy||!ImportService.phase.equals("Остановлено")||ImportService.failed)throw new AssertionError("Scan did not stop cleanly: "+ImportService.phase);
        long[] after=usage(cache);if(before[0]!=after[0]||before[1]!=after[1])throw new AssertionError("User's queued files changed");
        result.putString("stream","\nPASS: real Stop button cancels camera catalog in "+(SystemClock.elapsedRealtime()-start)+" ms, ends service, preserves queued file count and bytes. No upload.\n");finish(-1,result);
    }catch(Throwable error){result.putString("stream","\nFAIL: "+error+"\n");finish(1,result);}}
    private static Button find(View view){if(view instanceof Button b&&b.getText().toString().equals("Остановить передачу"))return b;if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){Button found=find(group.getChildAt(i));if(found!=null)return found;}return null;}
    private static long[] usage(File dir){File[] files=dir.listFiles();long count=0,bytes=0;if(files!=null)for(File f:files){count++;bytes+=f.length();}return new long[]{count,bytes};}
}

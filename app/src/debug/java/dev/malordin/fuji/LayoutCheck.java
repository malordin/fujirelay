package dev.malordin.fuji;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/** Real window geometry checks; never sends photos or changes saved settings. */
@android.annotation.TargetApi(30)
public final class LayoutCheck extends Instrumentation {
    private MainActivity screen;
    private final List<String> passed=new ArrayList<>();
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        if(Build.VERSION.SDK_INT<30)throw new IllegalStateException("Geometry harness requires Android 11+");
        if(ImportService.busy)throw new IllegalStateException("Finish current transfer first");
        screen=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        PhotoCatalog.load(getTargetContext());Thread.sleep(400);waitForIdleSync();
        picker(screen,"portrait");
        Configuration config=new Configuration(screen.getResources().getConfiguration());config.fontScale=1.6f;
        ContextThemeWrapper larger=new ContextThemeWrapper(screen,R.style.AppTheme);larger.applyOverrideConfiguration(config);
        picker(larger,"large font");
        settings(true);terminal();calendar();
        ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);
        ui(()->{screen.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);return null;});
        MainActivity rotated=(MainActivity)waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);
        if(rotated==null)throw new AssertionError("Landscape Activity not recreated");screen=rotated;Thread.sleep(400);waitForIdleSync();
        picker(screen,"landscape");settings(true);terminal();calendar();
        result.putString("stream","\nPASS "+passed.size()+" geometry checks\n"+String.join("\n",passed)+"\n");finish(-1,result);
    }catch(Throwable e){result.putString("stream","\nFAIL: "+e+"\nPassed: "+passed+"\n");finish(1,result);}
    finally{if(screen!=null)ui(()->{screen.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);return null;});}}
    private void picker(Context context,String label)throws Exception{
        PhotoPicker dialog=ui(()->{PhotoPicker d=new PhotoPicker(context,()->{});d.show();return d;});settle();
        ui(()->{
            Window window=dialog.getWindow();View root=window.getDecorView();
            find(root,v->"all".equals(v.getTag())).performClick();
            inside(window,find(root,v->"Закрыть выбор".equals(v.getContentDescription())),false);
            inside(window,find(root,v->v instanceof Button),false);
            GridView grid=(GridView)find(root,v->v instanceof GridView);inside(window,grid,false);
            if(grid.getChildCount()>0){
                View tile=grid.getChildAt(0);tile.performClick();
            }
            return null;
        });settle();
        ui(()->{
            View root=dialog.getWindow().getDecorView();
            if(findOptional(root,v->v instanceof TextView text&&(text.getText().toString().equals("○")||text.getText().toString().equals("✓")))!=null)throw new AssertionError("Unicode selection glyph remains");
            GridView grid=(GridView)find(root,v->v instanceof GridView);
            if(grid.getChildCount()>0){ViewGroup tile=(ViewGroup)grid.getChildAt(0);View preview=tile.getChildAt(0);if(Math.abs(preview.getWidth()-preview.getHeight())>1)throw new AssertionError("Preview is not square");}
            dialog.dismiss();return null;
        });passed.add(label+": safe toolbar/footer/grid, square previews, no glyph markers");
    }
    private void settings(boolean keyboard)throws Exception{
        ScreenDialog dialog=ui(()->{var method=MainActivity.class.getDeclaredMethod("settings");method.setAccessible(true);return (ScreenDialog)method.invoke(screen);});settle();
        ui(()->{inside(dialog.getWindow(),find(dialog.getWindow().getDecorView(),v->v instanceof Button b&&b.getText().toString().equals("Сохранить")),false);inside(dialog.getWindow(),find(dialog.getWindow().getDecorView(),v->v instanceof TextView t&&t.getText().toString().equals("Закрыть")),false);return null;});
        if(keyboard){
            ui(()->{EditText input=(EditText)find(dialog.getWindow().getDecorView(),v->v instanceof EditText);input.requestFocus();screen.getSystemService(InputMethodManager.class).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);return null;});Thread.sleep(1000);waitForIdleSync();
            ui(()->{if(!dialog.getWindow().getDecorView().getRootWindowInsets().isVisible(WindowInsets.Type.ime()))throw new AssertionError("Keyboard did not appear");inside(dialog.getWindow(),find(dialog.getWindow().getDecorView(),v->v instanceof Button b&&b.getText().toString().equals("Сохранить")),true);if(find(dialog.getWindow().getDecorView(),v->v instanceof ScrollView).getHeight()<56*screen.getResources().getDisplayMetrics().density)throw new AssertionError("Keyboard squeezed form viewport");dialog.dismiss();return null;});
        }else ui(()->{dialog.dismiss();return null;});
        passed.add("settings: actions inside bars and above keyboard");
    }
    private void terminal()throws Exception{
        ScreenDialog dialog=ui(()->{var method=MainActivity.class.getDeclaredMethod("openLog");method.setAccessible(true);return (ScreenDialog)method.invoke(screen);});settle();
        ui(()->{View root=dialog.getWindow().getDecorView();inside(dialog.getWindow(),find(root,v->v instanceof LogTerminal),false);inside(dialog.getWindow(),find(root,v->v instanceof TextView t&&t.getText().toString().equals("Копировать")),false);dialog.dismiss();return null;});passed.add("terminal: bounded viewport and copy action");
    }
    private void calendar()throws Exception{
        DatePickerDialog dialog=ui(()->{DatePickerDialog d=new DatePickerDialog(screen,(v,y,m,day)->{},2026,10,19);d.show();return d;});settle();
        ui(()->{inside(dialog.getWindow(),dialog.getButton(DatePickerDialog.BUTTON_POSITIVE),false);dialog.dismiss();return null;});passed.add("calendar: confirmation inside window");
    }
    private void settle()throws Exception{Thread.sleep(400);waitForIdleSync();}
    private static void inside(Window window,View view,boolean keyboard){
        View decor=window.getDecorView();int[] base=new int[2];decor.getLocationOnScreen(base);
        Insets bars=decor.getRootWindowInsets().getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|(keyboard?WindowInsets.Type.ime():0));
        Rect safe=new Rect(base[0]+bars.left,base[1]+bars.top,base[0]+decor.getWidth()-bars.right,base[1]+decor.getHeight()-bars.bottom);
        int[] at=new int[2];view.getLocationOnScreen(at);Rect actual=new Rect(at[0],at[1],at[0]+view.getWidth(),at[1]+view.getHeight());
        if(view.getWidth()==0||view.getHeight()==0||!safe.contains(actual))throw new AssertionError(view.getClass().getSimpleName()+" "+actual+" outside "+safe);
    }
    private static View find(View view,Predicate<View> test){View result=findOptional(view,test);if(result==null)throw new AssertionError("Control not found");return result;}
    private static View findOptional(View view,Predicate<View> test){if(test.test(view))return view;if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){View found=findOptional(group.getChildAt(i),test);if(found!=null)return found;}return null;}
    private <T>T ui(Work<T> work){AtomicReference<T> value=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();runOnMainSync(()->{try{value.set(work.run());}catch(Throwable e){error.set(e);}});if(error.get()!=null)throw new AssertionError(error.get());return value.get();}
    private interface Work<T>{T run()throws Exception;}
}

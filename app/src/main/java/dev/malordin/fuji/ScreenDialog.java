package dev.malordin.fuji;

import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Insets;
import android.os.Build;
import android.view.*;
import android.widget.FrameLayout;

/** One inset policy for every full-screen surface, including the keyboard. */
class ScreenDialog extends Dialog {
    private MainActivity owner;
    ScreenDialog(Context context) {
        super(context,R.style.AppTheme);
        Context base=context;
        while(base instanceof ContextWrapper wrapper && !(base instanceof MainActivity)){Context next=wrapper.getBaseContext();if(next==base)break;base=next;}
        if(base instanceof MainActivity activity){owner=activity;owner.trackDialog(this);}
        Window window=getWindow();
        window.setWindowAnimations(R.style.SheetAnimation);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if(Build.VERSION.SDK_INT>=30)window.setDecorFitsSystemWindows(false);
        else window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    @Override public void setContentView(View content) {
        FrameLayout safe=new FrameLayout(getContext());
        safe.setBackgroundColor(0xff101213);
        safe.addView(content,new FrameLayout.LayoutParams(-1,-1));
        safe.setOnApplyWindowInsetsListener((view,insets)->{
            if(Build.VERSION.SDK_INT>=30){
                Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            }else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        super.setContentView(safe);
        safe.requestApplyInsets();
    }
    @Override public void dismiss(){super.dismiss();if(owner!=null)owner.untrackDialog(this);}
    @Override public void show(){
        super.show();
        getWindow().setLayout(-1,-1);
        getWindow().setStatusBarColor(0xff101213);
        getWindow().setNavigationBarColor(0xff101213);
    }
}

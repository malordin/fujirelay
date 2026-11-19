package dev.malordin.fuji;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.widget.*;

final class LogTerminal extends ScrollView {
    private final TextView text;
    private String value="";
    LogTerminal(Context context){
        super(context);
        int padding=Math.round(14*getResources().getDisplayMetrics().density);
        GradientDrawable background=new GradientDrawable();background.setColor(0xff0b0e10);background.setCornerRadius(padding);
        setBackground(background);setFillViewport(true);setClipToOutline(true);
        text=new TextView(context);text.setTypeface(Typeface.MONOSPACE);text.setTextSize(11);text.setTextColor(0xffd2d9d9);
        text.setLineSpacing(4*getResources().getDisplayMetrics().density,1);text.setPadding(padding,padding,padding,padding);
        text.setTextIsSelectable(true);text.setContentDescription("Терминал журнала");addView(text,new LayoutParams(-1,-2));
    }
    void openOnTap(Runnable action){text.setTextIsSelectable(false);text.setOnClickListener(v->action.run());}
    void setLog(String log){
        if(value.equals(log)&&text.length()>0)return;
        boolean bottom=text.getHeight()-getScrollY()-getHeight()<=Math.round(32*getResources().getDisplayMetrics().density);
        boolean initial=value.isEmpty();value=log;
        SpannableStringBuilder styled=new SpannableStringBuilder(log.isBlank()?"Пока пусто":log.stripTrailing());
        int start=0;
        for(String line:styled.toString().split("\n")){
            int end=start+line.length();
            if(line.matches("^\\[\\d{4}-\\d{2}-\\d{2} .*")){
                int close=line.indexOf(']');
                styled.setSpan(new ForegroundColorSpan(0xff75878f),start,start+close+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if(line.contains("Ошибка:"))styled.setSpan(new ForegroundColorSpan(0xffe7af92),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            start=end+1;
        }
        text.setText(styled);
        if(initial||bottom)post(()->fullScroll(FOCUS_DOWN));
    }
}

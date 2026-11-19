package dev.malordin.fuji;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.hardware.usb.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String USB_PERMISSION = "dev.malordin.fuji.usb.PERMISSION";
    private static final int BG=0xff101213, CARD=0xff1b1e20, LINE=0xff303436, TEXT=0xfff2efe9, MUTED=0xff909798, ACCENT=0xffeabb98;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView heading, subtitle, cacheCount, cacheSize, delivered, journalTitle;
    private LinearLayout journalRows;
    private Button primary;
    private ProgressBar progress;
    private boolean expanded, configured;
        private long maxBytes=1024*Settings.MIB;
    private String pendingMode, journal="", renderedJournal="";
    private View settingsButton, moreButton;
    private final BroadcastReceiver permission = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device=camera();
            if (device != null && getSystemService(UsbManager.class).hasPermission(device) && pendingMode != null) start(pendingMode);
            else message("Доступ к камере", "Разрешение USB не получено.");
            pendingMode=null;
        }
    };
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 1000); }
    };

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        expanded=state!=null && state.getBoolean("journal");
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        LinearLayout root=column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view,insets)-> {
            if(Build.VERSION.SDK_INT>=30) { Insets bars=insets.getInsets(WindowInsets.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); }
            else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout bar=row(); bar.setPadding(dp(24),dp(8),dp(12),dp(8));
        TextView brand=label("FujiRelay",21,TEXT); brand.setTypeface(null,Typeface.BOLD); bar.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        settingsButton=icon(bar,"settings","Настройки",v->settings());
        moreButton=icon(bar,"more","Дополнительно",this::more);
        root.addView(bar);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body=column(); body.setPadding(dp(24),dp(12),dp(24),dp(20));
        LinearLayout hero=column(); hero.setGravity(Gravity.CENTER); hero.setPadding(0,dp(14),0,dp(24));
        hero.addView(new CameraArt(this),new LinearLayout.LayoutParams(dp(200),dp(150)));
        heading=label("X-T30 II",27,TEXT); heading.setTypeface(null,Typeface.BOLD); heading.setGravity(Gravity.CENTER); hero.addView(heading);
        subtitle=label("",14,MUTED); subtitle.setGravity(Gravity.CENTER); subtitle.setPadding(0,dp(8),0,0); hero.addView(subtitle);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(dp(100),dp(4)); pp.topMargin=dp(18); hero.addView(progress,pp);
        body.addView(hero);
        LinearLayout metrics=row(); metrics.setGravity(Gravity.TOP); metrics.setBackground(shape(CARD,22)); metrics.setPadding(dp(20),dp(20),dp(20),dp(20));
        LinearLayout left=column(); metrics.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        cacheCount=label("0",30,TEXT); left.addView(cacheCount); left.addView(label("В очереди",12,MUTED));
        cacheSize=label("",11,MUTED); cacheSize.setPadding(0,dp(10),0,0); left.addView(cacheSize);
        View divider=new View(this); divider.setBackgroundColor(LINE); LinearLayout.LayoutParams div=new LinearLayout.LayoutParams(dp(1),dp(82)); div.setMargins(dp(20),0,dp(24),0); metrics.addView(divider,div);
        LinearLayout right=column(); metrics.addView(right,new LinearLayout.LayoutParams(0,-2,1));
        delivered=label("0",30,TEXT); right.addView(delivered); right.addView(label("Передано",12,MUTED));
        body.addView(metrics); gap(body,24);
        LinearLayout journalHeader=row(); journalHeader.setMinimumHeight(dp(52)); journalHeader.setBackground(ripple(BG,12));
        journalHeader.setPadding(dp(4),0,dp(4),0); journalHeader.setId(View.generateViewId()); journalHeader.setContentDescription("Журнал передачи");
        journalTitle=label("Журнал",14,MUTED); journalHeader.addView(journalTitle,new LinearLayout.LayoutParams(0,-2,1));
        TextView chevron=label("⌄",22,MUTED); journalHeader.addView(chevron);
        journalHeader.setOnClickListener(v->{ expanded=!expanded; chevron.setText(expanded?"⌃":"⌄"); renderJournal(); }); body.addView(journalHeader);
        chevron.setText(expanded?"⌃":"⌄");
        journalRows=column(); body.addView(journalRows); scroll.addView(body); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=column(); footer.setPadding(dp(24),dp(12),dp(24),dp(20));
        primary=new Button(this); primary.setAllCaps(false); primary.setTextSize(16); primary.setTypeface(null,Typeface.BOLD); primary.setTextColor(BG);
        primary.setBackground(ripple(ACCENT,18)); primary.setOnClickListener(v->primaryAction()); footer.addView(primary,new LinearLayout.LayoutParams(-1,dp(56))); root.addView(footer);
        setContentView(root);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(permission,new IntentFilter(USB_PERMISSION),Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(permission,new IntentFilter(USB_PERMISSION));
        loadState(); render();
    }
    private void loadState() {
        new Thread(()-> {
            try(RelayDb db=new RelayDb(this)) {
                if(!ImportService.busy) {
                    File[] files=new File(getFilesDir(),"relay").listFiles(); long bytes=0; int count=0;
                    if(files!=null) for(File file:files) { count++; bytes+=file.length(); }
                    ImportService.cachedFiles=count; ImportService.cachedBytes=bytes; ImportService.confirmedFiles=db.confirmedCount();
                }
                Settings settings=new Settings(this);
                String saved=""; File file=new File(getFilesDir(),"last-import.log");
                if(file.isFile()) try(java.io.RandomAccessFile in=new java.io.RandomAccessFile(file,"r")) {
                    long offset=Math.max(0,in.length()-16000); in.seek(offset); byte[] bytes=new byte[(int)(in.length()-offset)]; in.readFully(bytes);
                    saved=new String(bytes,StandardCharsets.UTF_8); if(offset>0) saved=saved.substring(saved.indexOf('\n')+1);
                }
                String log=saved;
                handler.post(()-> { if(isDestroyed()) return; configured=!settings.endpoint.isEmpty(); maxBytes=settings.maxBytes; if(ImportService.status.isEmpty()) journal=log; render(); });
            } catch(Exception error) { handler.post(()->{ if(!isDestroyed()) message("Настройки", "Не удалось прочитать настройки. Сохрани их заново."); }); }
        },"relay-dashboard").start();
    }
    private void render() {
        if(primary==null) return;
        boolean busy=ImportService.busy, attached=camera()!=null;
        heading.setText(busy?ImportService.phase:ImportService.failed?"Передача приостановлена":attached?"X-T30 II":ImportService.cachedFiles>0?"Очередь готова":"Подключите камеру");
        subtitle.setText(busy?ImportService.cancel?"Завершаем текущую операцию":"Передача фотографий":ImportService.failed?"Подробности в журнале":!configured?"Immich не подключён":attached?"Готова к передаче":ImportService.cachedFiles>0?"Очередь можно отправить без камеры":"");
        progress.setVisibility(busy?View.VISIBLE:View.INVISIBLE);
        cacheCount.setText(String.valueOf(ImportService.cachedFiles));
        cacheSize.setText(size(ImportService.cachedBytes)+" / "+size(maxBytes));
        delivered.setText(String.valueOf(ImportService.confirmedFiles));
        primary.setText(busy?ImportService.cancel?"Останавливаем…":"Приостановить":!configured?"Подключить Immich":ImportService.cachedFiles>0?"Продолжить":"Передать фотографии");
        primary.setEnabled(busy?!ImportService.cancel:!configured||attached||ImportService.cachedFiles>0);
        primary.setAlpha(primary.isEnabled()?1f:0.4f); settingsButton.setEnabled(!busy); moreButton.setEnabled(!busy);
        if(!ImportService.status.isEmpty()) journal=ImportService.status;
        journalTitle.setText(ImportService.failed?"Журнал · ошибка":"Журнал"); journalTitle.setTextColor(ImportService.failed?ACCENT:MUTED);
        renderJournal();
    }
    private void renderJournal() {
        journalRows.setVisibility(expanded?View.VISIBLE:View.GONE);
        if(!expanded || renderedJournal.equals(journal) && journalRows.getChildCount()>0) return;
        renderedJournal=journal; journalRows.removeAllViews();
        if(journal.isBlank()) { TextView empty=label("Пока пусто",13,MUTED); empty.setPadding(dp(4),dp(8),0,dp(16)); journalRows.addView(empty); return; }
        String[] lines=journal.strip().split("\\n");
        for(int i=lines.length-1;i>=Math.max(0,lines.length-30);i--) {
            String entry=lines[i]; if(entry.isBlank()) continue;
            TextView item=label(entry,12,entry.startsWith("Ошибка:")?ACCENT:MUTED); item.setMaxLines(2); item.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.setMinHeight(dp(48)); item.setGravity(Gravity.CENTER_VERTICAL); item.setPadding(dp(12),dp(10),dp(12),dp(10)); item.setBackground(ripple(CARD,10));
            item.setOnClickListener(v->{ TextView detail=label(entry,14,TEXT); detail.setTextIsSelectable(true); detail.setPadding(dp(24),dp(20),dp(24),dp(12)); new AlertDialog.Builder(this).setView(detail).setPositiveButton("Закрыть",null).setNeutralButton("Копировать",(d,w)->getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("FujiRelay",entry))).show(); });
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(6); journalRows.addView(item,lp);
        }
    }
    private void primaryAction() {
        if(ImportService.busy) { ImportService.cancel=true; render(); return; }
        if(!configured) { settings(); return; }
        connect(camera()==null && ImportService.cachedFiles>0?"drain":"all");
    }
    private void more(View anchor) {
        PopupMenu menu=new PopupMenu(this,anchor);
        menu.getMenu().add("Проверить камеру").setOnMenuItemClickListener(item->{connect("scan");return true;});
        menu.getMenu().add("Передать один кадр").setOnMenuItemClickListener(item->{if(!configured) settings(); else connect("one");return true;});
        menu.show();
    }
    private void settings() {
        Dialog dialog=new Dialog(this,dev.malordin.fuji.R.style.AppTheme);
        LinearLayout screen=column(); screen.setBackgroundColor(BG); screen.setPadding(dp(24),dp(20),dp(24),dp(20));
        LinearLayout toolbar=row(); TextView title=label("Настройки",24,TEXT); title.setTypeface(null,Typeface.BOLD); toolbar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView close=label("Закрыть",14,ACCENT); close.setGravity(Gravity.CENTER); close.setMinHeight(dp(48)); close.setMinWidth(dp(64)); close.setBackground(ripple(BG,12)); close.setOnClickListener(v->dialog.dismiss()); toolbar.addView(close); screen.addView(toolbar);
        ScrollView scroll=new ScrollView(this); LinearLayout form=column(); gap(form,24);
        LinearLayout section=row(); section.addView(label("Immich",15,TEXT),new LinearLayout.LayoutParams(0,-2,1));
        TextView help=label("?",15,MUTED); help.setGravity(Gravity.CENTER); help.setMinWidth(dp(48)); help.setMinHeight(dp(48)); help.setContentDescription("Права API-ключа"); help.setOnClickListener(v->message("API-ключ", "Нужны права asset.upload, asset.read и user.read.")); section.addView(help); form.addView(section);
        EditText url=input(form,"Адрес сервера","https://photos.example.com",false,false);
        EditText key=input(form,"API-ключ","",true,false);
        gap(form,28); form.addView(label("Кэш на телефоне",15,TEXT)); gap(form,8);
        EditText count=input(form,"Фотографий","100",false,true); count.setText("100");
        EditText bytes=input(form,"Размер, МиБ","1024",false,true); bytes.setText("1024");
        gap(form,16); CheckBox http=new CheckBox(this); http.setText("HTTP в локальной сети"); http.setTextSize(13); http.setTextColor(MUTED); form.addView(http);
        TextView warning=label("Соединение без шифрования",12,MUTED); form.addView(warning); warning.setVisibility(View.GONE);
        http.setOnCheckedChangeListener((view,checked)->warning.setVisibility(checked?View.VISIBLE:View.GONE));
        try { Settings settings=new Settings(this); url.setText(settings.endpoint); key.setText(settings.apiKey); count.setText(String.valueOf(settings.maxFiles)); bytes.setText(String.valueOf(settings.maxBytes/Settings.MIB)); http.setChecked(settings.endpoint.startsWith("http://")); } catch(Exception ignored) { key.setText(""); }
        scroll.addView(form); screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Button save=new Button(this); save.setText("Сохранить"); save.setAllCaps(false); save.setTextColor(BG); save.setBackground(ripple(ACCENT,18));
        save.setOnClickListener(v->{try {Settings.save(this,url.getText().toString(),key.getText().toString(),Integer.parseInt(count.getText().toString()),Math.multiplyExact(Long.parseLong(bytes.getText().toString()),Settings.MIB),http.isChecked()); loadState(); dialog.dismiss();}catch(Exception e){Toast.makeText(this,e.getMessage()==null?"Проверь значения":e.getMessage(),Toast.LENGTH_LONG).show();}});
        screen.addView(save,new LinearLayout.LayoutParams(-1,dp(56))); dialog.setContentView(screen); dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show(); dialog.getWindow().setLayout(-1,-1);
    }
    private EditText input(LinearLayout parent,String name,String hint,boolean secret,boolean number) {
        gap(parent,14); parent.addView(label(name,12,MUTED)); gap(parent,6);
        EditText field=new EditText(this); field.setTextSize(15); field.setTextColor(TEXT); field.setHintTextColor(MUTED); field.setHint(hint); field.setSingleLine(true); field.setPadding(dp(14),dp(12),dp(14),dp(12)); field.setBackground(shape(CARD,12));
        field.setInputType(number?InputType.TYPE_CLASS_NUMBER:secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI); field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); parent.addView(field); return field;
    }
    private UsbDevice camera() { for(UsbDevice device:getSystemService(UsbManager.class).getDeviceList().values()) if(device.getVendorId()==0x04cb && device.getProductId()==0x02f3) return device; return null; }
    private void connect(String mode) {
        if(ImportService.busy) return;
        if("drain".equals(mode)) { start(mode); return; }
        UsbDevice device=camera(); if(device==null) { message("Камера не найдена","Проверь USB-кабель и режим USB CARD READER на камере."); return; }
        UsbManager manager=getSystemService(UsbManager.class);
        if(manager.hasPermission(device)) start(mode);
        else { pendingMode=mode; manager.requestPermission(device,PendingIntent.getBroadcast(this,0,new Intent(USB_PERMISSION).setPackage(getPackageName()),PendingIntent.FLAG_IMMUTABLE)); }
    }
    private void start(String mode) {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},1);
        startForegroundService(new Intent(this,ImportService.class).setAction(mode));
    }
    private void message(String title,String text) { new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("Закрыть",null).show(); }
    private View icon(LinearLayout parent,String type,String description,View.OnClickListener click) { Icon view=new Icon(this,type); view.setContentDescription(description); view.setBackground(ripple(BG,24)); view.setOnClickListener(click); view.setFocusable(true); parent.addView(view,new LinearLayout.LayoutParams(dp(48),dp(48))); return view; }
    private LinearLayout column() { LinearLayout view=new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view=new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView label(String value,int sp,int color) { TextView view=new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(color); return view; }
    private GradientDrawable shape(int color,int radius) { GradientDrawable drawable=new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private Drawable ripple(int color,int radius) { return new RippleDrawable(android.content.res.ColorStateList.valueOf(0x337f8b8b),shape(color,radius),shape(Color.WHITE,radius)); }
    private void gap(LinearLayout parent,int height) { parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height))); }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private static String size(long bytes) { return bytes>=1024*Settings.MIB?String.format(Locale.getDefault(),"%.1f ГБ",bytes/(1024.0*Settings.MIB)):String.format(Locale.getDefault(),"%.0f МБ",bytes/(double)Settings.MIB); }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putBoolean("journal",expanded); super.onSaveInstanceState(out); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); unregisterReceiver(permission); super.onDestroy(); }

    private class Icon extends View {
        private final String type; private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(Context context,String type) { super(context); this.type=type; }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.save(); canvas.translate(getWidth()/2f,getHeight()/2f); canvas.scale(getResources().getDisplayMetrics().density,getResources().getDisplayMetrics().density); paint.setColor(MUTED); paint.setStrokeWidth(1.6f); paint.setStyle(Paint.Style.STROKE);
            if(type.equals("more")) { paint.setStyle(Paint.Style.FILL); for(int y=-6;y<=6;y+=6) canvas.drawCircle(0,y,1.5f,paint); }
            else { canvas.drawCircle(0,0,7,paint); canvas.drawCircle(0,0,2.5f,paint); for(int i=0;i<8;i++){canvas.save();canvas.rotate(i*45);canvas.drawLine(0,-7,0,-10,paint);canvas.restore();} }
            canvas.restore();
        }
    }
    private class CameraArt extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        CameraArt(Context context) { super(context); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); c.save(); c.translate(getWidth()/2f,getHeight()/2f); c.scale(getResources().getDisplayMetrics().density,getResources().getDisplayMetrics().density);
            paint.setStyle(Paint.Style.FILL); paint.setColor(CARD); c.drawCircle(0,0,64,paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1); paint.setColor(LINE); c.drawCircle(0,0,70,paint);
            paint.setColor(ACCENT); paint.setStrokeWidth(2); c.drawRoundRect(-44,-24,44,30,8,8,paint);
            Path top=new Path();top.moveTo(-23,-24);top.lineTo(-17,-34);top.lineTo(7,-34);top.lineTo(14,-24);c.drawPath(top,paint);
            c.drawCircle(0,3,19,paint); paint.setStrokeWidth(1); paint.setColor(MUTED); c.drawCircle(0,3,13,paint);c.drawLine(25,-13,32,-13,paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(ACCENT); c.drawCircle(0,3,4,paint); c.restore();
        }
    }
}

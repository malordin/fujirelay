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
import java.util.Set;
import java.time.*;
import android.transition.*;

public class MainActivity extends Activity {
    private static final String USB_PERMISSION = "dev.malordin.fuji.usb.PERMISSION";
    private static final int BG=0xff101213, CARD=0xff1b1e20, LINE=0xff303436, TEXT=0xfff2efe9, MUTED=0xff909798, ACCENT=0xffeabb98;
    private final java.util.ArrayList<Dialog> dialogs=new java.util.ArrayList<>();
    void trackDialog(Dialog dialog){dialogs.add(dialog);}
    void untrackDialog(Dialog dialog){dialogs.remove(dialog);}
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView heading, subtitle, cacheCount, cacheSize, delivered, journalTitle;
    private LinearLayout journalRows;
    private LogTerminal terminal;
    private Button primary;
    private ProgressBar progress;
    private boolean expanded, configured;
        private long maxBytes=1024*Settings.MIB;
    private String pendingMode, journal="";
    private View settingsButton, moreButton;
    private TextView selectionTitle,selectionDetail,deviceLabel;
    private LinearLayout dateTabs;
    private Button stop;
    private PhotoSelection selection;
    private boolean pendingPicker;
    private long pickerRevision;
    private boolean lastBusy;
    private String lastUiState="";
    private ViewGroup animatedBody,footer;

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
        expanded=state!=null && state.getBoolean("journal"); selection=PhotoSelection.load(this);
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
        animatedBody=body;
        deviceLabel=label("X-T30 II",12,MUTED);deviceLabel.setLetterSpacing(0.08f);body.addView(deviceLabel);gap(body,24);
        heading=label("Фотографии",34,TEXT);heading.setTypeface(null,Typeface.BOLD);body.addView(heading);gap(body,6);
        subtitle=label("",13,MUTED);body.addView(subtitle);gap(body,24);
        LinearLayout choose=column();choose.setPadding(dp(20),dp(22),dp(20),dp(20));choose.setBackground(ripple(CARD,20));choose.setContentDescription("Выбрать фотографии");
        selectionTitle=label("Все фотографии",24,TEXT);selectionTitle.setTypeface(null,Typeface.BOLD);choose.addView(selectionTitle);gap(choose,8);
        selectionDetail=label("Обновить каталог камеры",13,MUTED);choose.addView(selectionDetail);gap(choose,24);
        TextView browse=label("Выбрать кадры  ↗",14,ACCENT);choose.addView(browse);choose.setOnClickListener(v->choosePhotos());body.addView(choose);gap(body,12);
        HorizontalScrollView dates=new HorizontalScrollView(this);dates.setHorizontalScrollBarEnabled(false);dateTabs=row();dates.addView(dateTabs);body.addView(dates);
        String[] titles={"Все","Сегодня","Вчера","Период"};String[] modes={"all","today","yesterday","range"};
        for(int i=0;i<titles.length;i++){String mode=modes[i];TextView chip=label(titles[i],13,MUTED);chip.setPadding(dp(16),dp(12),dp(16),dp(12));chip.setTag(mode);chip.setBackground(ripple(CARD,16));chip.setOnClickListener(v->{if(ImportService.busy)return;if(mode.equals("range")){dateRange();return;}selection=mode.equals("all")?new PhotoSelection("all",0,Long.MAX_VALUE,Set.of()):PhotoSelection.day(mode,LocalDate.now(),ZoneId.systemDefault());selection.save(this);chip.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).withEndAction(()->chip.animate().scaleX(1).scaleY(1).setDuration(160).start()).start();render();});LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.rightMargin=dp(8);dateTabs.addView(chip,cp);}
        gap(body,24);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));body.addView(progress,new LinearLayout.LayoutParams(-1,dp(4)));gap(body,16);
        LinearLayout metrics=row(); metrics.setGravity(Gravity.TOP); metrics.setBackground(shape(CARD,22)); metrics.setPadding(dp(20),dp(20),dp(20),dp(20));
        LinearLayout left=column(); metrics.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        cacheCount=label("0",30,TEXT); left.addView(cacheCount); left.addView(label("В очереди",12,MUTED));
        cacheSize=label("",11,MUTED); cacheSize.setPadding(0,dp(10),0,0); left.addView(cacheSize);
        View divider=new View(this); divider.setBackgroundColor(LINE); LinearLayout.LayoutParams div=new LinearLayout.LayoutParams(dp(1),dp(82)); div.setMargins(dp(20),0,dp(24),0); metrics.addView(divider,div);
        LinearLayout right=column(); metrics.addView(right,new LinearLayout.LayoutParams(0,-2,1));
        delivered=label("0",30,TEXT); right.addView(delivered); right.addView(label("Передано",12,MUTED));
        body.addView(metrics); gap(body,20);
        LinearLayout journalHeader=row(); journalHeader.setMinimumHeight(dp(52)); journalHeader.setBackground(ripple(BG,12));
        journalHeader.setPadding(dp(4),0,dp(4),0); journalHeader.setId(View.generateViewId()); journalHeader.setContentDescription("Журнал передачи");
        journalTitle=label("Журнал",14,MUTED); journalHeader.addView(journalTitle,new LinearLayout.LayoutParams(0,-2,1));
        TextView chevron=label("⌄",22,MUTED); journalHeader.addView(chevron);
        journalHeader.setOnClickListener(v->{ expanded=!expanded; chevron.setText(expanded?"⌃":"⌄"); if(android.animation.ValueAnimator.areAnimatorsEnabled())TransitionManager.beginDelayedTransition(body,new AutoTransition().setDuration(220)); renderJournal(); }); body.addView(journalHeader);
        chevron.setText(expanded?"⌃":"⌄");
        journalRows=column(); terminal=new LogTerminal(this); terminal.openOnTap(this::openLog);journalRows.addView(terminal,new LinearLayout.LayoutParams(-1,dp(224))); body.addView(journalRows); scroll.addView(body); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footerLayout=column(); footer=footerLayout; footer.setPadding(dp(24),dp(12),dp(24),dp(20));
        primary=new Button(this); primary.setAllCaps(false);primary.setIncludeFontPadding(false);primary.setPadding(dp(16),0,dp(16),0); primary.setTextSize(16); primary.setTypeface(null,Typeface.BOLD); primary.setTextColor(BG);
        primary.setBackground(ripple(ACCENT,18)); primary.setOnClickListener(v->primaryAction()); footer.addView(primary,new LinearLayout.LayoutParams(-1,dp(56)));
        stop=new Button(this);stop.setText("Остановить передачу");stop.setAllCaps(false);stop.setTextSize(13);stop.setTextColor(0xffdd9f8f);stop.setBackground(ripple(BG,12));stop.setOnClickListener(v->{ImportService.stopTransfer();render();});footer.addView(stop,new LinearLayout.LayoutParams(-1,dp(48)));stop.setVisibility(View.GONE);root.addView(footer);
        setContentView(root);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(permission,new IntentFilter(USB_PERMISSION),Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(permission,new IntentFilter(USB_PERMISSION));
        loadState(); render();
        if(android.animation.ValueAnimator.areAnimatorsEnabled()){body.setAlpha(0);body.setTranslationY(dp(12));body.animate().alpha(1).translationY(0).setDuration(320).start();}
    }
    private void loadState() {
        new Thread(()-> {
            try(RelayDb db=new RelayDb(this)) {
                if(!ImportService.busy) {
                    File[] files=new File(getFilesDir(),"relay").listFiles(); long bytes=0; int count=0;
                    if(files!=null) for(File file:files) { count++; bytes+=file.length(); }
                    ImportService.cachedFiles=count; ImportService.cachedBytes=bytes; ImportService.confirmedFiles=db.confirmedCount();
                }
                PhotoCatalog.load(this);
                Settings settings=new Settings(this);
                String saved=""; File file=new File(getFilesDir(),"last-import.log");
                if(file.isFile()) try(java.io.RandomAccessFile in=new java.io.RandomAccessFile(file,"r")) {
                    long offset=Math.max(0,in.length()-16000); in.seek(offset); byte[] bytes=new byte[(int)(in.length()-offset)]; in.readFully(bytes);
                    saved=new String(bytes,StandardCharsets.UTF_8); if(offset>0) saved=saved.substring(saved.indexOf('\n')+1);
                }
                String log=saved;
                handler.post(()-> { if(isDestroyed()) return; configured=!settings.endpoint.isEmpty(); maxBytes=settings.maxBytes; if(ImportService.status.isEmpty()) journal=log; lastUiState=""; render(); });
            } catch(Exception error) { handler.post(()->{ if(!isDestroyed()) message("Настройки", "Не удалось прочитать настройки. Сохрани их заново."); }); }
        },"relay-dashboard").start();
    }
    private void render() {
        if(primary==null) return;
        boolean busy=ImportService.busy, attached=camera()!=null;
        if((selection.mode.equals("today")||selection.mode.equals("yesterday")) && !busy) selection=PhotoSelection.load(this);
        String uiState=busy+"|"+attached+"|"+ImportService.cancel+"|"+ImportService.failed+"|"+ImportService.phase+"|"+ImportService.currentName+"|"+ImportService.cachedFiles+"|"+ImportService.cachedBytes+"|"+ImportService.confirmedFiles+"|"+ImportService.processed+"|"+ImportService.planned+"|"+ImportService.status.hashCode()+"|"+configured+"|"+maxBytes+"|"+selection.mode+"|"+selection.from+"|"+selection.until+"|"+selection.keys.hashCode()+"|"+PhotoCatalog.revision;
        if(uiState.equals(lastUiState))return;
        lastUiState=uiState;
        if(lastBusy!=busy && android.animation.ValueAnimator.areAnimatorsEnabled())TransitionManager.beginDelayedTransition(animatedBody,new AutoTransition().setDuration(200));
        lastBusy=busy;
        deviceLabel.setText(attached?"●  X-T30 II":"○  КАМЕРА НЕ ПОДКЛЮЧЕНА");
        heading.setText(busy?ImportService.phase:ImportService.failed?"Нужна проверка":"Фотографии");
        subtitle.setText(busy?ImportService.currentName.isEmpty()?"Читаем каталог":ImportService.currentName:ImportService.failed?"Подробности в журнале":!configured?"Подключите Immich, чтобы начать передачу":"Выберите кадры для передачи");
        progress.setVisibility(busy?View.VISIBLE:View.GONE);
        progress.setIndeterminate(busy&&ImportService.planned==0);
        if(ImportService.planned>0)progress.setProgress(Math.min(1000,ImportService.processed*1000/ImportService.planned),true);
        if(!selectionTitle.getText().toString().equals(selection.title())){selectionTitle.setText(selection.title());if(android.animation.ValueAnimator.areAnimatorsEnabled()){selectionTitle.setAlpha(0.3f);selectionTitle.animate().alpha(1).setDuration(180).start();}}
        long selectedBytes=0;int selectedCount=0;for(var shot:PhotoCatalog.shots)if(selection.matches(shot.key(),shot.created())){selectedCount++;selectedBytes+=shot.size();}
        selectionDetail.setText(PhotoCatalog.shots.isEmpty()?"Каталог ещё не прочитан":selectedCount+" кадров · "+size(selectedBytes));
        for(int i=0;i<dateTabs.getChildCount();i++){TextView tab=(TextView)dateTabs.getChildAt(i);boolean chosen=selection.mode.equals(tab.getTag());tab.setTextColor(chosen?BG:MUTED);tab.setBackground(ripple(chosen?ACCENT:CARD,16));tab.setEnabled(!busy);}
        stop.setVisibility(busy?View.VISIBLE:View.GONE);
        if(pendingPicker&&!busy&&!ImportService.cancel&&PhotoCatalog.revision>pickerRevision){pendingPicker=false;new PhotoPicker(this,()->{selection=PhotoSelection.load(this);render();}).show();}
        cacheCount.setText(String.valueOf(ImportService.cachedFiles));
        cacheSize.setText(size(ImportService.cachedBytes)+" / "+size(maxBytes));
        delivered.setText(String.valueOf(ImportService.confirmedFiles));
        primary.setText(busy?ImportService.cancel?"Завершаем…":"Пауза":!configured?"Подключить Immich":ImportService.cachedFiles>0?"Продолжить":"Передать фотографии");
        primary.setEnabled(busy?!ImportService.cancel:!configured||attached||ImportService.cachedFiles>0);
        primary.setAlpha(primary.isEnabled()?1f:0.4f); settingsButton.setEnabled(!busy); moreButton.setEnabled(!busy);
        if(!ImportService.status.isEmpty()) journal=ImportService.status;
        journalTitle.setText(ImportService.failed?"Журнал · ошибка":"Журнал"); journalTitle.setTextColor(ImportService.failed?ACCENT:MUTED);
        renderJournal();
    }
    private void renderJournal() {
        journalRows.setVisibility(expanded?View.VISIBLE:View.GONE);
        if(expanded)terminal.setLog(journal);
    }
    private ScreenDialog openLog(){
        ScreenDialog dialog=new ScreenDialog(this);
        LinearLayout screen=column();screen.setPadding(dp(16),dp(8),dp(16),dp(16));
        LinearLayout bar=row();TextView title=label("Журнал",22,TEXT);bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView copy=label("Копировать",13,ACCENT);copy.setGravity(Gravity.CENTER);copy.setMinHeight(dp(48));copy.setPadding(dp(12),0,dp(12),0);copy.setOnClickListener(v->getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("FujiRelay",ImportService.status.isEmpty()?journal:ImportService.status)));bar.addView(copy);
        TextView close=label("Закрыть",13,MUTED);close.setGravity(Gravity.CENTER);close.setMinHeight(dp(48));close.setPadding(dp(12),0,dp(12),0);close.setOnClickListener(v->dialog.dismiss());bar.addView(close);screen.addView(bar);
        LogTerminal full=new LogTerminal(this);screen.addView(full,new LinearLayout.LayoutParams(-1,0,1));full.setLog(journal);
        Handler updates=new Handler(Looper.getMainLooper());Runnable tick=new Runnable(){@Override public void run(){if(!dialog.isShowing())return;full.setLog(ImportService.status.isEmpty()?journal:ImportService.status);updates.postDelayed(this,1000);}};
        dialog.setOnDismissListener(d->updates.removeCallbacksAndMessages(null));dialog.setContentView(screen);dialog.show();updates.post(tick);return dialog;
    }
    private void choosePhotos() {
        if(ImportService.busy) return;
        if(PhotoCatalog.shots.isEmpty()){pendingPicker=true;pickerRevision=PhotoCatalog.revision;connect("scan");}
        else new PhotoPicker(this,()->{selection=PhotoSelection.load(this);render();}).show();
    }
    private void dateRange() {
        LocalDate today=LocalDate.now();
        new DatePickerDialog(this,(v,y,m,d)->{LocalDate first=LocalDate.of(y,m+1,d);DatePickerDialog end=new DatePickerDialog(this,(vv,yy,mm,dd)->{try{selection=PhotoSelection.range("range",first,LocalDate.of(yy,mm+1,dd),ZoneId.systemDefault());selection.save(this);render();}catch(IllegalArgumentException e){message("Период",e.getMessage());}},y,m,d);end.setTitle("По какую дату");end.show();},today.getYear(),today.getMonthValue()-1,today.getDayOfMonth()).show();
    }
    private void primaryAction() {
        if(ImportService.busy) { ImportService.cancel=true; render(); return; }
        if(!configured) { settings(); return; }
        connect(camera()==null && ImportService.cachedFiles>0?"drain":"all");
    }
    private void more(View anchor) {
        PopupMenu menu=new PopupMenu(this,anchor);
        menu.getMenu().add("Обновить каталог").setOnMenuItemClickListener(item->{pendingPicker=true;pickerRevision=PhotoCatalog.revision;connect("scan");return true;});
        menu.getMenu().add("Передать один кадр").setOnMenuItemClickListener(item->{if(!configured) settings(); else connect("one");return true;});
        menu.show();
    }
    private ScreenDialog settings() {
        ScreenDialog dialog=new ScreenDialog(this);
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
        Button save=new Button(this); save.setText("Сохранить");save.setIncludeFontPadding(false);save.setPadding(dp(16),0,dp(16),0); save.setAllCaps(false); save.setTextColor(BG); save.setBackground(ripple(ACCENT,18));
        save.setOnClickListener(v->{try {Settings.save(this,url.getText().toString(),key.getText().toString(),Integer.parseInt(count.getText().toString()),Math.multiplyExact(Long.parseLong(bytes.getText().toString()),Settings.MIB),http.isChecked()); loadState(); dialog.dismiss();}catch(Exception e){Toast.makeText(this,e.getMessage()==null?"Проверь значения":e.getMessage(),Toast.LENGTH_LONG).show();}});
        screen.addView(save,new LinearLayout.LayoutParams(-1,dp(56))); dialog.setContentView(screen); dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getWindow().setWindowAnimations(R.style.SheetAnimation);dialog.show(); dialog.getWindow().setLayout(-1,-1);return dialog;
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
    @Override protected void onDestroy() { for(Dialog dialog:new java.util.ArrayList<>(dialogs))dialog.dismiss();handler.removeCallbacksAndMessages(null); unregisterReceiver(permission); super.onDestroy(); }

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
}

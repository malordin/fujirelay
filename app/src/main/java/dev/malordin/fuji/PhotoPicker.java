package dev.malordin.fuji;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

final class PhotoPicker extends ScreenDialog {
    private static final int BG=0xff101213,CARD=0xff1b1e20,TEXT=0xfff2efe9,MUTED=0xff909798,ACCENT=0xffeabb98;
    private final Context context;
    private final Runnable applied;
    private final List<PhotoCatalog.Shot> all=PhotoCatalog.shots;
    private List<PhotoCatalog.Shot> visible=new ArrayList<>();
    private PhotoSelection filter;
    private boolean manual;
    private final Set<String> selected=new HashSet<>();
    private final ExecutorService images=Executors.newFixedThreadPool(2);
    private final android.util.LruCache<String,Bitmap> bitmaps=new android.util.LruCache<>(4*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap value){return value.getByteCount();}
    };
    private GridView grid;
    private Button done;
    private TextView toggle;
    private LinearLayout tabs;
    private final Handler main=new Handler(Looper.getMainLooper());
    PhotoPicker(Context context,Runnable applied) { super(context);this.context=context;this.applied=applied;getWindow().setWindowAnimations(R.style.SheetAnimation);filter=PhotoSelection.load(context);manual=filter.mode.equals("manual")||filter.mode.equals("all");if(filter.mode.equals("manual"))selected.addAll(filter.keys); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=column();root.setBackgroundColor(BG);root.setPadding(dp(16),dp(18),dp(16),dp(20));
        LinearLayout bar=row();
        ImageButton back=new ImageButton(context);back.setImageResource(R.drawable.ic_back);back.setBackgroundColor(Color.TRANSPARENT);back.setContentDescription("Закрыть выбор");back.setOnClickListener(v->dismiss());bar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView title=text("Фотографии",21,TEXT);title.setTypeface(null,Typeface.BOLD);title.setSingleLine();title.setEllipsize(TextUtils.TruncateAt.END);bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        toggle=text("Все",13,ACCENT);toggle.setMinHeight(dp(48));toggle.setMinWidth(dp(64));toggle.setGravity(Gravity.CENTER);toggle.setOnClickListener(v->{boolean every=!visible.isEmpty()&&visible.stream().allMatch(this::checked);manual=true;for(var shot:visible){if(every)selected.remove(shot.key());else selected.add(shot.key());}update();});bar.addView(toggle);root.addView(bar);
        HorizontalScrollView horizontal=new HorizontalScrollView(context);horizontal.setHorizontalScrollBarEnabled(false);tabs=row();horizontal.addView(tabs);root.addView(horizontal);
        String[] names={"Все","Сегодня","Вчера","Период"};String[] modes={"all","today","yesterday","range"};
        for(int i=0;i<names.length;i++){String mode=modes[i];TextView tab=text(names[i],13,MUTED);tab.setTag(mode);tab.setPadding(dp(14),dp(14),dp(14),dp(14));tab.setBackground(shape(CARD));tab.setOnClickListener(v->{if(mode.equals("range")){range();return;}filter=mode.equals("all")?new PhotoSelection("all",0,Long.MAX_VALUE,Set.of()):PhotoSelection.day(mode,LocalDate.now(),ZoneId.systemDefault());selected.clear();manual=false;refilter();});LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);tp.rightMargin=dp(8);tabs.addView(tab,tp);}
        FrameLayout gallery=new FrameLayout(context);
        grid=new GridView(context);grid.setNumColumns(2);grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setHorizontalSpacing(dp(10));grid.setVerticalSpacing(dp(16));grid.setClipToPadding(false);grid.setPadding(0,dp(16),0,dp(16));
        grid.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol){int minimum=dp(Math.round(132*Math.max(1,getContext().getResources().getConfiguration().fontScale*0.85f)));grid.setNumColumns(Math.max(1,Math.min(5,(r-l)/minimum)));}});
        gallery.addView(grid,new FrameLayout.LayoutParams(-1,-1));TextView empty=text("За этот период нет кадров",15,MUTED);empty.setGravity(Gravity.CENTER);gallery.addView(empty,new FrameLayout.LayoutParams(-1,-1));grid.setEmptyView(empty);root.addView(gallery,new LinearLayout.LayoutParams(-1,0,1));
        done=new Button(context);done.setAllCaps(false);done.setTextSize(15);done.setIncludeFontPadding(false);done.setPadding(dp(16),0,dp(16),0);done.setTextColor(BG);done.setBackground(shape(ACCENT));done.setOnClickListener(v->{PhotoSelection result=manual?new PhotoSelection("manual",0,Long.MAX_VALUE,selected):filter;result.save(context);applied.run();dismiss();});root.addView(done,new LinearLayout.LayoutParams(-1,dp(56)));
        setContentView(root);refilter();
    }
    @Override public void show(){super.show();getWindow().setWindowAnimations(R.style.SheetAnimation);getWindow().setLayout(-1,-1);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);}
    private boolean checked(PhotoCatalog.Shot shot){return manual?selected.contains(shot.key()):filter.matches(shot.key(),shot.created());}
    private void refilter(){for(int i=0;i<tabs.getChildCount();i++){TextView tab=(TextView)tabs.getChildAt(i);boolean active=filter.mode.equals(tab.getTag());tab.setTextColor(active?BG:MUTED);tab.setBackground(shape(active?ACCENT:CARD));}visible=new ArrayList<>();for(var shot:all)if(filter.mode.equals("manual")||filter.matches(shot.key(),shot.created()))visible.add(shot);grid.setAdapter(new Tiles());if(android.animation.ValueAnimator.areAnimatorsEnabled()){grid.setAlpha(0.4f);grid.animate().alpha(1).setDuration(180).start();}update();}
    private void update(){toggle.setText(!visible.isEmpty()&&visible.stream().allMatch(this::checked)?"Снять":"Все");int count=manual?selected.size():(int)all.stream().filter(s->filter.matches(s.key(),s.created())).count();done.setText("Выбрать · "+count);done.setEnabled(count>0);done.setAlpha(count>0?1f:0.4f);((BaseAdapter)grid.getAdapter()).notifyDataSetChanged();}
    private void range(){LocalDate today=LocalDate.now();new DatePickerDialog(context,(view,y,m,d)->{LocalDate first=LocalDate.of(y,m+1,d);DatePickerDialog end=new DatePickerDialog(context,(v,yy,mm,dd)->{try{filter=PhotoSelection.range("range",first,LocalDate.of(yy,mm+1,dd),ZoneId.systemDefault());selected.clear();manual=false;refilter();}catch(IllegalArgumentException e){Toast.makeText(context,e.getMessage(),Toast.LENGTH_SHORT).show();}},y,m,d);end.setTitle("По какую дату");end.show();},today.getYear(),today.getMonthValue()-1,today.getDayOfMonth()).show();}
    private class Tiles extends BaseAdapter {
        @Override public int getCount(){return visible.size();}
        @Override public Object getItem(int position){return visible.get(position);}
        @Override public long getItemId(int position){return position;}
        @Override public View getView(int position,View recycled,ViewGroup parent){
            PhotoCatalog.Shot shot=visible.get(position);
            boolean isChecked=checked(shot);
            LinearLayout tile=recycled instanceof LinearLayout?(LinearLayout)recycled:column();tile.removeAllViews();tile.setPadding(0,0,0,0);
            SquarePreview photo=new SquarePreview(context);photo.setBackground(shape(CARD));photo.setClipToOutline(true);
            ImageView image=new ImageView(context);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackgroundColor(0xff252a2c);image.setTag(shot.key());photo.addView(image,new FrameLayout.LayoutParams(-1,-1));
            if(isChecked){
                GradientDrawable border=shape(Color.TRANSPARENT);border.setStroke(dp(2),ACCENT);photo.setForeground(border);
                ImageView check=new ImageView(context);check.setImageResource(R.drawable.ic_selected);check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                FrameLayout.LayoutParams mark=new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.TOP|Gravity.RIGHT);mark.setMargins(dp(8),dp(8),dp(8),dp(8));photo.addView(check,mark);
            }
            tile.addView(photo,new LinearLayout.LayoutParams(-1,-2));
            TextView name=text(shot.name(),12,TEXT);name.setPadding(dp(2),dp(8),dp(2),0);name.setSingleLine();name.setEllipsize(TextUtils.TruncateAt.MIDDLE);tile.addView(name);
            TextView date=text(shot.created()>0?DateTimeFormatter.ofPattern("d MMM · HH:mm",Locale.getDefault()).format(Instant.ofEpochMilli(shot.created()).atZone(ZoneId.systemDefault())):"Дата неизвестна",11,MUTED);date.setPadding(dp(2),dp(3),dp(2),0);date.setSingleLine();date.setEllipsize(TextUtils.TruncateAt.END);tile.addView(date);
            tile.setSelected(isChecked);
            Bitmap cached=bitmaps.get(shot.key());if(cached!=null)image.setImageBitmap(cached);else if(PhotoCatalog.preview(context,shot.key()).isFile()&&!images.isShutdown())images.execute(()->{BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;Bitmap bitmap=BitmapFactory.decodeFile(PhotoCatalog.preview(context,shot.key()).getPath(),options);if(bitmap!=null){bitmaps.put(shot.key(),bitmap);main.post(()->{if(isShowing()&&shot.key().equals(image.getTag()))image.setImageBitmap(bitmap);});}});
            tile.setContentDescription(shot.name()+", "+(checked(shot)?"выбрано":"не выбрано"));tile.setOnClickListener(v->{if(!manual){selected.clear();for(var item:all)if(filter.matches(item.key(),item.created()))selected.add(item.key());manual=true;}if(!selected.remove(shot.key()))selected.add(shot.key());update();});return tile;
        }
    }
    private static final class SquarePreview extends FrameLayout {
        SquarePreview(Context context){super(context);}
        @Override protected void onMeasure(int widthSpec,int heightSpec){int width=MeasureSpec.getSize(widthSpec);super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY));}
    }
    @Override public void dismiss(){images.shutdownNow();main.removeCallbacksAndMessages(null);super.dismiss();}
    private int dp(int n){return Math.round(n*context.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(context);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(context);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView text(String value,int size,int color){TextView t=new TextView(context);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    private GradientDrawable shape(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));return d;}
}

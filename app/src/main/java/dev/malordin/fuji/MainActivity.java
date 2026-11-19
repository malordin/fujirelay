package dev.malordin.fuji;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final String USB_PERMISSION = "dev.malordin.fuji.usb.PERMISSION";
    private static final int BG = Color.rgb(11,17,22), CARD = Color.rgb(22,35,43), TEXT = Color.rgb(236,243,243), MUTED = Color.rgb(153,173,183), ACCENT = Color.rgb(153,213,200);
    private TextView status, phase, cache, delivery, limits;
    private ProgressBar progress;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Button> actions = new ArrayList<>();
    private String pendingMode;
    private final BroadcastReceiver permission = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbManager manager = getSystemService(UsbManager.class);
            boolean granted = manager.getDeviceList().values().stream().anyMatch(device ->
                device.getVendorId() == 0x04cb && device.getProductId() == 0x02f3 && manager.hasPermission(device));
            if (granted && pendingMode != null) start(pendingMode);
            else if (!granted) status.setText("Разреши доступ к USB-камере, чтобы начать передачу.");
            pendingMode = null;
        }
    };
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!ImportService.status.isEmpty()) status.setText(ImportService.status);
            phase.setText(ImportService.phase);
            if (!ImportService.cacheSummary.isEmpty()) cache.setText(ImportService.cacheSummary);
            if (!ImportService.deliverySummary.isEmpty()) delivery.setText(ImportService.deliverySummary);
            progress.setVisibility(ImportService.busy ? View.VISIBLE : View.GONE);
            for (Button button : actions) button.setEnabled(!ImportService.busy);
            handler.postDelayed(this, 1000);
        }
    };

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // Legacy overload only runs below API 33.
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        LinearLayout root = column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout content = column(); content.setPadding(dp(24), dp(20), dp(24), dp(28));
        text(content, "FUJIFILM  /  IMMICH", 11, ACCENT).setLetterSpacing(0.16f);
        TextView title = text(content, "FujiRelay", 38, TEXT); title.setTypeface(null, Typeface.BOLD);
        text(content, "Снимай больше. Храни на сервере.", 16, MUTED);
        gap(content, 22);
        LinearLayout connection = card(content);
        text(connection, "X-T30 II  →  ТЕЛЕФОН  →  IMMICH", 11, ACCENT);
        phase = text(connection, ImportService.phase, 21, TEXT); phase.setTypeface(null, Typeface.BOLD);
        text(connection, "Оригиналы проходят через ограниченный кэш.\nМесто освобождается после подтверждения Immich.", 13, MUTED);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true); progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        connection.addView(progress); progress.setVisibility(View.GONE);
        gap(content, 12);
        LinearLayout metrics = card(content);
        text(metrics, "НА ТЕЛЕФОНЕ", 10, MUTED);
        cache = text(metrics, "0 фото · 0 МиБ", 23, TEXT);
        limits = text(metrics, "Лимит: 100 фото / 1024 МиБ · резерв 512 МиБ", 12, MUTED);
        gap(metrics, 12);
        text(metrics, "ДОСТАВЛЕНО В IMMICH", 10, MUTED);
        delivery = text(metrics, "История хранится на этом телефоне", 13, TEXT);
        gap(content, 18);
        action(content, "Передать фотографии", "all", true);
        action(content, "Тест: передать один JPEG", "one", false);
        action(content, "Проверить камеру", "scan", false);
        action(content, "Отправить оставшийся кэш", "drain", false);
        Button settings = button(content, "Настройки Immich и кэша", false);
        settings.setOnClickListener(v -> settings()); actions.add(settings);
        Button stop = button(content, "Остановить после текущей операции", false);
        stop.setTextColor(Color.rgb(231,173,151)); stop.setOnClickListener(v -> ImportService.cancel = true);
        gap(content, 12);
        LinearLayout journal = card(content);
        text(journal, "ЖУРНАЛ ПЕРЕДАЧИ", 10, MUTED);
        status = text(journal, "Камера: USB CARD READER, USB POWER SUPPLY OFF.\nПодключи USB-C и проверь камеру.", 12, MUTED);
        status.setTextIsSelectable(true); status.setTypeface(Typeface.MONOSPACE);
        text(content, "JPEG · USB · оригинальное качество\nFujiRelay 0.2 · независимый open-source проект", 11, MUTED).setPadding(0,dp(18),0,0);
        scroll.addView(content); root.addView(scroll); setContentView(root);
        loadSummary();
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permission, new IntentFilter(USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED);
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else registerReceiver(permission, new IntentFilter(USB_PERMISSION));
    }
    private void loadSummary() {
        try (RelayDb db = new RelayDb(this)) {
            File directory = new File(getFilesDir(), "relay"); long bytes = 0; int count = 0;
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) { bytes += file.length(); count++; }
            cache.setText(String.format(java.util.Locale.US, "%d фото · %.1f МиБ", count, bytes / (double)Settings.MIB));
            delivery.setText(db.confirmedCount() + " подтверждений в журнале");
            Settings settings = new Settings(this);
            limits.setText("Лимит: " + settings.maxFiles + " фото / " + settings.maxBytes / Settings.MIB + " МиБ · резерв 512 МиБ");
        } catch (Exception e) { status.setText("Не удалось прочитать настройки. Открой настройки и сохрани их заново."); }
    }
    private void settings() {
        LinearLayout form = column(); form.setPadding(dp(24),dp(12),dp(24),dp(8));
        text(form, "Immich", 24, TEXT);
        text(form, "API-ключ: asset.upload, asset.read, user.read. Загрузка идёт напрямую; мобильное приложение Immich не требуется.", 13, MUTED);
        EditText url = input(form, "https://photos.example.com", false, false);
        EditText key = input(form, "API-ключ", true, false);
        text(form, "Максимум фотографий в кэше", 12, MUTED);
        EditText count = input(form, "100", false, true); count.setText("100");
        text(form, "Максимальный размер кэша, МиБ", 12, MUTED);
        EditText bytes = input(form, "1024", false, true); bytes.setText("1024");
        CheckBox http = new CheckBox(this); http.setText("Разрешить HTTP для домашней сети"); http.setTextColor(MUTED); form.addView(http);
        text(form, "При HTTP ключ и фотографии передаются без шифрования. Для удалённого доступа используй HTTPS.", 12, MUTED);
        try {
            Settings settings = new Settings(this); url.setText(settings.endpoint); key.setText(settings.apiKey);
            count.setText(String.valueOf(settings.maxFiles)); bytes.setText(String.valueOf(settings.maxBytes / Settings.MIB));
            http.setChecked(settings.endpoint.startsWith("http://"));
        } catch (Exception ignored) { key.setText(""); }
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(scroll).setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null).create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnShowListener(ignored -> {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    Settings.save(this, url.getText().toString(), key.getText().toString(), Integer.parseInt(count.getText().toString()),
                        Long.parseLong(bytes.getText().toString()) * Settings.MIB, http.isChecked());
                    loadSummary(); dialog.dismiss();
                } catch (Exception e) { Toast.makeText(this, e.getMessage() == null ? "Проверь значения" : e.getMessage(), Toast.LENGTH_LONG).show(); }
            });
        });
        dialog.show();
    }
    private EditText input(LinearLayout parent, String hint, boolean secret, boolean number) {
        EditText field = new EditText(this); field.setHint(hint); field.setTextSize(15); field.setTextColor(TEXT); field.setHintTextColor(MUTED); field.setSingleLine(true);
        field.setInputType(number ? InputType.TYPE_CLASS_NUMBER : secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); parent.addView(field); return field;
    }
    private void action(LinearLayout parent, String label, String mode, boolean primary) {
        Button button = button(parent, label, primary); actions.add(button);
        button.setOnClickListener(v -> {
            if (!"scan".equals(mode)) try {
                if (new Settings(this).endpoint.isEmpty()) { settings(); return; }
            } catch (Exception e) { settings(); return; }
            if ("drain".equals(mode)) start(mode); else connect(mode);
        });
    }
    private Button button(LinearLayout parent, String label, boolean primary) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(14); button.setMinHeight(dp(48));
        button.setTextColor(primary ? BG : TEXT); button.setBackground(background(primary ? ACCENT : CARD, 14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50)); params.bottomMargin = dp(8);
        parent.addView(button, params); return button;
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = column(); card.setPadding(dp(18),dp(16),dp(18),dp(16)); card.setBackground(background(CARD,20)); parent.addView(card); return card;
    }
    private GradientDrawable background(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private TextView text(LinearLayout parent, String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextColor(color); view.setTextSize(size); view.setPadding(0,dp(3),0,dp(3)); parent.addView(view); return view; }
    private void gap(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1,dp(height))); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void connect(String mode) {
        if (ImportService.busy) return;
        UsbManager manager = getSystemService(UsbManager.class);
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getVendorId() != 0x04cb || device.getProductId() != 0x02f3) continue;
            if (manager.hasPermission(device)) start(mode);
            else {
                pendingMode = mode;
                manager.requestPermission(device, PendingIntent.getBroadcast(this, 0,
                    new Intent(USB_PERMISSION).setPackage(getPackageName()), PendingIntent.FLAG_IMMUTABLE));
            }
            return;
        }
        status.setText("X-T30 II не найдена. Проверь кабель для данных и режим USB CARD READER.");
    }
    private void start(String mode) { startForegroundService(new Intent(this, ImportService.class).setAction(mode)); }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { unregisterReceiver(permission); super.onDestroy(); }
}

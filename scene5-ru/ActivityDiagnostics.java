package com.omarea.vtools.activities;

import android.app.Activity;
import android.app.ActivityManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.os.StatFs;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/** Read-only system report. No root commands, CPU tuning or persistent changes. */
public final class ActivityDiagnostics extends Activity {
    private TextView reportView;
    private String report = "";
    private final int background = Color.rgb(18, 18, 18);
    private final int cardColor = Color.rgb(33, 33, 33);
    private final int accent = Color.rgb(73, 150, 240);

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(background);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(20), dp(16), dp(26));
        scroll.addView(body);
        TextView title = new TextView(this);
        title.setText("Диагностика устройства");
        title.setTextSize(24);
        title.setTextColor(Color.WHITE);
        title.setPadding(0, 0, 0, dp(8));
        body.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("Только чтение. Кнопки на этом экране не меняют системные настройки.");
        subtitle.setTextSize(13);
        subtitle.setTextColor(0xffaaaaaa);
        subtitle.setPadding(0, 0, 0, dp(16));
        body.addView(subtitle);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = new Button(this);
        refresh.setText("Обновить");
        Button copy = new Button(this);
        copy.setText("Копировать");
        actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(50), 1));
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(50), 1));
        body.addView(actions);
        reportView = new TextView(this);
        reportView.setTextSize(15);
        reportView.setLineSpacing(dp(4), 1.0f);
        reportView.setTextColor(Color.WHITE);
        reportView.setTextIsSelectable(true);
        GradientDrawable card = new GradientDrawable();
        card.setColor(cardColor);
        card.setCornerRadius(dp(14));
        reportView.setBackground(card);
        reportView.setPadding(dp(16), dp(16), dp(16), dp(18));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(12);
        body.addView(reportView, cardParams);
        refresh.setOnClickListener(v -> showReport());
        copy.setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null && report.length() > 0) {
                cb.setPrimaryClip(ClipData.newPlainText("Диагностика Scene", report));
                Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show();
            }
        });
        setContentView(scroll);
        showReport();
    }

    private void showReport() {
        try {
            report = buildReport();
        } catch (Exception e) {
            report = "Не удалось прочитать некоторые показатели:\n" + e.toString();
        }
        reportView.setText(report);
    }

    private String buildReport() {
        StringBuilder s = new StringBuilder();
        s.append("УСТРОЙСТВО\n");
        field(s, "Модель", Build.MANUFACTURER + " " + Build.MODEL);
        field(s, "Устройство", Build.DEVICE);
        field(s, "Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        field(s, "Архитектура", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "неизвестна");
        field(s, "Ядер CPU", "" + Runtime.getRuntime().availableProcessors());
        String kernel = readLine("/proc/sys/kernel/osrelease");
        field(s, "Ядро Linux", kernel.length() == 0 ? "нет доступа" : kernel);
        s.append("\nПРОЦЕССОР\n");
        for (int i=0; i<Runtime.getRuntime().availableProcessors(); i++) {
            String freq = readLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            String text = "нет данных";
            try {
                long khz = Long.parseLong(freq.trim());
                text = String.format(Locale.US, "%.0f МГц", khz / 1000.0);
            } catch (Exception ignored) {}
            field(s, "CPU " + i, text);
        }
        field(s, "Средняя нагрузка", readLine("/proc/loadavg"));
        s.append("\nОПЕРАТИВНАЯ ПАМЯТЬ\n");
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            field(s, "Всего RAM", gib(mi.totalMem));
            field(s, "Свободно RAM", gib(mi.availMem));
            field(s, "Использовано RAM", gib(Math.max(0L, mi.totalMem-mi.availMem)));
            field(s, "Низкий запас памяти", mi.lowMemory ? "да" : "нет");
        }
        s.append("\nБАТАРЕЯ\n");
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int rawTemp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            int voltage = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            field(s, "Заряд", (level >= 0 && scale > 0) ? (level*100/scale)+"%" : "нет данных");
            field(s, "Температура", rawTemp >= 0 ? String.format(Locale.US, "%.1f °C", rawTemp/10.0) : "нет данных");
            field(s, "Напряжение", voltage >= 0 ? voltage+" мВ" : "нет данных");
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            field(s, "Зарядка", status == BatteryManager.BATTERY_STATUS_CHARGING ? "идёт" :
                 status == BatteryManager.BATTERY_STATUS_FULL ? "заряжено" : "не заряжается");
        }
        BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            int cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (cur != Integer.MIN_VALUE) {
                field(s, "Ток (показание)", String.format(Locale.US,"%.0f мА",cur/1000.0));
            }
        }
        s.append("\nЭКРАН\n");
        Display display = getWindowManager().getDefaultDisplay();
        field(s, "Частота сейчас", String.format(Locale.US, "%.1f Гц", display.getRefreshRate()));
        float maxRefresh=0f;
        for (Display.Mode m : display.getSupportedModes()) maxRefresh=Math.max(maxRefresh,m.getRefreshRate());
        field(s, "Макс. доступная частота", String.format(Locale.US, "%.1f Гц", maxRefresh));
        s.append("\nХРАНИЛИЩЕ И СИСТЕМА\n");
        StatFs storage = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        field(s, "Всего памяти", gib(storage.getTotalBytes()));
        field(s, "Свободно", gib(storage.getAvailableBytes()));
        long elapsed=SystemClock.elapsedRealtime()/1000;
        field(s, "Время работы", String.format(Locale.US,"%d ч %d мин",elapsed/3600,(elapsed/60)%60));
        s.append("\nПримечание: частота экрана не равна FPS игры. Отдельные показатели зависят от доступности системных файлов.\n");
        return s.toString();
    }

    private String gib(long bytes) {
        return String.format(Locale.US, "%.2f ГБ", bytes/1073741824.0);
    }
    private void field(StringBuilder s, String key, String value) {
        s.append(key).append(": ").append(value == null || value.isEmpty() ? "нет данных" : value).append('\n');
    }
    private String readLine(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String value = reader.readLine();
            return value == null ? "" : value;
        } catch (Exception ignored) {return "";}
    }
    private int dp(int d) {
        return (int)(d * getResources().getDisplayMetrics().density + 0.5f);
    }
}

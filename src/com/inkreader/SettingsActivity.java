package com.inkreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

public final class SettingsActivity extends Activity {
    private SharedPreferences prefs;
    private TextView sample;
    private TextView fontSizeLabel;
    private TextView spacingLabel;
    private TextView marginLabel;
    private TextView brightnessLabel;
    private CheckBox followSystem;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.migrateCompactDefaults(this);
        prefs = Ui.prefs(this);
        Ui.applyWindow(this, Ui.isNight(this));
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.themeBackground(this));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 14), Ui.dp(this, 10),
                Ui.dp(this, 14), Ui.dp(this, 8));
        Button back = Ui.button(this, "返回");
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = Ui.title(this, "阅读设置", 22, Ui.themeText(this));
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button reset = Ui.button(this, "恢复默认");
        reset.setOnClickListener(v -> {
            prefs.edit().clear().apply();
            recreate();
        });
        header.addView(reset);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 30));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        sample = Ui.text(this, prefs.getInt("font-size", Ui.DEFAULT_FONT_SIZE_SP),
                Ui.themeText(this), false);
        sample.setText("山川是不卷收的文章，日月为你掌灯伴读。\n调整下面的排版参数会立即作用到这段预览。");
        sample.setLineSpacing(0,
                prefs.getFloat("line-spacing", Ui.DEFAULT_LINE_SPACING));
        sample.setTypeface(Ui.fontTypeface(this));
        sample.setPadding(Ui.dp(this, prefs.getInt("margin", Ui.DEFAULT_MARGIN_DP)), Ui.dp(this, 18),
                Ui.dp(this, prefs.getInt("margin", Ui.DEFAULT_MARGIN_DP)), Ui.dp(this, 18));
        sample.setBackground(Ui.round(this, Ui.themeCard(this), 14));
        body.addView(sample, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        fontSizeLabel = section(body, "字号");
        SeekBar fontSize = seek(14, 34,
                prefs.getInt("font-size", Ui.DEFAULT_FONT_SIZE_SP));
        fontSize.setOnSeekBarChangeListener(simple(value -> {
            prefs.edit().putInt("font-size", value).apply();
            fontSizeLabel.setText("字号  ·  " + value + " sp");
            sample.setTextSize(value);
        }));
        body.addView(fontSize);

        spacingLabel = section(body, "行间距");
        SeekBar spacing = seek(110, 210, Math.round(
                prefs.getFloat("line-spacing", Ui.DEFAULT_LINE_SPACING) * 100));
        spacing.setOnSeekBarChangeListener(simple(value -> {
            float multiplier = value / 100f;
            prefs.edit().putFloat("line-spacing", multiplier).apply();
            spacingLabel.setText(String.format(java.util.Locale.ROOT, "行间距  ·  %.2f 倍", multiplier));
            sample.setLineSpacing(0, multiplier);
        }));
        body.addView(spacing);

        marginLabel = section(body, "左右页边距");
        SeekBar margin = seek(8, 56,
                prefs.getInt("margin", Ui.DEFAULT_MARGIN_DP));
        margin.setOnSeekBarChangeListener(simple(value -> {
            prefs.edit().putInt("margin", value).apply();
            marginLabel.setText("左右页边距  ·  " + value + " dp");
            int px = Ui.dp(this, value);
            sample.setPadding(px, Ui.dp(this, 18), px, Ui.dp(this, 18));
        }));
        body.addView(margin);

        section(body, "正文字体");
        Spinner font = spinner(new String[]{"衬线体 · 宋体风格", "无衬线体 · 黑体风格", "等宽体"});
        String savedFont = prefs.getString("font-family", Ui.DEFAULT_FONT_FAMILY);
        font.setSelection("sans-serif".equals(savedFont) ? 1 : "monospace".equals(savedFont) ? 2 : 0);
        font.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = position == 1 ? "sans-serif" : position == 2 ? "monospace" : "serif";
                prefs.edit().putString("font-family", value).apply();
                sample.setTypeface(Ui.fontTypeface(SettingsActivity.this));
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        body.addView(font);

        section(body, "字体粗细");
        Spinner fontWeight = spinner(new String[]{"常规", "粗体"});
        fontWeight.setSelection("bold".equals(
                prefs.getString("font-weight", Ui.DEFAULT_FONT_WEIGHT)) ? 1 : 0);
        fontWeight.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putString("font-weight", position == 1 ? "bold" : "normal").apply();
                sample.setTypeface(Ui.fontTypeface(SettingsActivity.this));
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        body.addView(fontWeight);

        section(body, "阅读背景");
        Spinner theme = spinner(new String[]{"纸张日间", "夜间", "护眼绿"});
        String savedTheme = prefs.getString("theme", "paper");
        theme.setSelection("night".equals(savedTheme) ? 1 : "eye".equals(savedTheme) ? 2 : 0);
        theme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean initial = true;
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (initial) {
                    initial = false;
                    return;
                }
                String value = position == 1 ? "night" : position == 2 ? "eye" : "paper";
                prefs.edit().putString("theme", value).apply();
                recreate();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        body.addView(theme);

        section(body, "屏幕亮度");
        brightnessLabel = Ui.text(this, 13, Ui.themeMuted(this), false);
        body.addView(brightnessLabel);
        int savedBrightness = prefs.getInt("brightness", 170);
        SeekBar brightness = seek(5, 255, savedBrightness);
        followSystem = new CheckBox(this);
        followSystem.setText("跟随系统亮度");
        followSystem.setChecked(prefs.getBoolean("follow-system-brightness", true));
        body.addView(followSystem);
        brightness.setEnabled(!followSystem.isChecked());
        updateBrightnessLabel(savedBrightness);
        brightness.setOnSeekBarChangeListener(simple(value -> {
            prefs.edit().putInt("brightness", value).apply();
            updateBrightnessLabel(value);
            if (!followSystem.isChecked()) applyBrightness();
        }));
        followSystem.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("follow-system-brightness", checked).apply();
            brightness.setEnabled(!checked);
            applyBrightness();
        });
        body.addView(brightness);
        applyBrightness();

        CheckBox keepAwake = new CheckBox(this);
        keepAwake.setText("阅读时保持屏幕常亮");
        keepAwake.setChecked(prefs.getBoolean("keep-awake", false));
        keepAwake.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("keep-awake", checked).apply();
            applyKeepAwake(checked);
        });
        body.addView(keepAwake);

        section(body, "手机壳水墨屏");
        CheckBox eink = new CheckBox(this);
        eink.setText("进入阅读时连接 NFC 水墨屏");
        eink.setChecked(prefs.getBoolean("eink-enabled", false));
        eink.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean("eink-enabled", checked).apply());
        body.addView(eink);
        TextView panelHint = Ui.text(this, 13, Ui.themeMuted(this), false);
        panelHint.setText("按实物选择屏幕型号；A0 与 A1 无法由 D1 自动识别。");
        body.addView(panelHint);
        Spinner panel = spinner(new String[]{"请选择屏幕型号", "A0 · 552×768 四色", "A1 · 552×768 四色"});
        String savedPanel = prefs.getString("panel-type", "");
        panel.setSelection("A0".equals(savedPanel) ? 1 : "A1".equals(savedPanel) ? 2 : 0);
        panel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putString("panel-type", position == 1 ? "A0" : position == 2 ? "A1" : "").apply();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        body.addView(panel);
        Button check = Ui.button(this, "检查 Shizuku 与 NFC");
        check.setOnClickListener(v -> checkShizuku());
        body.addView(check);
        TextView transportHint = Ui.text(this, 13, Ui.themeMuted(this), false);
        transportHint.setText("第二页会在第一页显示期间后台预传。翻页时如果预传已完成，仅发送 D4 刷新；若未完成则自动回退为完整传输，画面不会错页。");
        transportHint.setPadding(0, Ui.dp(this, 8), 0, 0);
        body.addView(transportHint);

        Button license = Ui.button(this, "开源许可与硬件说明");
        license.setOnClickListener(v -> showLicense());
        body.addView(license);
    }

    private TextView section(LinearLayout body, String title) {
        TextView label = Ui.title(this, title, 15, Ui.themeText(this));
        label.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 5));
        body.addView(label);
        return label;
    }

    private SeekBar seek(int min, int max, int value) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
        bar.setTag(min);
        return bar;
    }

    private static SeekBar.OnSeekBarChangeListener simple(ValueListener listener) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                listener.value(progress + (Integer) seekBar.getTag());
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, values));
        return spinner;
    }

    private void updateBrightnessLabel(int value) {
        brightnessLabel.setText("应用内亮度  ·  " + Math.round(value * 100f / 255f) + "%"
                + (followSystem != null && followSystem.isChecked() ? "  ·  跟随系统" : ""));
    }

    private void applyBrightness() {
        boolean follow = prefs.getBoolean("follow-system-brightness", true);
        if (follow) {
            getWindow().getAttributes().screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        } else {
            int value = prefs.getInt("brightness", 170);
            getWindow().getAttributes().screenBrightness = Math.max(0.02f, value / 255f);
        }
        getWindow().setAttributes(getWindow().getAttributes());
    }

    private void applyKeepAwake(boolean keepAwake) {
        if (keepAwake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void checkShizuku() {
        boolean nfc = getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC);
        String shizuku;
        try {
            if (!Shizuku.pingBinder()) shizuku = "未运行";
            else if (Shizuku.isPreV11()) shizuku = "版本过旧，请升级";
            else if (Shizuku.checkSelfPermission()
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) shizuku = "已授权";
            else shizuku = "已运行，尚未授权";
        } catch (Throwable error) {
            shizuku = "不可用：" + error.getMessage();
        }
        new AlertDialog.Builder(this)
                .setTitle("传输环境")
                .setMessage("NFC 硬件：" + (nfc ? "可用" : "不可用")
                        + "\nShizuku：" + shizuku
                        + "\n\n水墨屏传输需要 Shizuku 授权。TXT 阅读、书架和设置不依赖该权限。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void showLicense() {
        new AlertDialog.Builder(this)
                .setTitle("开源许可与硬件说明")
                .setMessage("协议与图像布局参考 kur0neko/huawei-epd-pn532，"
                        + "遵循 PolyForm Noncommercial 1.0.0；Required Notice："
                        + "Copyright (c) 2026 kur0neko。Shizuku 客户端遵循 MIT。\n\n"
                        + "支持的手机壳屏幕为 552×768 黑白黄红面板，且必须与已验证的原厂扫描配置匹配。"
                        + "本应用保留板内驱动，不写入 DB/DA。完整许可位于 APK assets/licenses。")
                .setPositiveButton("关闭", null)
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        applyBrightness();
        applyKeepAwake(prefs.getBoolean("keep-awake", false));
    }

    private interface ValueListener {
        void value(int value);
    }
}

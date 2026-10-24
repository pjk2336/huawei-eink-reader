package com.inkreader;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class Ui {
    public static final int PAPER = 0xfff5f2e9;
    public static final int PAPER_CARD = 0xfffffdf6;
    public static final int INK = 0xff20251f;
    public static final int MUTED = 0xff6f756b;
    public static final int GREEN = 0xff315c4c;
    public static final int GREEN_SOFT = 0xffdfe9df;
    public static final int NIGHT = 0xff161816;
    public static final int NIGHT_CARD = 0xff222622;
    public static final int NIGHT_TEXT = 0xffd9ddcf;
    public static final int EYE = 0xffdce7ce;
    public static final int EYE_CARD = 0xffe9f0df;
    public static final int DEFAULT_FONT_SIZE_SP = 17;
    public static final float DEFAULT_LINE_SPACING = 1.40f;
    public static final int DEFAULT_MARGIN_DP = 16;
    public static final String DEFAULT_FONT_FAMILY = "sans-serif";
    public static final String DEFAULT_FONT_WEIGHT = "bold";

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable round(Context context, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static TextView text(Context context, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.12f);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(context, 3), 0, dp(context, 3));
        return view;
    }

    public static TextView title(Context context, String value, float size, int color) {
        TextView view = text(context, size, color, true);
        view.setText(value);
        return view;
    }

    public static Button button(Context context, String value) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(value);
        button.setTextSize(13.5f);
        button.setTextColor(themeText(context));
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setIncludeFontPadding(false);
        button.setGravity(android.view.Gravity.CENTER);
        button.setMinHeight(dp(context, 42));
        button.setMinWidth(0);
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        button.setStateListAnimator(null);
        GradientDrawable surface = round(context,
                isNight(context) ? 0xff2b312b : 0xffe6ece5, 12);
        surface.setStroke(dp(context, 1),
                isNight(context) ? 0xff3b443b : 0xffd3ddd2);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x44315c4c), surface, null));
        return button;
    }

    public static Button primaryButton(Context context, String value) {
        Button button = button(context, value);
        button.setTextColor(Color.WHITE);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x55ffffff), round(context, GREEN, 12), null));
        return button;
    }

    public static ImageButton iconButton(Context context, String drawableName,
                                         String description) {
        ImageButton button = new ImageButton(context);
        int id = context.getResources().getIdentifier(
                drawableName, "drawable", context.getPackageName());
        if (id != 0) button.setImageResource(id);
        button.setImageTintList(ColorStateList.valueOf(themeText(context)));
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(context, 10), dp(context, 10),
                dp(context, 10), dp(context, 10));
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x44315c4c),
                round(context, isNight(context) ? 0xff2b312b : 0xffe6ece5, 12), null));
        return button;
    }

    public static LinearLayout.LayoutParams weighted(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    public static LinearLayout section(Context context, String title, int textColor) {
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(context, 16), 0, dp(context, 5));
        TextView label = title(context, title, 15, textColor);
        section.addView(label);
        return section;
    }

    public static void applyWindow(Activity activity, boolean dark) {
        if (Build.VERSION.SDK_INT >= 26) {
            activity.getWindow().setStatusBarColor(dark ? NIGHT : PAPER);
            activity.getWindow().setNavigationBarColor(dark ? NIGHT : PAPER);
            int flags = activity.getWindow().getDecorView().getSystemUiVisibility();
            if (dark) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            activity.getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    public static int themeBackground(Context context) {
        String theme = prefs(context).getString("theme", "paper");
        return "night".equals(theme) ? NIGHT : "eye".equals(theme) ? EYE : PAPER;
    }

    public static int themeCard(Context context) {
        String theme = prefs(context).getString("theme", "paper");
        return "night".equals(theme) ? NIGHT_CARD : "eye".equals(theme) ? EYE_CARD : PAPER_CARD;
    }

    public static int themeText(Context context) {
        return "night".equals(prefs(context).getString("theme", "paper")) ? NIGHT_TEXT : INK;
    }

    public static int themeMuted(Context context) {
        return "night".equals(prefs(context).getString("theme", "paper")) ? 0xffaeb6a8 : MUTED;
    }

    public static boolean isNight(Context context) {
        return "night".equals(prefs(context).getString("theme", "paper"));
    }

    public static Typeface fontTypeface(Context context) {
        String value = prefs(context).getString("font-family", DEFAULT_FONT_FAMILY);
        int style = "bold".equals(prefs(context).getString("font-weight", DEFAULT_FONT_WEIGHT))
                ? Typeface.BOLD : Typeface.NORMAL;
        if ("sans-serif".equals(value)) return Typeface.create("sans-serif", style);
        if ("monospace".equals(value)) return Typeface.create("monospace", style);
        return Typeface.create("serif", style);
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("reader-settings", Context.MODE_PRIVATE);
    }

    /** Move untouched settings from the old defaults to the compact defaults once. */
    public static void migrateCompactDefaults(Context context) {
        SharedPreferences preferences = prefs(context);
        if (preferences.getBoolean("compact-defaults-v1", false)) return;
        SharedPreferences.Editor editor = preferences.edit();
        if (preferences.getInt("font-size", -1) == 19) {
            editor.putInt("font-size", DEFAULT_FONT_SIZE_SP);
        }
        if (Math.abs(preferences.getFloat("line-spacing", -1f) - 1.55f) < 0.001f) {
            editor.putFloat("line-spacing", DEFAULT_LINE_SPACING);
        }
        if (preferences.getInt("margin", -1) == 24) {
            editor.putInt("margin", DEFAULT_MARGIN_DP);
        }
        editor.putBoolean("compact-defaults-v1", true).apply();
    }

    public static void setMargins(View view, int left, int top, int right, int bottom) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) params).setMargins(left, top, right, bottom);
            view.setLayoutParams(params);
        }
    }
}

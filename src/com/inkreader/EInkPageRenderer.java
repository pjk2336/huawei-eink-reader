package com.inkreader;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import org.nfcresearch.qpolllab.ImageRaster;
import org.nfcresearch.qpolllab.PanelProtocol;
import org.nfcresearch.qpolllab.PanelType;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class EInkPageRenderer {
    public static final int WIDTH = 552;
    public static final int HEIGHT = 768;
    private static final int MARGIN = 34;
    private static final int HEADER = 54;
    private static final int FOOTER = 52;
    private static final int BOTTOM_GUARD = 2;

    public static final class Snapshot {
        public final byte[] packed;
        public final String sha256;
        public final int pageIndex;
        public final int pageCount;
        public final int startChar;
        public final int endChar;

        Snapshot(byte[] packed, String sha256, int pageIndex, int pageCount,
                 int startChar, int endChar) {
            this.packed = packed;
            this.sha256 = sha256;
            this.pageIndex = pageIndex;
            this.pageCount = pageCount;
            this.startChar = startChar;
            this.endChar = endChar;
        }
    }

    public static final class PageLayout {
        private final String text;
        private final String title;
        private final int chapterNumber;
        private final TextPaint textPaint;
        private final Paint chromePaint;
        private final StaticLayout layout;
        private final List<Integer> firstLines = new ArrayList<>();
        private final List<Integer> lastLines = new ArrayList<>();
        private final float spacing;

        private PageLayout(String text, String title, int chapterNumber, TextPaint textPaint,
                           Paint chromePaint, StaticLayout layout, float spacing) {
            this.text = text;
            this.title = title;
            this.chapterNumber = chapterNumber;
            this.textPaint = textPaint;
            this.chromePaint = chromePaint;
            this.layout = layout;
            this.spacing = spacing;
            int first = 0;
            // Restore the 0.3.0 text capacity. The page now includes the two
            // lines that were previously leaking into the old bottom gap.
            int bodyHeight = HEIGHT - HEADER - FOOTER - BOTTOM_GUARD;
            while (first < layout.getLineCount()) {
                int last = first;
                int top = layout.getLineTop(first);
                while (last + 1 < layout.getLineCount()
                        && layout.getLineBottom(last + 1) - top <= bodyHeight) {
                    last++;
                }
                firstLines.add(first);
                lastLines.add(last);
                first = last + 1;
            }
            if (firstLines.isEmpty()) {
                firstLines.add(0);
                lastLines.add(0);
            }
        }

        public int pageCount() {
            return firstLines.size();
        }

        public int startChar(int page) {
            return layout.getLineStart(firstLines.get(clampPage(page)));
        }

        public int endChar(int page) {
            int index = clampPage(page);
            return layout.getLineEnd(lastLines.get(index));
        }

        /** Draws the exact e-ink line layout into a phone-sized text area. */
        public void drawPageText(Canvas canvas, int page, int color,
                                 float left, float top, float areaWidth, float areaHeight) {
            if (areaWidth <= 0 || areaHeight <= 0 || firstLines.isEmpty()) return;
            int index = clampPage(page);
            int first = firstLines.get(index);
            int last = lastLines.get(index);
            float sourceTop = layout.getLineTop(first);
            float sourceHeight = Math.max(1f, layout.getLineBottom(last) - sourceTop);
            float sourceWidth = Math.max(1f, layout.getWidth());
            float scale = Math.min(areaWidth / sourceWidth, areaHeight / sourceHeight);
            float x = left + (areaWidth - sourceWidth * scale) / 2f;
            float y = top + (areaHeight - sourceHeight * scale) / 2f;
            float contentBottom = Math.min(top + areaHeight, y + sourceHeight * scale);
            canvas.save();
            canvas.clipRect(left, y, left + areaWidth, contentBottom);
            canvas.translate(x, y - sourceTop * scale);
            canvas.scale(scale, scale);
            synchronized (this) {
                int oldColor = textPaint.getColor();
                textPaint.setColor(color);
                layout.draw(canvas);
                textPaint.setColor(oldColor);
            }
            canvas.restore();
        }

        public int pageAtChar(int charIndex) {
            int value = Math.max(0, charIndex);
            for (int i = 0; i < firstLines.size(); i++) {
                if (layout.getLineStart(firstLines.get(i)) > value) return Math.max(0, i - 1);
            }
            return firstLines.size() - 1;
        }

        private int clampPage(int page) {
            return Math.max(0, Math.min(firstLines.size() - 1, page));
        }
    }

    public EInkPageRenderer() {
    }

    public PageLayout layout(Context context, int chapterNumber, String chapterTitle, String text) {
        int fontSp = Ui.prefs(context).getInt("font-size", Ui.DEFAULT_FONT_SIZE_SP);
        float spacing = Ui.prefs(context).getFloat(
                "line-spacing", Ui.DEFAULT_LINE_SPACING);
        float textPx = 21f + Math.max(0, fontSp - 16) * 0.85f;
        TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(textPx);
        textPaint.setTypeface(Ui.fontTypeface(context));

        Paint chrome = new Paint(Paint.ANTI_ALIAS_FLAG);
        chrome.setColor(Color.BLACK);
        chrome.setTextSize(18);
        chrome.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));

        int width = WIDTH - MARGIN * 2;
        StaticLayout built = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, spacing)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();
        return new PageLayout(text, chapterTitle == null ? "" : chapterTitle, chapterNumber,
                textPaint, chrome, built, spacing);
    }

    public Snapshot render(Context context, PageLayout pages, int pageIndex,
                           String panelType) throws Exception {
        int index = Math.max(0, Math.min(pages.pageCount() - 1, pageIndex));
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        int startLine = pages.firstLines.get(index);
        int lastLine = pages.lastLines.get(index);
        int top = pages.layout.getLineTop(startLine);
        int bodyTop = HEADER;
        int bodyBottom = HEIGHT - FOOTER;
        int pageLineBottom = bodyTop + pages.layout.getLineBottom(lastLine) - top;
        int pageClipBottom = Math.min(bodyBottom, pageLineBottom);

        pages.chromePaint.setTextSize(18);
        String header = ellipsize(pages.title, 24);
        canvas.drawText(header, MARGIN, 28, pages.chromePaint);
        pages.chromePaint.setTextSize(14);
        canvas.drawText("墨读 · 预传阅读", WIDTH - MARGIN - pages.chromePaint.measureText("墨读 · 预传阅读"),
                28, pages.chromePaint);
        pages.chromePaint.setStrokeWidth(1.2f);
        canvas.drawLine(MARGIN, 40, WIDTH - MARGIN, 40, pages.chromePaint);

        canvas.save();
        // Clip at the selected page's last line. The safety gap below this
        // point must stay blank; otherwise following lines leak onto the panel
        // even though they were assigned to the next phone page.
        canvas.clipRect(MARGIN, bodyTop, WIDTH - MARGIN, pageClipBottom);
        canvas.translate(MARGIN, bodyTop - top);
        synchronized (pages) {
            pages.layout.draw(canvas);
        }
        canvas.restore();

        pages.chromePaint.setTextSize(15);
        canvas.drawLine(MARGIN, HEIGHT - 43, WIDTH - MARGIN, HEIGHT - 43, pages.chromePaint);
        String chapterPages = String.format(Locale.ROOT, "第%d章  %d/%d页",
                pages.chapterNumber, index + 1, pages.pageCount());
        String status = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date())
                + "  " + batteryPercent(context) + "%";
        canvas.drawText(chapterPages, MARGIN, HEIGHT - 19, pages.chromePaint);
        canvas.drawText(status,
                WIDTH - MARGIN - pages.chromePaint.measureText(status),
                HEIGHT - 19, pages.chromePaint);
        bitmap.setHasAlpha(false);

        int[] pixels = new int[WIDTH * HEIGHT];
        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
        // Crisp binary text both reads better on e-ink and creates longer
        // repeated runs, which substantially reduces the D3 packet count.
        byte[] codes = ImageRaster.quantize(pixels, WIDTH, HEIGHT, true, 160, false, 1);
        byte[] packed = PanelType.parse(panelType).pack(codes);
        bitmap.recycle();
        String sha = hex(MessageDigest.getInstance("SHA-256").digest(packed));
        return new Snapshot(packed, sha, index, pages.pageCount(),
                pages.startChar(index), pages.endChar(index));
    }

    private static String ellipsize(String value, int maxChars) {
        if (value.length() <= maxChars) return value;
        return value.substring(0, maxChars) + "…";
    }

    private static int batteryPercent(Context context) {
        BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        int percent = manager == null ? -1
                : manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (percent < 0 || percent > 100) {
            Intent state = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = state == null ? -1 : state.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = state == null ? -1 : state.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            percent = level < 0 || scale <= 0 ? 0 : Math.round(level * 100f / scale);
        }
        return Math.max(0, Math.min(100, percent));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}

package com.inkreader;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public final class DirectoryActivity extends Activity {
    private static final int PAGE_SIZE = 100;
    private ReaderDb db;
    private long bookId;
    private int currentChapter;
    private int totalChapters;
    private int windowStart;
    private int windowEnd;
    private boolean loading;
    private ListView list;
    private ChapterAdapter adapter;
    private TextView counter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.applyWindow(this, Ui.isNight(this));
        db = new ReaderDb(this);
        bookId = getIntent().getLongExtra("bookId", -1);
        currentChapter = Math.max(0, getIntent().getIntExtra("chapterIndex", 0));
        buildUi();
        totalChapters = db.chapterCount(bookId);
        int anchor = (currentChapter / PAGE_SIZE) * PAGE_SIZE;
        loadRange(anchor, PAGE_SIZE, true);
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
        TextView title = Ui.title(this, "章节目录", 21, Ui.themeText(this));
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        counter = Ui.text(this, 12, Ui.themeMuted(this), false);
        header.addView(counter);
        root.addView(header);

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        adapter = new ChapterAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Chapter chapter = adapter.items.get(position);
            Intent result = new Intent();
            result.putExtra("chapterIndex", chapter.index);
            setResult(RESULT_OK, result);
            finish();
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override public void onScroll(AbsListView view, int firstVisibleItem,
                                           int visibleItemCount, int totalItemCount) {
                if (loading || adapter.getCount() == 0) return;
                if (firstVisibleItem == 0 && windowStart > 0) {
                    loadBefore();
                } else if (firstVisibleItem + visibleItemCount >= totalItemCount - 4
                        && windowEnd < totalChapters) {
                    loadAfter();
                }
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private void loadRange(int start, int limit, boolean initial) {
        if (loading || start < 0 || start >= totalChapters) return;
        loading = true;
        new Thread(() -> {
            List<Chapter> values = db.chapters(bookId, start, Math.min(limit, totalChapters - start));
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!initial) adapter.items.clear();
                adapter.items.addAll(values);
                windowStart = start;
                windowEnd = start + values.size();
                adapter.notifyDataSetChanged();
                counter.setText(Math.min(windowEnd, totalChapters) + " / " + totalChapters);
                if (initial) {
                    int target = Math.max(0, Math.min(adapter.items.size() - 1,
                            currentChapter - windowStart));
                    list.setSelection(target);
                }
                loading = false;
            });
        }, "chapter-directory").start();
    }

    private void loadAfter() {
        if (loading || windowEnd >= totalChapters) return;
        loading = true;
        int start = windowEnd;
        new Thread(() -> {
            List<Chapter> values = db.chapters(bookId, start, PAGE_SIZE);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                adapter.items.addAll(values);
                windowEnd = start + values.size();
                adapter.notifyDataSetChanged();
                counter.setText(windowEnd + " / " + totalChapters);
                loading = false;
            });
        }, "chapter-directory-next").start();
    }

    private void loadBefore() {
        if (loading || windowStart <= 0) return;
        loading = true;
        int oldFirst = list.getFirstVisiblePosition();
        int start = Math.max(0, windowStart - PAGE_SIZE);
        int count = windowStart - start;
        new Thread(() -> {
            List<Chapter> values = db.chapters(bookId, start, count);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                adapter.items.addAll(0, values);
                windowStart = start;
                adapter.notifyDataSetChanged();
                list.setSelection(oldFirst + values.size());
                counter.setText(windowStart + "–" + windowEnd + " / " + totalChapters);
                loading = false;
            });
        }, "chapter-directory-previous").start();
    }

    @Override protected void onDestroy() {
        db.close();
        super.onDestroy();
    }

    private final class ChapterAdapter extends BaseAdapter {
        private final ArrayList<Chapter> items = new ArrayList<>();

        @Override public int getCount() { return items.size(); }
        @Override public Chapter getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return items.get(position).id; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            TextView row = convertView instanceof TextView
                    ? (TextView) convertView : new TextView(DirectoryActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(DirectoryActivity.this, 22), Ui.dp(DirectoryActivity.this, 14),
                    Ui.dp(DirectoryActivity.this, 22), Ui.dp(DirectoryActivity.this, 14));
            row.setTextSize(16);
            row.setSingleLine(true);
            Chapter chapter = items.get(position);
            boolean active = chapter.index == currentChapter;
            row.setText((active ? "●  " : "    ") + chapter.title
                    + "\n    " + String.format(java.util.Locale.ROOT, "第 %d 章", chapter.index + 1));
            row.setTextColor(active ? Ui.GREEN : Ui.themeText(DirectoryActivity.this));
            row.setTypeface(active ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            row.setBackgroundColor(active ? Ui.GREEN_SOFT : 0x00000000);
            row.setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return row;
        }
    }
}

package com.inkreader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.InputType;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class SearchActivity extends Activity {
    private long bookId;
    private Book book;
    private EditText input;
    private TextView status;
    private ProgressBar progress;
    private SearchAdapter adapter;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicInteger generation = new AtomicInteger();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.migrateCompactDefaults(this);
        Ui.applyWindow(this, Ui.isNight(this));
        bookId = getIntent().getLongExtra("bookId", -1);
        buildUi();
        new Thread(() -> {
            ReaderDb db = new ReaderDb(this);
            try {
                Book loaded = db.book(bookId);
                runOnUiThread(() -> {
                    if (loaded == null) {
                        status.setText("小说不存在");
                        input.setEnabled(false);
                    } else {
                        book = loaded;
                        input.requestFocus();
                    }
                });
            } finally {
                db.close();
            }
        }, "search-book-load").start();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.themeBackground(this));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 12), Ui.dp(this, 10),
                Ui.dp(this, 12), Ui.dp(this, 7));
        ImageButton back = Ui.iconButton(this, "ic_back", "返回阅读");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("搜索全书");
        input.setTextColor(Ui.themeText(this));
        input.setHintTextColor(Ui.themeMuted(this));
        input.setTextSize(16);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setBackground(Ui.round(this, Ui.themeCard(this), 12));
        input.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                0, Ui.dp(this, 44), 1);
        inputParams.setMargins(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        header.addView(input, inputParams);
        Button search = Ui.primaryButton(this, "搜索");
        search.setOnClickListener(v -> startSearch());
        header.addView(search, new LinearLayout.LayoutParams(
                Ui.dp(this, 76), Ui.dp(this, 44)));
        root.addView(header);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                startSearch();
                return true;
            }
            return false;
        });

        status = Ui.text(this, 13, Ui.themeMuted(this), false);
        status.setPadding(Ui.dp(this, 18), Ui.dp(this, 5),
                Ui.dp(this, 18), Ui.dp(this, 7));
        status.setText("输入关键词，回车或点击搜索");
        root.addView(status);
        progress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3)));

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 20));
        adapter = new SearchAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            SearchHit hit = adapter.items.get(position);
            Intent result = new Intent();
            result.putExtra("chapterIndex", hit.chapterIndex);
            result.putExtra("chapterOffset", hit.chapterOffset);
            setResult(RESULT_OK, result);
            finish();
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private void startSearch() {
        if (book == null) return;
        String query = input.getText().toString().trim();
        if (query.isEmpty()) {
            status.setText("请输入搜索关键词");
            return;
        }
        final int token = generation.incrementAndGet();
        cancelled.set(true);
        cancelled.set(false);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        status.setText("正在搜索全书…");
        adapter.items.clear();
        adapter.notifyDataSetChanged();
        new Thread(() -> {
            ReaderDb db = new ReaderDb(this);
            try {
                List<SearchHit> result = BookSearch.search(db, book, query,
                        (percent, chapters) -> runOnUiThread(() -> {
                            if (!isDestroyed() && token == generation.get()) {
                                progress.setProgress(percent);
                                status.setText("正在搜索 · 已扫描 " + chapters + " 章");
                            }
                        }), cancelled);
                runOnUiThread(() -> {
                    if (isDestroyed() || token != generation.get()) return;
                    progress.setVisibility(View.GONE);
                    adapter.items.addAll(result);
                    adapter.notifyDataSetChanged();
                    status.setText(result.isEmpty()
                            ? "没有找到“" + query + "”"
                            : "找到 " + result.size() + " 处，点击跳转");
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    if (isDestroyed() || token != generation.get()) return;
                    progress.setVisibility(View.GONE);
                    status.setText("搜索失败：" + error.getMessage());
                });
            } finally {
                db.close();
            }
        }, "book-search").start();
    }

    @Override protected void onDestroy() {
        cancelled.set(true);
        generation.incrementAndGet();
        super.onDestroy();
    }

    private final class SearchAdapter extends BaseAdapter {
        private final ArrayList<SearchHit> items = new ArrayList<>();

        @Override public int getCount() { return items.size(); }
        @Override public SearchHit getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
            } else {
                row = new LinearLayout(SearchActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(Ui.dp(SearchActivity.this, 18), Ui.dp(SearchActivity.this, 12),
                        Ui.dp(SearchActivity.this, 18), Ui.dp(SearchActivity.this, 12));
                row.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.removeAllViews();
            row.setBackground(Ui.round(SearchActivity.this,
                    Ui.themeCard(SearchActivity.this), 12));
            SearchHit hit = items.get(position);
            TextView title = Ui.title(SearchActivity.this,
                    "第 " + (hit.chapterIndex + 1) + " 章 · " + hit.chapterTitle,
                    15, Ui.themeText(SearchActivity.this));
            title.setMaxLines(1);
            row.addView(title);
            TextView snippet = Ui.text(SearchActivity.this, 13,
                    Ui.themeMuted(SearchActivity.this), false);
            snippet.setText(highlight(hit.snippet, hit.query));
            snippet.setMaxLines(2);
            row.addView(snippet);
            return row;
        }

        private CharSequence highlight(String text, String query) {
            if (text == null || query == null || query.isEmpty()) return text;
            SpannableStringBuilder value = new SpannableStringBuilder(text);
            int from = 0;
            while (from <= text.length() - query.length()) {
                int match = text.regionMatches(true, from, query, 0, query.length())
                        ? from : text.toLowerCase(java.util.Locale.ROOT)
                        .indexOf(query.toLowerCase(java.util.Locale.ROOT), from);
                if (match < 0) break;
                value.setSpan(new BackgroundColorSpan(0x99F0CF65),
                        match, match + query.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                value.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),
                        match, match + query.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                from = match + Math.max(1, query.length());
            }
            return value;
        }
    }
}

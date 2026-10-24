package com.inkreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class BookmarkActivity extends Activity {
    private ReaderDb db;
    private long bookId;
    private BookmarkAdapter adapter;
    private TextView empty;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.migrateCompactDefaults(this);
        Ui.applyWindow(this, Ui.isNight(this));
        bookId = getIntent().getLongExtra("bookId", -1);
        db = new ReaderDb(this);
        buildUi();
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
        TextView title = Ui.title(this, "书签", 21, Ui.themeText(this));
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(new View(this), new LinearLayout.LayoutParams(Ui.dp(this, 42), 1));
        root.addView(header);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 20));
        adapter = new BookmarkAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Bookmark mark = adapter.items.get(position);
            Intent result = new Intent();
            result.putExtra("chapterIndex", mark.chapterIndex);
            result.putExtra("chapterOffset", mark.chapterOffset);
            setResult(RESULT_OK, result);
            finish();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDelete(adapter.items.get(position));
            return true;
        });
        empty = Ui.text(this, 14, Ui.themeMuted(this), false);
        empty.setText("还没有书签\n阅读时点击顶部书签图标即可保存当前页");
        empty.setGravity(Gravity.CENTER);
        FrameLayout content = new FrameLayout(this);
        content.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    @Override protected void onResume() {
        super.onResume();
        List<Bookmark> marks = db.bookmarks(bookId);
        adapter.items.clear();
        adapter.items.addAll(marks);
        adapter.notifyDataSetChanged();
        empty.setVisibility(marks.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void confirmDelete(Bookmark mark) {
        new AlertDialog.Builder(this)
                .setTitle("删除这个书签？")
                .setMessage(mark.chapterTitle + "\n" + mark.snippet)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    db.deleteBookmark(mark.id);
                    onResume();
                }).show();
    }

    @Override protected void onDestroy() {
        db.close();
        super.onDestroy();
    }

    private final class BookmarkAdapter extends BaseAdapter {
        private final ArrayList<Bookmark> items = new ArrayList<>();
        private final SimpleDateFormat date = new SimpleDateFormat(
                "MM-dd HH:mm", java.util.Locale.CHINA);

        @Override public int getCount() { return items.size(); }
        @Override public Bookmark getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return items.get(position).id; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
            } else {
                row = new LinearLayout(BookmarkActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(Ui.dp(BookmarkActivity.this, 18), Ui.dp(BookmarkActivity.this, 12),
                        Ui.dp(BookmarkActivity.this, 18), Ui.dp(BookmarkActivity.this, 12));
                row.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.removeAllViews();
            row.setBackground(Ui.round(BookmarkActivity.this,
                    Ui.themeCard(BookmarkActivity.this), 12));
            Bookmark mark = items.get(position);
            TextView title = Ui.title(BookmarkActivity.this,
                    mark.chapterTitle, 15, Ui.themeText(BookmarkActivity.this));
            title.setMaxLines(1);
            row.addView(title);
            TextView snippet = Ui.text(BookmarkActivity.this, 13,
                    Ui.themeMuted(BookmarkActivity.this), false);
            snippet.setText(mark.snippet);
            snippet.setMaxLines(2);
            row.addView(snippet);
            TextView time = Ui.text(BookmarkActivity.this, 11,
                    Ui.themeMuted(BookmarkActivity.this), false);
            time.setText(date.format(new Date(mark.createdAt)) + "  ·  长按删除");
            row.addView(time);
            return row;
        }
    }
}

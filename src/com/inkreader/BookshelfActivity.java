package com.inkreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BookshelfActivity extends Activity {
    private static final int REQUEST_IMPORT = 301;
    private ReaderDb db;
    private ListView list;
    private View empty;
    private BookAdapter adapter;
    private String sort;
    private String activeTheme;
    private boolean importing;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.migrateCompactDefaults(this);
        Ui.applyWindow(this, Ui.isNight(this));
        db = new ReaderDb(this);
        activeTheme = Ui.prefs(this).getString("theme", "paper");
        sort = Ui.prefs(this).getString("bookshelf-sort", ReaderDb.SORT_RECENT);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.themeBackground(this));
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), 0);
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brandBlock = new LinearLayout(this);
        brandBlock.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        int logoId = getResources().getIdentifier(
                "ic_launcher", "drawable", getPackageName());
        if (logoId != 0) logo.setImageResource(logoId);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        brandBlock.addView(logo, new LinearLayout.LayoutParams(
                Ui.dp(this, 46), Ui.dp(this, 46)));
        TextView brand = Ui.title(this, "墨读", 28, Ui.themeText(this));
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brandParams.leftMargin = Ui.dp(this, 10);
        brandBlock.addView(brand, brandParams);
        header.addView(brandBlock, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button sortButton = Ui.button(this, "排序");
        sortButton.setOnClickListener(v -> chooseSort());
        header.addView(sortButton);
        Button settings = Ui.button(this, "设置");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(settings);
        root.addView(header);
        TextView subtitle = Ui.text(this, 13, Ui.themeMuted(this), false);
        subtitle.setText("本地阅读 · 无需登录 · 离线可用");
        root.addView(subtitle);

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 84));
        adapter = new BookAdapter();
        list.setAdapter(adapter);
        content.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        empty = buildEmpty();
        content.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Button importButton = Ui.primaryButton(this, "导入 TXT");
        importButton.setTextSize(16);
        FrameLayout.LayoutParams importParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52), Gravity.BOTTOM);
        importParams.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 18));
        content.addView(importButton, importParams);
        importButton.setOnClickListener(v -> pickFiles());

        list.setOnItemClickListener((parent, view, position, id) -> openBook(adapter.books.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDelete(adapter.books.get(position));
            return true;
        });
    }

    private View buildEmpty() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        TextView mark = Ui.title(this, "▤", 54, Ui.GREEN);
        mark.setGravity(Gravity.CENTER);
        box.addView(mark);
        TextView title = Ui.title(this, "书架还是空的", 22, Ui.themeText(this));
        title.setGravity(Gravity.CENTER);
        box.addView(title);
        TextView hint = Ui.text(this, 14, Ui.themeMuted(this), false);
        hint.setText("导入一个或多个 TXT 文件\n章节、进度和设置都只保存在本机");
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 86));
        box.addView(hint);
        return box;
    }

    @Override protected void onResume() {
        super.onResume();
        String currentTheme = Ui.prefs(this).getString("theme", "paper");
        if (!currentTheme.equals(activeTheme)) {
            recreate();
            return;
        }
        if (!importing) reload();
    }

    private void reload() {
        List<Book> books = db.listBooks(sort);
        adapter.setBooks(books);
        empty.setVisibility(books.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(books.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void openBook(Book book) {
        if (book == null) return;
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra("bookId", book.id);
        startActivity(intent);
    }

    private void chooseSort() {
        String[] values = {"最近阅读", "书名"};
        int checked = ReaderDb.SORT_TITLE.equals(sort) ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle("书架排序")
                .setSingleChoiceItems(values, checked, (dialog, which) -> {
                    sort = which == 1 ? ReaderDb.SORT_TITLE : ReaderDb.SORT_RECENT;
                    Ui.prefs(this).edit().putString("bookshelf-sort", sort).apply();
                    dialog.dismiss();
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(Book book) {
        new AlertDialog.Builder(this)
                .setTitle("删除《" + book.title + "》？")
                .setMessage("小说文件、章节目录和阅读进度都会从此手机删除，无法在应用内恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    String path = db.deleteBook(book.id);
                    if (path != null) {
                        File file = new File(path);
                        if (file.exists() && !file.delete()) {
                            Toast.makeText(this, "书籍记录已删除，但本地文件清理失败", Toast.LENGTH_LONG).show();
                        }
                    }
                    reload();
                }).show();
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "text/*", "application/octet-stream"});
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_IMPORT || result != RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (!uris.isEmpty()) importFiles(uris);
    }

    private void importFiles(List<Uri> uris) {
        if (importing) return;
        importing = true;
        AtomicBoolean cancelled = new AtomicBoolean();
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        TextView status = Ui.text(this, 14, Ui.themeText(this), false);
        status.setText("准备导入 " + uris.size() + " 个文件");
        status.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 8), Ui.dp(this, 22), 0);
        box.addView(status);
        box.addView(bar);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("导入 TXT")
                .setView(box)
                .setNegativeButton("取消", (d, w) -> cancelled.set(true))
                .setCancelable(false)
                .create();
        dialog.show();

        new Thread(() -> {
            TxtImporter importer = new TxtImporter(this, db);
            StringBuilder summary = new StringBuilder();
            int success = 0, duplicate = 0, failed = 0;
            for (int i = 0; i < uris.size(); i++) {
                if (cancelled.get()) break;
                int fileIndex = i + 1;
                try {
                    TxtImporter.Result result = importer.importUri(uris.get(i),
                            new TxtImporter.Progress() {
                                @Override public void update(int percent, String message) {
                                    runOnUiThread(() -> {
                                        bar.setIndeterminate(percent < 0);
                                        if (percent >= 0) bar.setProgress(percent);
                                        status.setText(fileIndex + " / " + uris.size() + " · " + message);
                                    });
                                }

                                @Override public boolean cancelled() {
                                    return cancelled.get();
                                }
                            });
                    if (result.success) success++;
                    else if (result.duplicate) duplicate++;
                    summary.append(result.message).append('\n');
                } catch (InterruptedException e) {
                    summary.append("已取消剩余文件\n");
                    break;
                } catch (Throwable error) {
                    failed++;
                    String name = uris.get(i).getLastPathSegment();
                    summary.append(name == null ? "导入失败" : name + "：导入失败")
                            .append("（").append(error.getMessage()).append("）\n");
                }
            }
            int done = success, dup = duplicate, bad = failed;
            runOnUiThread(() -> {
                importing = false;
                if (dialog.isShowing()) dialog.dismiss();
                reload();
                String resultText = "成功 " + done + " 本"
                        + (dup > 0 ? " · 重复 " + dup + " 本" : "")
                        + (bad > 0 ? " · 失败 " + bad + " 本" : "");
                String detail = summary.length() == 0 ? resultText : resultText + "\n\n" + summary;
                new AlertDialog.Builder(BookshelfActivity.this)
                        .setTitle("导入完成")
                        .setMessage(detail)
                        .setPositiveButton("知道了", null)
                        .show();
            });
        }, "txt-import").start();
    }

    @Override protected void onDestroy() {
        if (!importing) db.close();
        super.onDestroy();
    }

    private final class BookAdapter extends BaseAdapter {
        private final List<Book> books = new ArrayList<>();

        void setBooks(List<Book> values) {
            books.clear();
            books.addAll(values);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return books.size(); }
        @Override public Book getItem(int position) { return books.get(position); }
        @Override public long getItemId(int position) { return books.get(position).id; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout card;
            if (convertView instanceof LinearLayout) {
                card = (LinearLayout) convertView;
            } else {
                card = new LinearLayout(BookshelfActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(Ui.dp(BookshelfActivity.this, 18), Ui.dp(BookshelfActivity.this, 14),
                        Ui.dp(BookshelfActivity.this, 18), Ui.dp(BookshelfActivity.this, 14));
                card.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            card.removeAllViews();
            card.setBackground(Ui.round(BookshelfActivity.this, Ui.themeCard(BookshelfActivity.this), 15));
            card.setElevation(Ui.dp(BookshelfActivity.this, 2));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(0, Ui.dp(BookshelfActivity.this, 5), 0,
                    Ui.dp(BookshelfActivity.this, 6));
            card.setLayoutParams(cardParams);
            Book book = books.get(position);

            TextView title = Ui.title(BookshelfActivity.this, book.title, 19,
                    Ui.themeText(BookshelfActivity.this));
            title.setMaxLines(2);
            card.addView(title);
            TextView meta = Ui.text(BookshelfActivity.this, 13,
                    Ui.themeMuted(BookshelfActivity.this), false);
            meta.setText(book.author + "  ·  " + book.totalChapters + " 章  ·  " + book.progressText());
            card.addView(meta);
            ProgressBar progress = new ProgressBar(BookshelfActivity.this, null,
                    android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000);
            progress.setProgress(Math.round(book.progress * 1000));
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(BookshelfActivity.this, 4));
            progressParams.topMargin = Ui.dp(BookshelfActivity.this, 9);
            card.addView(progress, progressParams);
            return card;
        }
    }
}

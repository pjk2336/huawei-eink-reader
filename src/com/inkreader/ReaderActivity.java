package com.inkreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReaderActivity extends Activity implements PanelClient.Listener {
    private static final int REQUEST_DIRECTORY = 401;
    private static final int REQUEST_SETTINGS = 402;
    private static final int REQUEST_SEARCH = 403;
    private static final int REQUEST_BOOKMARKS = 404;
    private static final int PAGE_AUTO = -1;
    private static final int PAGE_LAST = -2;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private final Map<String, EInkPageRenderer.Snapshot> advancing = new HashMap<>();

    private ReaderDb db;
    private long bookId;
    private Book book;
    private Chapter chapter;
    private ChapterChunk chunk;
    private String chunkText = "";
    private EInkPageRenderer.PageLayout eInkPages;
    private int eInkPageIndex;
    private int wantedEInkPage;
    private int displayedEInkPage = -1;
    private boolean advanceBusy;
    private boolean preloadBusy;
    private EInkPageRenderer.Snapshot nextSnapshot;
    private int nextSnapshotPage = -1;
    private boolean nextPreloaded;
    private int expectedPreloadPage = -1;
    private boolean pageReady;
    private boolean loadingContent;
    private boolean destroyed;
    private String activeLayoutSignature;

    private LinearLayout content;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private FrameLayout pageFrame;
    private PagedTextView pageBody;
    private TextView pageCounter;
    private EInkPageRenderer.Snapshot currentSnapshot;
    private String displayedPageText = "";
    private int viewGeneration;
    private TextView chapterTitle;
    private TextView bookTitle;
    private TextView progressText;
    private TextView panelStatus;
    private Button panelButton;
    private View darkOverlay;
    private boolean darkMode;
    private PanelClient panelClient;
    private boolean autoEInkAttempted;
    private String activeTheme;
    private final EInkPageRenderer pageRenderer = new EInkPageRenderer();

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) && darkMode) {
                setDarkMode(false);
                Toast.makeText(ReaderActivity.this, "已恢复手机屏幕", Toast.LENGTH_SHORT).show();
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.migrateCompactDefaults(this);
        bookId = getIntent().getLongExtra("bookId", -1);
        db = new ReaderDb(this);
        book = db.book(bookId);
        if (book == null) {
            Toast.makeText(this, "小说不存在或已被删除", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        activeTheme = Ui.prefs(this).getString("theme", "paper");
        Ui.applyWindow(this, Ui.isNight(this));
        buildUi();
        registerReceiver(screenReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
        loadChapter(book.chapterIndex, book.chapterOffset, PAGE_AUTO);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.themeBackground(this));
        setContentView(root);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setBackgroundColor(Ui.themeCard(this));
        topBar.setPadding(Ui.dp(this, 12), Ui.dp(this, 8),
                Ui.dp(this, 12), Ui.dp(this, 7));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = Ui.iconButton(this, "ic_back", "返回书架");
        back.setOnClickListener(v -> finish());
        actions.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(Ui.dp(this, 8), 0, 0, 0);
        bookTitle = Ui.title(this, book.title, 15, Ui.themeText(this));
        bookTitle.setMaxLines(1);
        chapterTitle = Ui.text(this, 13, Ui.themeMuted(this), false);
        chapterTitle.setMaxLines(1);
        names.addView(bookTitle);
        names.addView(chapterTitle);
        actions.addView(names, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton search = Ui.iconButton(this, "ic_search", "搜索全书");
        search.setOnClickListener(v -> openSearch());
        actions.addView(search, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        ImageButton bookmark = Ui.iconButton(this, "ic_bookmark", "书签");
        bookmark.setOnClickListener(v -> showBookmarkMenu());
        actions.addView(bookmark, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        ImageButton directory = Ui.iconButton(this, "ic_toc", "章节目录");
        directory.setOnClickListener(v -> openDirectory());
        actions.addView(directory, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        ImageButton settings = Ui.iconButton(this, "ic_settings", "阅读设置");
        settings.setOnClickListener(v -> startActivityForResult(
                new Intent(this, SettingsActivity.class), REQUEST_SETTINGS));
        actions.addView(settings, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        topBar.addView(actions);
        progressText = Ui.text(this, 12, Ui.themeMuted(this), false);
        progressText.setGravity(Gravity.END);
        progressText.setPadding(0, Ui.dp(this, 4), 0, 0);
        topBar.addView(progressText);
        content.addView(topBar);

        pageFrame = new FrameLayout(this);
        pageFrame.setBackgroundColor(Ui.themeBackground(this));
        pageBody = new PagedTextView(this, new PageAction() {
            @Override public void previous() { turnPage(-1); }
            @Override public void next() { turnPage(1); }
            @Override public void center() { toggleToolbars(); }
        });
        pageBody.setBackground(Ui.round(this, Ui.themeCard(this), 18));
        pageBody.setElevation(Ui.dp(this, 2));
        pageBody.setContentDescription("阅读正文。点击左侧上一页，点击右侧下一页，点击中间显示工具栏，左右滑动翻页");
        FrameLayout.LayoutParams pageBodyParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        pageBodyParams.setMargins(Ui.dp(this, 12), Ui.dp(this, 8),
                Ui.dp(this, 12), Ui.dp(this, 8));
        pageFrame.addView(pageBody, pageBodyParams);
        pageCounter = Ui.text(this, 11, Ui.themeMuted(this), false);
        pageCounter.setGravity(Gravity.CENTER);
        pageCounter.setPadding(Ui.dp(this, 9), Ui.dp(this, 4),
                Ui.dp(this, 9), Ui.dp(this, 4));
        pageCounter.setBackground(Ui.round(this, Ui.themeBackground(this), 10));
        FrameLayout.LayoutParams counterParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        counterParams.setMargins(0, 0, Ui.dp(this, 26), Ui.dp(this, 18));
        pageFrame.addView(pageCounter, counterParams);
        content.addView(pageFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setBackgroundColor(Ui.themeCard(this));
        bottomBar.setPadding(Ui.dp(this, 10), Ui.dp(this, 5),
                Ui.dp(this, 10), Ui.dp(this, 9));
        panelStatus = Ui.text(this, 11, Ui.themeMuted(this), false);
        panelStatus.setText("左右滑动或点击两侧翻页 · 中间显示工具栏");
        panelStatus.setMaxLines(2);
        bottomBar.addView(panelStatus);
        LinearLayout nav = new LinearLayout(this);
        Button previous = Ui.button(this, "‹ 上一页");
        previous.setOnClickListener(v -> turnPage(-1));
        nav.addView(previous, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button toc = Ui.button(this, "目录");
        toc.setOnClickListener(v -> openDirectory());
        nav.addView(toc, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button next = Ui.button(this, "下一页 ›");
        next.setOnClickListener(v -> turnPage(1));
        nav.addView(next, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        panelButton = Ui.primaryButton(this, "连接水墨屏");
        panelButton.setOnClickListener(v -> {
            if (panelClient == null) startPanel();
            else setDarkMode(!darkMode);
        });
        nav.addView(panelButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f));
        bottomBar.addView(nav);
        content.addView(bottomBar);

        ProgressBar progress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgress(Math.round(book.progress * 1000));
        content.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3)));

        darkOverlay = new View(this);
        darkOverlay.setBackgroundColor(Color.BLACK);
        darkOverlay.setVisibility(View.GONE);
        darkOverlay.setOnClickListener(v -> setDarkMode(false));
        root.addView(darkOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void loadChapter(int chapterIndex, int chapterOffset, int forceEInkPage) {
        if (loadingContent || destroyed) return;
        saveProgress();
        loadingContent = true;
        final int targetChapter = Math.max(0, Math.min(book.totalChapters - 1, chapterIndex));
        final int targetOffset = Math.max(0, chapterOffset);
        final int token = generation.incrementAndGet();
        panelStatus.setText("正在载入章节…");
        new Thread(() -> {
            try {
                Chapter loadedChapter = db.chapter(book.id, targetChapter);
                if (loadedChapter == null) throw new IllegalStateException("章节目录不完整");
                ChapterChunk loadedChunk = db.chunkAt(loadedChapter.id, targetOffset);
                if (loadedChunk == null) throw new IllegalStateException("章节内容为空");
                String text = readChunk(loadedChapter, loadedChunk);
                EInkPageRenderer.PageLayout pages = pageRenderer.layout(
                        ReaderActivity.this, loadedChapter.index + 1, loadedChapter.title, text);
                runOnUiThread(() -> {
                    if (destroyed || token != generation.get()) return;
                    chapter = loadedChapter;
                    chunk = loadedChunk;
                    chunkText = text;
                    eInkPages = pages;
                    loadingContent = false;
                    int local = Math.max(0, targetOffset - chunk.charStart);
                    if (forceEInkPage == PAGE_LAST) {
                        eInkPageIndex = pages.pageCount() - 1;
                    } else if (forceEInkPage >= 0) {
                        eInkPageIndex = Math.min(forceEInkPage, pages.pageCount() - 1);
                    } else {
                        eInkPageIndex = pages.pageAtChar(local);
                    }
                    wantedEInkPage = eInkPageIndex;
                    displayedEInkPage = -1;
                    nextSnapshot = null;
                    nextSnapshotPage = -1;
                    nextPreloaded = false;
                    expectedPreloadPage = -1;
                    preloadBusy = false;
                    activeLayoutSignature = layoutSignature();
                    applyText();
                    showPage(eInkPageIndex, false, 0);
                    if (panelClient == null) {
                        panelStatus.setText("左右滑动或点击两侧翻页 · 中间显示工具栏");
                    }
                    saveLoadedPosition(chunk.charStart + pages.startChar(eInkPageIndex));
                    updateProgress();
                    if (panelClient != null && pageReady) {
                        queueAdvance(wantedEInkPage);
                    } else if (Ui.prefs(this).getBoolean("eink-enabled", false)
                            && !autoEInkAttempted) {
                        autoEInkAttempted = true;
                        String panel = Ui.prefs(this).getString("panel-type", "");
                        if (!panel.isEmpty()) startPanel();
                    }
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    if (destroyed || token != generation.get()) return;
                    loadingContent = false;
                    panelStatus.setText("载入失败：" + error.getMessage());
                    new AlertDialog.Builder(ReaderActivity.this)
                            .setTitle("无法打开章节")
                            .setMessage(String.valueOf(error.getMessage()))
                            .setPositiveButton("返回书架", (d, w) -> finish())
                            .show();
                });
            }
        }, "reader-load").start();
    }

    private String readChunk(Chapter source, ChapterChunk part) throws Exception {
        long length = part.byteEnd - part.byteStart;
        if (length <= 0 || length > 4 * 1024 * 1024L) {
            throw new IllegalStateException("章节分段大小异常");
        }
        byte[] bytes = new byte[(int) length];
        try (RandomAccessFile input = new RandomAccessFile(book.filePath, "r")) {
            input.seek(part.byteStart);
            input.readFully(bytes);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void applyText() {
        String title = chapter.title == null ? "" : chapter.title.trim();
        if (!title.matches("^(?:第\\s*[0-9０-９零〇一二三四五六七八九十百千万两]+\\s*[章节回卷部篇集]|卷\\s*[0-9０-９零〇一二三四五六七八九十百千万两]).*")) {
            title = "第 " + (chapter.index + 1) + " 章 · " + title;
        }
        chapterTitle.setText(title);
        applyReaderStyle();
    }

    private void applyReaderStyle() {
        int margin = Ui.dp(this,
                Ui.prefs(this).getInt("margin", Ui.DEFAULT_MARGIN_DP));
        pageBody.setPageStyle(Ui.themeText(this),
                margin, Ui.dp(this, 20), margin, Ui.dp(this, 42));
        pageFrame.setBackgroundColor(Ui.themeBackground(this));
        pageBody.setBackground(Ui.round(this, Ui.themeCard(this), 18));
        pageCounter.setTextColor(Ui.themeMuted(this));
        pageCounter.setBackground(Ui.round(this, Ui.themeBackground(this), 10));
        applyBrightness();
        if (Ui.prefs(this).getBoolean("keep-awake", false) || darkMode) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private String layoutSignature() {
        return Ui.prefs(this).getInt("font-size", Ui.DEFAULT_FONT_SIZE_SP) + ":"
                + Ui.prefs(this).getFloat("line-spacing", Ui.DEFAULT_LINE_SPACING) + ":"
                + Ui.prefs(this).getString("font-family", Ui.DEFAULT_FONT_FAMILY) + ":"
                + Ui.prefs(this).getString("font-weight", Ui.DEFAULT_FONT_WEIGHT);
    }

    private void showPage(int page, boolean animate, int direction) {
        if (chunkText == null || eInkPages == null || chunkText.isEmpty()) return;
        int safePage = Math.max(0, Math.min(eInkPages.pageCount() - 1, page));
        int start = Math.min(chunkText.length(), eInkPages.startChar(safePage));
        int end = Math.min(chunkText.length(), eInkPages.endChar(safePage));
        if (end < start) end = start;
        displayedPageText = chunkText.substring(start, end);
        pageBody.animate().cancel();
        if (animate) {
            pageBody.setAlpha(0.28f);
            pageBody.setTranslationX(Ui.dp(this, direction >= 0 ? 18 : -18));
            pageBody.animate().alpha(1f).translationX(0f).setDuration(145).start();
        } else {
            pageBody.setAlpha(1f);
            pageBody.setTranslationX(0f);
        }
        pageBody.setPage(eInkPages, safePage, Ui.themeText(this));
        pageBody.setContentDescription(displayedPageText);
        pageCounter.setText((safePage + 1) + " / " + eInkPages.pageCount() + " 页");

        if (currentSnapshot == null || currentSnapshot.pageIndex != safePage) {
            currentSnapshot = nextSnapshot != null && nextSnapshotPage == safePage
                    ? nextSnapshot : null;
        }
        if (currentSnapshot == null) {
            final int token = ++viewGeneration;
            renderPage(safePage, snapshot -> {
                if (destroyed || token != viewGeneration) return;
                currentSnapshot = snapshot;
            });
        }
    }

    private void updateProgress() {
        if (chapter == null) return;
        float chapterProgress = localPosition() / (float) Math.max(1, chapter.charCount);
        float progress = (chapter.index + Math.min(0.999f, chapterProgress))
                / Math.max(1, book.totalChapters);
        progressText.setText("第 " + (chapter.index + 1) + " / " + book.totalChapters
                + " 章  ·  全书 " + Math.round(progress * 100f) + "%");
    }

    private void saveLoadedPosition(int position) {
        if (chapter == null) return;
        int safePosition = Math.max(0, Math.min(chapter.charCount - 1, position));
        float chapterFraction = safePosition / (float) Math.max(1, chapter.charCount);
        float progress = (chapter.index + Math.min(0.999f, chapterFraction))
                / Math.max(1, book.totalChapters);
        book.chapterIndex = chapter.index;
        book.chapterOffset = safePosition;
        book.progress = progress;
        db.saveProgress(book.id, chapter.index, safePosition, progress);
    }

    private int localPosition() {
        if (chunk == null || eInkPages == null) return 0;
        return chunk.charStart + eInkPages.startChar(eInkPageIndex);
    }

    private void saveProgress() {
        if (book == null || chapter == null || chunk == null || loadingContent) return;
        int position = localPosition();
        float chapterFraction = position / (float) Math.max(1, chapter.charCount);
        float progress = (chapter.index + Math.min(0.999f, chapterFraction))
                / Math.max(1, book.totalChapters);
        book.chapterIndex = chapter.index;
        book.chapterOffset = position;
        book.progress = progress;
        db.saveProgress(book.id, chapter.index, position, progress);
    }

    private void openDirectory() {
        saveProgress();
        Intent intent = new Intent(this, DirectoryActivity.class);
        intent.putExtra("bookId", book.id);
        intent.putExtra("chapterIndex", chapter == null ? book.chapterIndex : chapter.index);
        startActivityForResult(intent, REQUEST_DIRECTORY);
    }

    private void openSearch() {
        if (book == null) return;
        saveProgress();
        Intent intent = new Intent(this, SearchActivity.class);
        intent.putExtra("bookId", book.id);
        startActivityForResult(intent, REQUEST_SEARCH);
    }

    private void showBookmarkMenu() {
        if (chapter == null) return;
        new AlertDialog.Builder(this)
                .setTitle("书签")
                .setItems(new String[]{"保存当前页", "查看全部书签"}, (dialog, which) -> {
                    if (which == 0) saveCurrentBookmark();
                    else openBookmarks();
                }).show();
    }

    private void saveCurrentBookmark() {
        String snippet = displayedPageText
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (snippet.length() > 64) snippet = snippet.substring(0, 64) + "…";
        int position = localPosition();
        db.saveBookmark(book.id, chapter.index, position, chapter.title, snippet);
        panelStatus.setText("已保存书签 · 第 " + (chapter.index + 1) + " 章");
        Toast.makeText(this, "当前页书签已保存", Toast.LENGTH_SHORT).show();
    }

    private void openBookmarks() {
        saveProgress();
        Intent intent = new Intent(this, BookmarkActivity.class);
        intent.putExtra("bookId", book.id);
        startActivityForResult(intent, REQUEST_BOOKMARKS);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK) return;
        if ((request == REQUEST_DIRECTORY || request == REQUEST_SEARCH
                || request == REQUEST_BOOKMARKS) && data != null) {
            int index = Math.max(0, data.getIntExtra("chapterIndex", 0));
            int offset = Math.max(0, data.getIntExtra("chapterOffset", 0));
            loadChapter(index, offset, PAGE_AUTO);
        }
    }

    private void toggleToolbars() {
        if (darkMode) return;
        boolean show = topBar.getVisibility() != View.VISIBLE;
        topBar.setVisibility(show ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void startPanel() {
        String panelType = Ui.prefs(this).getString("panel-type", "");
        if (panelType.isEmpty()) {
            Toast.makeText(this, "请先在设置中选择 A0 或 A1 屏幕型号", Toast.LENGTH_LONG).show();
            startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
            return;
        }
        if (panelClient != null) return;
        resetPanelTransferState();
        panelStatus.setText("正在准备 NFC 传输");
        panelClient = new PanelClient(this, this, panelType,
                Ui.prefs(this).getInt("panel-power-ms", 2000),
                Ui.prefs(this).getInt("panel-poll-ms", 100));
        panelClient.start();
    }

    private void queueAdvance(int targetPage) {
        if (eInkPages == null || panelClient == null || !pageReady) return;
        wantedEInkPage = Math.max(0, Math.min(eInkPages.pageCount() - 1, targetPage));
        eInkPageIndex = wantedEInkPage;
        if (advanceBusy) {
            panelStatus.setText("正在等待当前刷新，已排队第 " + (wantedEInkPage + 1) + " 页");
            return;
        }
        EInkPageRenderer.Snapshot cached = currentSnapshot;
        if (cached != null && cached.pageIndex == wantedEInkPage) {
            submitAdvance(cached);
            return;
        }
        cached = nextSnapshot;
        if (cached != null && nextSnapshotPage == wantedEInkPage) {
            if (nextPreloaded) {
                panelStatus.setText("预传已就绪，立即发送快速 D4");
            }
            submitAdvance(cached);
            return;
        }
        panelStatus.setText("正在渲染第 " + (wantedEInkPage + 1) + " 页");
        renderPage(wantedEInkPage, snapshot -> submitAdvance(snapshot));
    }

    private void submitAdvance(EInkPageRenderer.Snapshot snapshot) {
        if (panelClient == null || !pageReady || advanceBusy || destroyed) return;
        if (snapshot == nextSnapshot) nextPreloaded = false;
        String id = panelClient.submitAdvance(snapshot.packed);
        if (id == null) return;
        advancing.put(id, snapshot);
        advanceBusy = true;
        panelStatus.setText("正在发送第 " + (snapshot.pageIndex + 1) + " 页 · 同时准备下一页");
        renderNextAhead(snapshot.pageIndex + 1);
    }

    private void schedulePreload(int page) {
        if (panelClient == null || eInkPages == null || !pageReady || preloadBusy) return;
        if (page < 0 || page >= eInkPages.pageCount()) return;
        if (nextSnapshot != null && nextSnapshotPage == page) {
            preloadBusy = true;
            expectedPreloadPage = page;
            nextPreloaded = false;
            panelStatus.setText("屏幕刷新完成，立即预传第 " + (page + 1) + " 页");
            String id = panelClient.submitPreload(nextSnapshot.packed);
            if (id == null) preloadBusy = false;
            return;
        }
        preloadBusy = true;
        expectedPreloadPage = page;
        nextPreloaded = false;
        panelStatus.setText("当前页已显示，后台预传下一页");
        renderPage(page, snapshot -> {
            if (panelClient == null || !pageReady || destroyed) {
                preloadBusy = false;
                return;
            }
            nextSnapshot = snapshot;
            nextSnapshotPage = page;
            String id = panelClient.submitPreload(snapshot.packed);
            if (id == null) preloadBusy = false;
        });
    }

    private void renderNextAhead(int page) {
        if (panelClient == null || eInkPages == null || !pageReady) return;
        if (page < 0 || page >= eInkPages.pageCount()) return;
        if (nextSnapshot != null && nextSnapshotPage == page) return;
        renderPage(page, snapshot -> {
            if (destroysPanelState()) return;
            nextSnapshot = snapshot;
            nextSnapshotPage = page;
            nextPreloaded = false;
            panelStatus.setText("屏幕刷新期间，第 " + (page + 1) + " 页已准备完成");
        });
    }

    private boolean destroysPanelState() {
        return destroyed || panelClient == null || !pageReady;
    }

    private interface SnapshotCallback {
        void ready(EInkPageRenderer.Snapshot snapshot);
    }

    private void renderPage(int page, SnapshotCallback callback) {
        EInkPageRenderer.PageLayout pages = eInkPages;
        String panelType = Ui.prefs(this).getString("panel-type", "");
        int token = generation.get();
        renderExecutor.execute(() -> {
            try {
                EInkPageRenderer.Snapshot snapshot = pageRenderer.render(
                        this, pages, page, panelType);
                main.post(() -> {
                    if (!destroyed && token == generation.get()) callback.ready(snapshot);
                });
            } catch (Throwable error) {
                main.post(() -> {
                    if (!destroyed) {
                        advanceBusy = false;
                        preloadBusy = false;
                        panelStatus.setText("页面渲染失败：" + error.getMessage());
                    }
                });
            }
        });
    }

    private void turnPage(int delta) {
        if (eInkPages == null || chapter == null || chunk == null || loadingContent) return;
        int target = wantedEInkPage + delta;
        if (target >= 0 && target < eInkPages.pageCount()) {
            eInkPageIndex = target;
            wantedEInkPage = target;
            showPage(target, true, delta);
            saveLoadedPosition(chunk.charStart + eInkPages.startChar(target));
            queueAdvance(target);
            return;
        }
        if (delta > 0) {
            ChapterChunk following = db.nextChunk(chapter.id, chunk.index);
            if (following != null) {
                loadChapter(chapter.index, following.charStart, 0);
            } else if (chapter.index + 1 < book.totalChapters) {
                loadChapter(chapter.index + 1, 0, 0);
            } else {
                Toast.makeText(this, "已经是全书最后一页", Toast.LENGTH_SHORT).show();
            }
        } else {
            ChapterChunk previous = db.previousChunk(chapter.id, chunk.index);
            if (previous != null) {
                loadChapter(chapter.index, previous.charStart, PAGE_LAST);
            } else if (chapter.index > 0) {
                Chapter previousChapter = db.chapter(book.id, chapter.index - 1);
                if (previousChapter != null) loadChapter(previousChapter.index,
                        Math.max(0, previousChapter.charCount - 1), PAGE_LAST);
            } else {
                Toast.makeText(this, "已经是全书第一页", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (eInkPages != null && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.getRepeatCount() == 0) {
                turnPage(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ? 1 : -1);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (eInkPages != null && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true;
        return super.onKeyUp(keyCode, event);
    }

    private void setDarkMode(boolean enabled) {
        if (enabled && panelClient == null) {
            startPanel();
            if (panelClient == null) return;
        }
        darkMode = enabled;
        darkOverlay.setVisibility(enabled ? View.VISIBLE : View.GONE);
        topBar.setVisibility(enabled ? View.GONE : View.VISIBLE);
        bottomBar.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            getWindow().getAttributes().screenBrightness = 0f;
            getWindow().setAttributes(getWindow().getAttributes());
            panelButton.setText("退出息屏");
            panelButton.setContentDescription("退出水墨屏息屏模式；音量键翻页");
        } else {
            panelButton.setText(panelClient != null && pageReady ? "息屏翻页" : "连接水墨屏");
            applyBrightness();
            Ui.applyWindow(this, Ui.isNight(this));
            if (!Ui.prefs(this).getBoolean("keep-awake", false)) {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }
    }

    private void applyBrightness() {
        if (darkMode) return;
        if (Ui.prefs(this).getBoolean("follow-system-brightness", true)) {
            getWindow().getAttributes().screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        } else {
            getWindow().getAttributes().screenBrightness =
                    Math.max(0.02f, Ui.prefs(this).getInt("brightness", 170) / 255f);
        }
        getWindow().setAttributes(getWindow().getAttributes());
    }

    @Override public void onPanelStatus(String message) {
        panelStatus.setText(message);
    }

    @Override public void onPanelReady() {
        pageReady = true;
        panelButton.setText("息屏翻页");
        panelStatus.setText("水墨屏已连接 · 音量键翻页");
        queueAdvance(wantedEInkPage);
    }

    @Override public void onPreloadDone(String id) {
        preloadBusy = false;
        nextPreloaded = true;
        if (nextSnapshot != null && nextSnapshotPage == expectedPreloadPage) {
            panelStatus.setText("第 " + (nextSnapshotPage + 1) + " 页已预传 · 翻页将只刷新");
        }
    }

    @Override public void onAdvanced(String id, boolean fast, long millis) {
        EInkPageRenderer.Snapshot snapshot = advancing.remove(id);
        advanceBusy = false;
        if (snapshot != null) {
            displayedEInkPage = snapshot.pageIndex;
            if (fast) {
                panelStatus.setText("预传命中 · D4 刷新 " + millis + " ms");
            } else {
                panelStatus.setText("第 " + (snapshot.pageIndex + 1) + " 页已刷新 · 开始预传下一页");
            }
        }
        if (wantedEInkPage != displayedEInkPage) {
            queueAdvance(wantedEInkPage);
        } else {
            eInkPageIndex = displayedEInkPage;
            saveLoadedPosition(chunk.charStart
                    + eInkPages.startChar(displayedEInkPage));
            schedulePreload(displayedEInkPage + 1);
        }
    }

    @Override public void onPanelFailure(String message) {
        resetPanelTransferState();
        panelStatus.setText(message);
        if (panelClient != null) {
            panelClient.close();
            panelClient = null;
        }
        panelButton.setText("重新连接水墨屏");
    }

    @Override protected void onResume() {
        super.onResume();
        if (book == null) return;
        String currentTheme = Ui.prefs(this).getString("theme", "paper");
        if (!currentTheme.equals(activeTheme)) {
            saveProgress();
            recreate();
            return;
        }
        Ui.applyWindow(this, Ui.isNight(this));
        if (!loadingContent && chunk != null) {
            if (activeLayoutSignature != null
                    && !activeLayoutSignature.equals(layoutSignature())) {
                int position = localPosition();
                loadChapter(chapter.index, position, PAGE_AUTO);
                if (panelClient == null
                        && Ui.prefs(this).getBoolean("eink-enabled", false)) {
                    String panel = Ui.prefs(this).getString("panel-type", "");
                    if (!panel.isEmpty()) startPanel();
                }
                return;
            }
            applyReaderStyle();
        }
        if (panelClient == null && chunk != null
                && Ui.prefs(this).getBoolean("eink-enabled", false)) {
            String panel = Ui.prefs(this).getString("panel-type", "");
            if (!panel.isEmpty()) startPanel();
        }
    }

    @Override protected void onPause() {
        saveProgress();
        super.onPause();
    }

    @Override protected void onStop() {
        saveProgress();
        if (panelClient != null) {
            panelClient.close();
            panelClient = null;
        }
        resetPanelTransferState();
        setDarkMode(false);
        super.onStop();
    }

    private void resetPanelTransferState() {
        pageReady = false;
        advanceBusy = false;
        preloadBusy = false;
        advancing.clear();
        nextSnapshot = null;
        nextSnapshotPage = -1;
        nextPreloaded = false;
        expectedPreloadPage = -1;
        displayedEInkPage = -1;
    }

    @Override protected void onDestroy() {
        destroyed = true;
        generation.incrementAndGet();
        saveProgress();
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        if (panelClient != null) panelClient.close();
        renderExecutor.shutdownNow();
        db.close();
        super.onDestroy();
    }

    private interface PageAction {
        void previous();
        void next();
        void center();
    }

    private static final class PagedTextView extends View {
        private final PageAction action;
        private final GestureDetector gestures;
        private EInkPageRenderer.PageLayout pages;
        private int pageIndex;
        private int textColor;

        PagedTextView(Context context, PageAction action) {
            super(context);
            this.action = action;
            setWillNotDraw(false);
            gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) {
                    return true;
                }

                @Override public boolean onSingleTapUp(MotionEvent event) {
                    float x = event.getX() / Math.max(1f, getWidth());
                    if (x < 0.29f) action.previous();
                    else if (x > 0.71f) action.next();
                    else action.center();
                    performClick();
                    return true;
                }

                @Override public boolean onFling(MotionEvent first, MotionEvent second,
                                                 float velocityX, float velocityY) {
                    float dx = second.getX() - first.getX();
                    float dy = second.getY() - first.getY();
                    if (Math.abs(dx) > Ui.dp(getContext(), 48)
                            && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                        if (dx < 0) action.next();
                        else action.previous();
                        return true;
                    }
                    return false;
                }
            });
            setFocusable(true);
            setClickable(true);
        }

        void setPageStyle(int color, int left, int top, int right, int bottom) {
            textColor = color;
            setPadding(left, top, right, bottom);
            invalidate();
        }

        void setPage(EInkPageRenderer.PageLayout pages, int pageIndex, int color) {
            this.pages = pages;
            this.pageIndex = Math.max(0, Math.min(pages.pageCount() - 1, pageIndex));
            this.textColor = color;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (pages == null) return;
            pages.drawPageText(canvas, pageIndex, textColor,
                    getPaddingLeft(), getPaddingTop(),
                    getWidth() - getPaddingLeft() - getPaddingRight(),
                    getHeight() - getPaddingTop() - getPaddingBottom());
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            return gestures.onTouchEvent(event);
        }
    }
}

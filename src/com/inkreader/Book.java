package com.inkreader;

public final class Book {
    public long id;
    public String title;
    public String author;
    public String filePath;
    public String sourceHash;
    public long importedAt;
    public long lastReadAt;
    public int chapterIndex;
    public int chapterOffset;
    public float progress;
    public int totalChapters;

    public String progressText() {
        if (chapterIndex <= 0 && progress <= 0f) return "尚未阅读";
        return "已读 " + Math.round(Math.max(0f, Math.min(1f, progress)) * 100f) + "%";
    }
}

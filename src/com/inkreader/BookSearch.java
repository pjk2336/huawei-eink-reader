package com.inkreader;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BookSearch {
    public static final int MAX_RESULTS = 200;

    public interface Progress {
        void update(int percent, int chaptersScanned);
    }

    private BookSearch() {
    }

    public static List<SearchHit> search(ReaderDb db, Book book, String rawQuery,
                                         Progress progress, AtomicBoolean cancelled)
            throws Exception {
        String query = rawQuery == null ? "" : rawQuery.trim();
        ArrayList<SearchHit> results = new ArrayList<>();
        if (query.isEmpty()) return results;
        int pageSize = 120;
        int segmentOffset = 0;
        try (RandomAccessFile input = new RandomAccessFile(book.filePath, "r")) {
            while (segmentOffset < Integer.MAX_VALUE && results.size() < MAX_RESULTS) {
                if (cancelled != null && cancelled.get()) break;
                List<SearchSegment> segments = db.searchSegments(
                        book.id, segmentOffset, pageSize);
                if (segments.isEmpty()) break;
                for (SearchSegment segment : segments) {
                    if (cancelled != null && cancelled.get()) break;
                    long length = segment.byteEnd - segment.byteStart;
                    if (length <= 0 || length > 4L * 1024L * 1024L) continue;
                    byte[] bytes = new byte[(int) length];
                    input.seek(segment.byteStart);
                    input.readFully(bytes);
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    int from = 0;
                    int perSegment = 0;
                    while (from <= text.length() - query.length() && perSegment < 12) {
                        int match = indexOfIgnoreCase(text, query, from);
                        if (match < 0) break;
                        SearchHit hit = new SearchHit();
                        hit.chapterIndex = segment.chapterIndex;
                        hit.chapterOffset = segment.charStart + match;
                        hit.chapterTitle = segment.chapterTitle;
                        hit.snippet = snippet(text, match, query.length());
                        hit.query = query;
                        results.add(hit);
                        perSegment++;
                        if (results.size() >= MAX_RESULTS) break;
                        from = match + Math.max(1, query.length());
                    }
                    if (results.size() >= MAX_RESULTS) break;
                }
                int lastChapter = segments.get(segments.size() - 1).chapterIndex;
                if (progress != null) {
                    int percent = book.totalChapters <= 0 ? 0
                            : Math.min(99, (lastChapter + 1) * 100 / book.totalChapters);
                    progress.update(percent, lastChapter + 1);
                }
                segmentOffset += segments.size();
                if (segments.size() < pageSize) break;
            }
        }
        return results;
    }

    private static int indexOfIgnoreCase(String text, String query, int from) {
        int last = text.length() - query.length();
        for (int i = Math.max(0, from); i <= last; i++) {
            if (text.regionMatches(true, i, query, 0, query.length())) return i;
        }
        return -1;
    }

    private static String snippet(String text, int match, int length) {
        int start = Math.max(0, match - 32);
        int end = Math.min(text.length(), match + length + 48);
        String value = text.substring(start, end)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return (start > 0 ? "…" : "") + value + (end < text.length() ? "…" : "");
    }
}

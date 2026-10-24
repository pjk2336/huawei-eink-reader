package com.inkreader;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChapterDetector {
    private static final Pattern CHAPTER = Pattern.compile(
            "^\\s*((?:第\\s*[0-9０-９零〇一二三四五六七八九十百千万两]+\\s*[章节回卷部篇集])"
                    + "|(?:卷\\s*[0-9０-９零〇一二三四五六七八九十百千万两]+)"
                    + "|(?:楔子|序章|序言|引子|前言|尾声|后记|终章|番外(?:\\s*[0-9一二三四五六七八九十]+)?))"
                    + "(?:\\s*[：:、.．·\\-—]?\\s*)(.{0,56})\\s*$");
    private static final Pattern AUTHOR = Pattern.compile("^\\s*作\\s*者\\s*[：:]\\s*(.{1,30}?)\\s*$");

    private ChapterDetector() {
    }

    public static String chapterTitle(String line) {
        if (line == null) return null;
        String value = line.replace('\u3000', ' ').trim();
        if (value.isEmpty() || value.length() > 76) return null;
        Matcher match = CHAPTER.matcher(value);
        if (!match.matches()) return null;
        String rest = match.group(2);
        if (rest.matches(".*[。！？；，,].*")) return null;
        if (rest.matches("^(?:正文)?(?:结束|完|终)$")) return null;
        return value;
    }

    public static String author(String line) {
        if (line == null) return null;
        Matcher match = AUTHOR.matcher(line.replace('\u3000', ' '));
        return match.matches() ? match.group(1).trim() : null;
    }

    public static String titleFromFilename(String name) {
        if (name == null) return "未命名";
        String title = name.trim();
        int slash = Math.max(title.lastIndexOf('/'), title.lastIndexOf('\\'));
        if (slash >= 0) title = title.substring(slash + 1);
        String lower = title.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".txt")) title = title.substring(0, title.length() - 4);
        return title.trim().isEmpty() ? "未命名" : title.trim();
    }
}

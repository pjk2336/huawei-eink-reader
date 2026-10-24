package com.inkreader;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Locale;

public final class TxtImporter {
    public static final long MAX_SOURCE_BYTES = 32L * 1024L * 1024L;
    private static final int SAMPLE_BYTES = 64 * 1024;
    private static final int CHUNK_CHARS = 48 * 1024;

    public interface Progress {
        void update(int percent, String message);
        boolean cancelled();
    }

    public static final class Result {
        public final boolean success;
        public final boolean duplicate;
        public final long bookId;
        public final String message;

        private Result(boolean success, boolean duplicate, long bookId, String message) {
            this.success = success;
            this.duplicate = duplicate;
            this.bookId = bookId;
            this.message = message;
        }

        public static Result ok(long id, String message) {
            return new Result(true, false, id, message);
        }

        public static Result duplicate(String message) {
            return new Result(false, true, -1, message);
        }
    }

    private final Context context;
    private final ReaderDb db;

    public TxtImporter(Context context, ReaderDb db) {
        this.context = context.getApplicationContext();
        this.db = db;
    }

    public Result importUri(Uri uri, Progress progress) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        String displayName = displayName(resolver, uri);
        long sourceSize = sourceSize(resolver, uri);
        if (sourceSize > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("文件超过 32 MB 上限");
        }
        Charset charset = detectCharset(resolver, uri);
        ImportData data = new ImportData();
        data.title = ChapterDetector.titleFromFilename(displayName);
        data.sourceName = displayName;

        File booksDir = new File(context.getFilesDir(), "books");
        if (!booksDir.exists() && !booksDir.mkdirs()) throw new IllegalStateException("无法创建本地书库目录");
        File temp = File.createTempFile("import-", ".txt", booksDir);
        long outputBytes = 0;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ImportData.ChapterDraft current = null;
        ImportData.ChunkDraft currentChunk = null;
        boolean sawHeading = false;
        int metadataLines = 0;
        long lastUpdate = 0;
        try (InputStream base = resolver.openInputStream(uri)) {
            if (base == null) throw new IllegalArgumentException("无法打开文件");
            CountingInputStream counted = new CountingInputStream(base, MAX_SOURCE_BYTES);
            DigestInputStream input = new DigestInputStream(counted, digest);
            PushbackInputStream decodedInput = new PushbackInputStream(input, 4);
            skipBom(decodedInput, charset);
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(decodedInput, decoder), 64 * 1024);
                 FileOutputStream output = new FileOutputStream(temp)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (progress.cancelled()) throw new InterruptedException("已取消导入");
                    String trimmed = line.trim();
                    String heading = ChapterDetector.chapterTitle(trimmed);
                    if (heading != null) {
                        if (current != null) finishChapter(current, outputBytes);
                        current = newChapter(heading, outputBytes);
                        data.chapters.add(current);
                        currentChunk = null;
                        sawHeading = true;
                    } else if (current == null && !trimmed.isEmpty()) {
                        current = newChapter("前言", 0);
                        data.chapters.add(current);
                        currentChunk = null;
                    }

                    if (metadataLines++ < 240 && !trimmed.isEmpty()) {
                        String author = ChapterDetector.author(trimmed);
                        if (author != null && !author.isEmpty()) data.author = author;
                    }

                    if (current != null) {
                        if (currentChunk == null || currentChunk.charCount >= CHUNK_CHARS) {
                            if (currentChunk != null) currentChunk.byteEnd = outputBytes;
                            currentChunk = newChunk(outputBytes, current.charCount);
                            current.chunks.add(currentChunk);
                        }
                    }

                    byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
                    output.write(bytes);
                    outputBytes += bytes.length;
                    if (current != null) {
                        current.charCount += line.length() + 1;
                        currentChunk.charCount += line.length() + 1;
                    }
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastUpdate > 180) {
                        lastUpdate = now;
                        int percent = sourceSize > 0
                                ? (int) Math.min(99, counted.count * 100L / sourceSize)
                                : -1;
                        progress.update(percent, "正在识别编码与章节");
                    }
                }
            }
            if (current != null) finishChapter(current, outputBytes);
            else {
                current = newChapter("全文", 0);
                current.charCount = 0;
                current.chunks.add(newChunk(0, 0));
                current.chunks.get(0).byteEnd = outputBytes;
                current.chunks.get(0).charCount = 0;
                current.byteEnd = outputBytes;
                data.chapters.add(current);
            }
            if (!sawHeading && data.chapters.size() == 1
                    && "前言".equals(data.chapters.get(0).title)) {
                data.chapters.get(0).title = "全文";
            }
            data.byteLength = outputBytes;
            data.sha256 = hex(digest.digest());
            progress.update(100, "正在写入本地书架");

            if (db.hashExists(data.sha256)) {
                temp.delete();
                return Result.duplicate("《" + data.title + "》已经导入过");
            }
            File finalFile = new File(booksDir, "book-" + data.sha256 + ".txt");
            if (finalFile.exists() && !finalFile.delete()) throw new IllegalStateException("无法清理旧文件");
            if (!temp.renameTo(finalFile)) throw new IllegalStateException("无法保存导入文件");
            temp = finalFile;
            long bookId = db.insertImported(data, finalFile.getAbsolutePath());
            temp = null;
            String chapters = data.chapters.size() <= 1
                    ? "单章全文"
                    : data.chapters.size() + " 章";
            return Result.ok(bookId, "已导入《" + data.title + "》 · " + chapters
                    + " · " + charset.name());
        } finally {
            if (temp != null && temp.exists()) temp.delete();
        }
    }

    public static Charset detectCharset(ContentResolver resolver, Uri uri) throws Exception {
        byte[] sample = new byte[SAMPLE_BYTES];
        int length;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("无法读取文件");
            length = 0;
            while (length < sample.length) {
                int n = input.read(sample, length, sample.length - length);
                if (n < 0) break;
                length += n;
            }
        }
        if (length >= 3 && (sample[0] & 255) == 0xEF && (sample[1] & 255) == 0xBB
                && (sample[2] & 255) == 0xBF) return StandardCharsets.UTF_8;
        if (length >= 2 && (sample[0] & 255) == 0xFF && (sample[1] & 255) == 0xFE)
            return StandardCharsets.UTF_16LE;
        if (length >= 2 && (sample[0] & 255) == 0xFE && (sample[1] & 255) == 0xFF)
            return StandardCharsets.UTF_16BE;
        int zeroEven = 0, zeroOdd = 0;
        for (int i = 1; i < length; i += 2) {
            if (sample[i] == 0) zeroOdd++;
            if (i + 1 < length && sample[i + 1] == 0) zeroEven++;
        }
        int pairs = Math.max(1, length / 2);
        if (zeroOdd * 100 / pairs > 35) return StandardCharsets.UTF_16LE;
        if (zeroEven * 100 / pairs > 35) return StandardCharsets.UTF_16BE;
        if (isUtf8Prefix(sample, length)) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName("GB18030");
        } catch (Exception unsupported) {
            return Charset.forName("GBK");
        }
    }

    /**
     * A fixed-size sample can cut a UTF-8 character in the middle. Try the
     * complete sample, then remove at most three trailing continuation bytes.
     * Invalid bytes inside the sample still fail and fall back to GB18030.
     */
    private static boolean isUtf8Prefix(byte[] sample, int length) {
        int maxTrim = Math.min(3, Math.max(0, length - 1));
        for (int trim = 0; trim <= maxTrim; trim++) {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                decoder.decode(ByteBuffer.wrap(sample, 0, length - trim));
                return true;
            } catch (CharacterCodingException ignored) {
                // Try one fewer byte in case the sample ended inside a code point.
            }
        }
        return false;
    }

    private static void skipBom(PushbackInputStream input, Charset charset) throws Exception {
        byte[] prefix = new byte[3];
        int length = 0;
        while (length < prefix.length) {
            int n = input.read(prefix, length, prefix.length - length);
            if (n < 0) break;
            length += n;
        }
        int bom = 0;
        if (StandardCharsets.UTF_8.equals(charset) && length >= 3
                && (prefix[0] & 255) == 0xEF && (prefix[1] & 255) == 0xBB
                && (prefix[2] & 255) == 0xBF) {
            bom = 3;
        } else if (StandardCharsets.UTF_16LE.equals(charset) && length >= 2
                && (prefix[0] & 255) == 0xFF && (prefix[1] & 255) == 0xFE) {
            bom = 2;
        } else if (StandardCharsets.UTF_16BE.equals(charset) && length >= 2
                && (prefix[0] & 255) == 0xFE && (prefix[1] & 255) == 0xFF) {
            bom = 2;
        }
        if (length > bom) input.unread(prefix, bom, length - bom);
    }

    private static ImportData.ChapterDraft newChapter(String title, long byteStart) {
        ImportData.ChapterDraft chapter = new ImportData.ChapterDraft();
        chapter.title = title;
        chapter.byteStart = byteStart;
        return chapter;
    }

    private static ImportData.ChunkDraft newChunk(long byteStart, int charStart) {
        ImportData.ChunkDraft chunk = new ImportData.ChunkDraft();
        chunk.byteStart = byteStart;
        chunk.charStart = charStart;
        return chunk;
    }

    private static void finishChapter(ImportData.ChapterDraft chapter, long end) {
        chapter.byteEnd = end;
        if (chapter.chunks.isEmpty()) return;
        chapter.chunks.get(chapter.chunks.size() - 1).byteEnd = end;
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name;
            }
        } catch (Exception ignored) {
        }
        String fallback = uri.getLastPathSegment();
        return fallback == null ? "未命名.txt" : fallback;
    }

    private static long sourceSize(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }

    private static final class CountingInputStream extends InputStream {
        private final InputStream delegate;
        private final long limit;
        long count;

        CountingInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override public int read() throws java.io.IOException {
            int value = delegate.read();
            if (value >= 0) {
                count++;
                if (count > limit) throw new java.io.IOException("文件超过 32 MB 上限");
            }
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count += n;
                if (count > limit) throw new java.io.IOException("文件超过 32 MB 上限");
            }
            return n;
        }

        @Override public void close() throws java.io.IOException {
            delegate.close();
        }
    }
}

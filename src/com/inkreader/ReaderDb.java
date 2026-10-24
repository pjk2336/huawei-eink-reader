package com.inkreader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class ReaderDb extends SQLiteOpenHelper {
    public static final String SORT_RECENT = "recent";
    public static final String SORT_TITLE = "title";
    private static final String DATABASE = "ink-reader.db";
    private static final int VERSION = 2;

    public ReaderDb(Context context) {
        super(context, DATABASE, null, VERSION);
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL,author TEXT NOT NULL,file_path TEXT NOT NULL,"
                + "source_hash TEXT NOT NULL UNIQUE,imported_at INTEGER NOT NULL,"
                + "last_read_at INTEGER NOT NULL DEFAULT 0,chapter_index INTEGER NOT NULL DEFAULT 0,"
                + "chapter_offset INTEGER NOT NULL DEFAULT 0,progress REAL NOT NULL DEFAULT 0,"
                + "total_chapters INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE chapters("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,book_id INTEGER NOT NULL,"
                + "chapter_index INTEGER NOT NULL,title TEXT NOT NULL,byte_start INTEGER NOT NULL,"
                + "byte_end INTEGER NOT NULL,char_count INTEGER NOT NULL,"
                + "UNIQUE(book_id,chapter_index),FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE)");
        db.execSQL("CREATE TABLE chunks("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,chapter_id INTEGER NOT NULL,"
                + "chunk_index INTEGER NOT NULL,byte_start INTEGER NOT NULL,byte_end INTEGER NOT NULL,"
                + "char_start INTEGER NOT NULL,char_count INTEGER NOT NULL,"
                + "UNIQUE(chapter_id,chunk_index),FOREIGN KEY(chapter_id) REFERENCES chapters(id) ON DELETE CASCADE)");
        db.execSQL("CREATE TABLE bookmarks("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,book_id INTEGER NOT NULL,"
                + "chapter_index INTEGER NOT NULL,chapter_offset INTEGER NOT NULL,"
                + "chapter_title TEXT NOT NULL,snippet TEXT NOT NULL,created_at INTEGER NOT NULL,"
                + "UNIQUE(book_id,chapter_index,chapter_offset),"
                + "FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX chapters_book_index ON chapters(book_id,chapter_index)");
        db.execSQL("CREATE INDEX chunks_chapter_index ON chunks(chapter_id,char_start)");
        db.execSQL("CREATE INDEX bookmarks_book_time ON bookmarks(book_id,created_at DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,book_id INTEGER NOT NULL,"
                    + "chapter_index INTEGER NOT NULL,chapter_offset INTEGER NOT NULL,"
                    + "chapter_title TEXT NOT NULL,snippet TEXT NOT NULL,created_at INTEGER NOT NULL,"
                    + "UNIQUE(book_id,chapter_index,chapter_offset),"
                    + "FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE)");
            db.execSQL("CREATE INDEX IF NOT EXISTS bookmarks_book_time "
                    + "ON bookmarks(book_id,created_at DESC)");
        }
    }

    public synchronized long insertImported(ImportData data, String filePath) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues book = new ContentValues();
            long now = System.currentTimeMillis();
            book.put("title", data.title);
            book.put("author", data.author);
            book.put("file_path", filePath);
            book.put("source_hash", data.sha256);
            book.put("imported_at", now);
            book.put("last_read_at", 0);
            book.put("chapter_index", 0);
            book.put("chapter_offset", 0);
            book.put("progress", 0f);
            book.put("total_chapters", data.chapters.size());
            long bookId = db.insertOrThrow("books", null, book);
            for (int i = 0; i < data.chapters.size(); i++) {
                ImportData.ChapterDraft chapter = data.chapters.get(i);
                ContentValues row = new ContentValues();
                row.put("book_id", bookId);
                row.put("chapter_index", i);
                row.put("title", chapter.title);
                row.put("byte_start", chapter.byteStart);
                row.put("byte_end", chapter.byteEnd);
                row.put("char_count", chapter.charCount);
                long chapterId = db.insertOrThrow("chapters", null, row);
                for (int c = 0; c < chapter.chunks.size(); c++) {
                    ImportData.ChunkDraft chunk = chapter.chunks.get(c);
                    ContentValues chunkRow = new ContentValues();
                    chunkRow.put("chapter_id", chapterId);
                    chunkRow.put("chunk_index", c);
                    chunkRow.put("byte_start", chunk.byteStart);
                    chunkRow.put("byte_end", chunk.byteEnd);
                    chunkRow.put("char_start", chunk.charStart);
                    chunkRow.put("char_count", chunk.charCount);
                    db.insertOrThrow("chunks", null, chunkRow);
                }
            }
            db.setTransactionSuccessful();
            return bookId;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized boolean hashExists(String sha256) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT 1 FROM books WHERE source_hash=? LIMIT 1", new String[]{sha256})) {
            return cursor.moveToFirst();
        }
    }

    public synchronized List<Book> listBooks(String sort) {
        String order = SORT_TITLE.equals(sort)
                ? "title COLLATE NOCASE ASC, imported_at DESC"
                : "CASE WHEN last_read_at=0 THEN 1 ELSE 0 END,last_read_at DESC,imported_at DESC";
        ArrayList<Book> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,title,author,file_path,source_hash,imported_at,last_read_at,"
                        + "chapter_index,chapter_offset,progress,total_chapters FROM books ORDER BY " + order, null)) {
            while (cursor.moveToNext()) result.add(readBook(cursor));
        }
        return result;
    }

    public synchronized Book book(long id) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,title,author,file_path,source_hash,imported_at,last_read_at,"
                        + "chapter_index,chapter_offset,progress,total_chapters FROM books WHERE id=?",
                new String[]{Long.toString(id)})) {
            return cursor.moveToFirst() ? readBook(cursor) : null;
        }
    }

    public synchronized int chapterCount(long bookId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM chapters WHERE book_id=?", new String[]{Long.toString(bookId)})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    public synchronized List<Chapter> chapters(long bookId, int offset, int limit) {
        ArrayList<Chapter> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,book_id,chapter_index,title,byte_start,byte_end,char_count "
                        + "FROM chapters WHERE book_id=? ORDER BY chapter_index LIMIT ? OFFSET ?",
                new String[]{Long.toString(bookId), Integer.toString(limit), Integer.toString(offset)})) {
            while (cursor.moveToNext()) result.add(readChapter(cursor));
        }
        return result;
    }

    public synchronized Chapter chapter(long bookId, int index) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,book_id,chapter_index,title,byte_start,byte_end,char_count "
                        + "FROM chapters WHERE book_id=? AND chapter_index=?",
                new String[]{Long.toString(bookId), Integer.toString(index)})) {
            return cursor.moveToFirst() ? readChapter(cursor) : null;
        }
    }

    public synchronized ChapterChunk chunkAt(long chapterId, int position) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,chapter_id,chunk_index,byte_start,byte_end,char_start,char_count "
                        + "FROM chunks WHERE chapter_id=? AND char_start<=? ORDER BY char_start DESC LIMIT 1",
                new String[]{Long.toString(chapterId), Integer.toString(Math.max(0, position))})) {
            if (cursor.moveToFirst()) return readChunk(cursor);
        }
        return firstChunk(chapterId);
    }

    public synchronized ChapterChunk firstChunk(long chapterId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,chapter_id,chunk_index,byte_start,byte_end,char_start,char_count "
                        + "FROM chunks WHERE chapter_id=? ORDER BY chunk_index LIMIT 1",
                new String[]{Long.toString(chapterId)})) {
            return cursor.moveToFirst() ? readChunk(cursor) : null;
        }
    }

    public synchronized ChapterChunk nextChunk(long chapterId, int chunkIndex) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,chapter_id,chunk_index,byte_start,byte_end,char_start,char_count "
                        + "FROM chunks WHERE chapter_id=? AND chunk_index>? ORDER BY chunk_index LIMIT 1",
                new String[]{Long.toString(chapterId), Integer.toString(chunkIndex)})) {
            return cursor.moveToFirst() ? readChunk(cursor) : null;
        }
    }

    public synchronized ChapterChunk previousChunk(long chapterId, int chunkIndex) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,chapter_id,chunk_index,byte_start,byte_end,char_start,char_count "
                        + "FROM chunks WHERE chapter_id=? AND chunk_index<? ORDER BY chunk_index DESC LIMIT 1",
                new String[]{Long.toString(chapterId), Integer.toString(chunkIndex)})) {
            return cursor.moveToFirst() ? readChunk(cursor) : null;
        }
    }

    public synchronized void saveProgress(long bookId, int chapterIndex, int chapterOffset, float progress) {
        ContentValues values = new ContentValues();
        values.put("chapter_index", Math.max(0, chapterIndex));
        values.put("chapter_offset", Math.max(0, chapterOffset));
        values.put("progress", Math.max(0f, Math.min(1f, progress)));
        values.put("last_read_at", System.currentTimeMillis());
        getWritableDatabase().update("books", values, "id=?", new String[]{Long.toString(bookId)});
    }

    public synchronized String deleteBook(long bookId) {
        Book book = book(bookId);
        String path = book == null ? null : book.filePath;
        getWritableDatabase().delete("books", "id=?", new String[]{Long.toString(bookId)});
        return path;
    }

    public synchronized long saveBookmark(long bookId, int chapterIndex, int chapterOffset,
                                          String chapterTitle, String snippet) {
        ContentValues values = new ContentValues();
        values.put("book_id", bookId);
        values.put("chapter_index", chapterIndex);
        values.put("chapter_offset", chapterOffset);
        values.put("chapter_title", chapterTitle);
        values.put("snippet", snippet);
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(
                "bookmarks", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized List<Bookmark> bookmarks(long bookId) {
        ArrayList<Bookmark> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT id,book_id,chapter_index,chapter_offset,chapter_title,snippet,created_at "
                        + "FROM bookmarks WHERE book_id=? ORDER BY created_at DESC",
                new String[]{Long.toString(bookId)})) {
            while (cursor.moveToNext()) {
                Bookmark bookmark = new Bookmark();
                bookmark.id = cursor.getLong(0);
                bookmark.bookId = cursor.getLong(1);
                bookmark.chapterIndex = cursor.getInt(2);
                bookmark.chapterOffset = cursor.getInt(3);
                bookmark.chapterTitle = cursor.getString(4);
                bookmark.snippet = cursor.getString(5);
                bookmark.createdAt = cursor.getLong(6);
                result.add(bookmark);
            }
        }
        return result;
    }

    public synchronized void deleteBookmark(long bookmarkId) {
        getWritableDatabase().delete("bookmarks", "id=?",
                new String[]{Long.toString(bookmarkId)});
    }

    public synchronized List<SearchSegment> searchSegments(long bookId, int offset, int limit) {
        ArrayList<SearchSegment> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT c.chapter_index,c.title,ch.byte_start,ch.byte_end,"
                        + "ch.char_start,ch.char_count "
                        + "FROM chunks ch JOIN chapters c ON c.id=ch.chapter_id "
                        + "WHERE c.book_id=? ORDER BY c.chapter_index,ch.chunk_index LIMIT ? OFFSET ?",
                new String[]{Long.toString(bookId), Integer.toString(limit),
                        Integer.toString(offset)})) {
            while (cursor.moveToNext()) {
                SearchSegment segment = new SearchSegment();
                segment.chapterIndex = cursor.getInt(0);
                segment.chapterTitle = cursor.getString(1);
                segment.byteStart = cursor.getLong(2);
                segment.byteEnd = cursor.getLong(3);
                segment.charStart = cursor.getInt(4);
                segment.charCount = cursor.getInt(5);
                result.add(segment);
            }
        }
        return result;
    }

    private static Book readBook(Cursor cursor) {
        Book book = new Book();
        book.id = cursor.getLong(0);
        book.title = cursor.getString(1);
        book.author = cursor.getString(2);
        book.filePath = cursor.getString(3);
        book.sourceHash = cursor.getString(4);
        book.importedAt = cursor.getLong(5);
        book.lastReadAt = cursor.getLong(6);
        book.chapterIndex = cursor.getInt(7);
        book.chapterOffset = cursor.getInt(8);
        book.progress = cursor.getFloat(9);
        book.totalChapters = cursor.getInt(10);
        return book;
    }

    private static Chapter readChapter(Cursor cursor) {
        Chapter chapter = new Chapter();
        chapter.id = cursor.getLong(0);
        chapter.bookId = cursor.getLong(1);
        chapter.index = cursor.getInt(2);
        chapter.title = cursor.getString(3);
        chapter.byteStart = cursor.getLong(4);
        chapter.byteEnd = cursor.getLong(5);
        chapter.charCount = cursor.getInt(6);
        return chapter;
    }

    private static ChapterChunk readChunk(Cursor cursor) {
        ChapterChunk chunk = new ChapterChunk();
        chunk.id = cursor.getLong(0);
        chunk.chapterId = cursor.getLong(1);
        chunk.index = cursor.getInt(2);
        chunk.byteStart = cursor.getLong(3);
        chunk.byteEnd = cursor.getLong(4);
        chunk.charStart = cursor.getInt(5);
        chunk.charCount = cursor.getInt(6);
        return chunk;
    }
}

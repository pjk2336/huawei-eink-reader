package com.inkreader;

import java.util.ArrayList;
import java.util.List;

public final class ImportData {
    public String title;
    public String author = "佚名";
    public String sourceName;
    public String sha256;
    public long byteLength;
    public final List<ChapterDraft> chapters = new ArrayList<>();

    public static final class ChapterDraft {
        public String title;
        public long byteStart;
        public long byteEnd;
        public int charCount;
        public final List<ChunkDraft> chunks = new ArrayList<>();
    }

    public static final class ChunkDraft {
        public long byteStart;
        public long byteEnd;
        public int charStart;
        public int charCount;
    }
}

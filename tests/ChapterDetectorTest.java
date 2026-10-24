package com.inkreader;

public final class ChapterDetectorTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check("第一章 初见".equals(ChapterDetector.chapterTitle("第一章 初见")), "chapter");
        check("第12回：归来".equals(ChapterDetector.chapterTitle("第12回：归来")), "arabic chapter");
        check("第一卷 风起".equals(ChapterDetector.chapterTitle("第一卷 风起")), "volume");
        check("卷三".equals(ChapterDetector.chapterTitle("卷三")), "volume short");
        check("序章".equals(ChapterDetector.chapterTitle("序章")), "prologue");
        check("番外二".equals(ChapterDetector.chapterTitle("番外二")), "extra");
        check(ChapterDetector.chapterTitle("第一章讲述了我们第一次相遇。") == null, "body false positive");
        check(ChapterDetector.chapterTitle("他翻开第一章，继续阅读。") == null, "inline false positive");
        check(ChapterDetector.chapterTitle("第一章结束") == null, "chapter end banner");
        check("测试作者".equals(ChapterDetector.author("作者：测试作者")), "author");
        check("书名".equals(ChapterDetector.titleFromFilename("C:\\books\\书名.txt")), "filename");
        System.out.println("ChapterDetectorTest: PASS");
    }
}

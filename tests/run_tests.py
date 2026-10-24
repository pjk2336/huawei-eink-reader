#!/usr/bin/env python3
"""Offline protocol, image, and chapter-parser tests."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JDK = Path(os.environ.get("JAVA_HOME", r"D:\Android_studio\jbr"))
BUILD = ROOT / "build"
BUILD.mkdir(exist_ok=True)
OUT = Path(tempfile.mkdtemp(prefix="tests-", dir=BUILD))
ENV = dict(os.environ, JAVA_HOME=str(JDK))
ENV["PATH"] = str(JDK / "bin") + os.pathsep + ENV.get("PATH", "")

protocol_sources = [
    ROOT / "src/org/nfcresearch/qpolllab/PanelProtocol.java",
    ROOT / "src/org/nfcresearch/qpolllab/PanelType.java",
    ROOT / "src/org/nfcresearch/qpolllab/ImageRaster.java",
    ROOT / "src/org/nfcresearch/qpolllab/ImageBlocks.java",
    ROOT / "tests/ProtocolTest.java",
    ROOT / "tests/PanelTypeTest.java",
    ROOT / "tests/ImageRasterTest.java",
    ROOT / "tests/ImageBlocksTest.java",
    ROOT / "tests/PreloadTest.java",
    ROOT / "tests/LzoCompressionTest.java",
    ROOT / "tests/PacketCountProbe.java",
]
subprocess.run([str(JDK / "bin/javac"), "-encoding", "UTF-8", "--release", "8",
                "-d", str(OUT), *map(str, protocol_sources)], env=ENV, check=True)
for test in ("ProtocolTest", "PanelTypeTest", "ImageRasterTest",
             "ImageBlocksTest", "PreloadTest", "LzoCompressionTest"):
    args = [str(JDK / "bin/java"), "-cp", str(OUT),
            "org.nfcresearch.qpolllab." + test]
    if test in ("ProtocolTest", "PanelTypeTest", "ImageRasterTest", "LzoCompressionTest"):
        args.append(str(OUT))
    subprocess.run(args, env=ENV, check=True)

chapter_out = OUT / "chapters"
chapter_out.mkdir()
subprocess.run([str(JDK / "bin/javac"), "-encoding", "UTF-8", "--release", "8",
                "-d", str(chapter_out),
                str(ROOT / "src/com/inkreader/ChapterDetector.java"),
                str(ROOT / "tests/ChapterDetectorTest.java")], env=ENV, check=True)
subprocess.run([str(JDK / "bin/java"), "-cp", str(chapter_out),
                "com.inkreader.ChapterDetectorTest"], env=ENV, check=True)
print("ALL_OFFLINE_TESTS_PASS", OUT)

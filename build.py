#!/usr/bin/env python3
"""Offline APK build using the installed Android SDK. No Gradle or network."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent


def first_existing(paths):
    for value in paths:
        if value and Path(value).exists():
            return Path(value)
    raise RuntimeError("No matching tool found: " + ", ".join(str(p) for p in paths))


SDK = first_existing([
    os.environ.get("ANDROID_HOME"),
    Path.home() / "AppData/Local/Android/Sdk",
    r"C:\Android\Sdk",
])
JDK = first_existing([
    os.environ.get("JAVA_HOME"),
    r"D:\Android_studio\jbr",
    Path.home() / ".jdks",
])
BUILD_TOOLS = sorted((SDK / "build-tools").glob("34.*"))[-1]
ANDROID_JAR = SDK / "platforms/android-34/android.jar"
if not ANDROID_JAR.exists():
    raise RuntimeError("Android 34 platform is required")

BUILD = ROOT / "build"
DIST = ROOT / "dist"
BUILD.mkdir(parents=True, exist_ok=True)
WORK = Path(tempfile.mkdtemp(prefix="inkreader-", dir=BUILD))
for folder in (WORK / "classes", WORK / "dex", WORK / "jars", DIST):
    folder.mkdir(parents=True, exist_ok=True)

ENV = dict(os.environ)
ENV["JAVA_HOME"] = str(JDK)
ENV["PATH"] = str(JDK / "bin") + os.pathsep + ENV.get("PATH", "")


def run(*args):
    subprocess.run([str(value) for value in args], cwd=ROOT, env=ENV, check=True)


libraries = []
for aar in sorted((ROOT / "libs").glob("*-13.1.5.aar")):
    jar = WORK / "jars" / (aar.stem + ".jar")
    with zipfile.ZipFile(aar) as archive:
        jar.write_bytes(archive.read("classes.jar"))
    libraries.append(jar)
if len(libraries) != 4:
    raise RuntimeError("Expected pinned Shizuku API AARs")

sources = sorted((ROOT / "src").rglob("*.java"))
classpath = os.pathsep.join(str(path) for path in [ANDROID_JAR, *libraries])
run(JDK / "bin/javac", "-encoding", "UTF-8", "--release", "8", "-Xlint:-options",
    "-classpath", classpath, "-d", WORK / "classes", *sources)
run(JDK / "bin/jar", "cf", WORK / "classes.jar", "-C", WORK / "classes", ".")
run(BUILD_TOOLS / "d8.bat", "--min-api", "26", "--lib", ANDROID_JAR,
    "--output", WORK / "dex", WORK / "classes.jar", *libraries)
run(BUILD_TOOLS / "aapt.exe", "package", "-f", "-M", ROOT / "AndroidManifest.xml",
    "-S", ROOT / "res", "-A", ROOT / "assets", "-I", ANDROID_JAR,
    "-F", WORK / "unsigned.apk")
with zipfile.ZipFile(WORK / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as archive:
    archive.write(WORK / "dex/classes.dex", "classes.dex")
run(BUILD_TOOLS / "zipalign.exe", "-f", "4", WORK / "unsigned.apk",
    WORK / "aligned.apk")

key = BUILD / "debug.keystore"
if not key.exists():
    run(JDK / "bin/keytool", "-genkeypair", "-keystore", key,
        "-storepass", "android", "-keypass", "android", "-alias", "inkreader",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-dname", "CN=Ink Reader Debug", "-noprompt")
version = ET.parse(ROOT / "AndroidManifest.xml").getroot().attrib[
    "{http://schemas.android.com/apk/res/android}versionName"]
apk = DIST / ("InkReader-" + version + ".apk")
run(BUILD_TOOLS / "apksigner.bat", "sign", "--ks", key, "--ks-key-alias", "inkreader",
    "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", apk,
    WORK / "aligned.apk")
run(BUILD_TOOLS / "apksigner.bat", "verify", "--verbose", apk)
ascii_apk = Path(tempfile.gettempdir()) / "inkreader-badging.apk"
shutil.copy2(apk, ascii_apk)
run(BUILD_TOOLS / "aapt.exe", "dump", "badging", ascii_apk)
ascii_apk.unlink(missing_ok=True)
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
(DIST / (apk.name + ".sha256")).write_text(
    digest + "  " + apk.name + "\n", encoding="utf-8")
print("APK:", apk)
print("Bytes:", apk.stat().st_size)
print("SHA256:", digest)

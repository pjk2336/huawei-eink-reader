# Shizuku setup

The reader itself works without Shizuku. It is required only for sending pages
to the Huawei NFC e-ink case.

## Install

Install Shizuku from its official site:

<https://shizuku.rikka.app/>

Start Shizuku using either root or ADB. On Android 11 and newer, ADB wireless
debugging pairing is usually the simplest option. The service must be restarted
after rebooting Android.

## Windows ADB helper

The repository includes a Windows helper for USB and wireless debugging:

```powershell
.\tools\Activate-Shizuku.ps1 -Device <adb-device-serial>
```

If a matching `shizuku-*-release.apk` is placed in `tools/`, the script can
install it. Otherwise it uses an already installed Shizuku package. It locates
the current native starter path and executes that path through `adb shell`.

For Shizuku v13.6.x, the command shown by the app has this shape:

```text
adb shell /data/app/.../moe.shizuku.privileged.api-.../lib/arm64/libshizuku.so
```

Do not use an old path after updating or reinstalling Shizuku; extract the
current command from Shizuku itself.

## Open InkReader

Open InkReader, start a book, and grant Shizuku permission when prompted.
Then select panel type A0 or A1 in the app settings and connect the e-ink case.

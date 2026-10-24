package com.inkreader;

import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Shizuku user-service bridge. It accepts only image files and fixed panel
 * operations from the owning app, then keeps one privileged worker process alive.
 */
public final class PanelShizukuService extends Binder {
    private static final String PACKAGE = "com.inkreader.reader";
    private static final String TOKEN = "com.inkreader.reader.PanelSession";
    private final Object lock = new Object();
    private final StringBuilder logs = new StringBuilder();
    private final int appUid;
    private final String apk;
    private volatile boolean active;
    private volatile Process child;
    private volatile BufferedWriter commands;
    private volatile File workDir;
    private volatile File stopFile;
    private int sequence;

    public PanelShizukuService() throws Exception {
        int uid = android.os.Process.myUid();
        if (uid != 0 && uid != 2000) throw new SecurityException("需要 Shizuku 或 Root");
        Object pm = Class.forName("android.app.ActivityThread")
                .getMethod("getPackageManager").invoke(null);
        ApplicationInfo info = (ApplicationInfo) Class.forName(
                        "android.content.pm.IPackageManager")
                .getMethod("getApplicationInfo", String.class, long.class, int.class)
                .invoke(pm, PACKAGE, 0L, 0);
        if (info == null) throw new IllegalStateException("应用包不存在");
        appUid = info.uid;
        apk = info.sourceDir;
    }

    private void log(String line) {
        synchronized (lock) {
            logs.append(line).append('\n');
            if (logs.length() > 80000) {
                int cut = logs.indexOf("\n", logs.length() - 60000);
                if (cut >= 0) logs.delete(0, cut + 1);
            }
        }
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == 16777115) {
            stop();
            return true;
        }
        if (code < 1 || code > 4) return super.onTransact(code, data, reply, flags);
        int callerUid = Binder.getCallingUid();
        // Shizuku may proxy user-service calls through its shell/root process.
        // The service is only published to this app by Shizuku's authorization.
        if (callerUid != appUid && callerUid != 0 && callerUid != 2000) {
            throw new SecurityException("调用方不匹配（caller=" + callerUid
                    + ", app=" + appUid + "）");
        }
        data.enforceInterface(TOKEN);
        if (code == 1) {
            start();
            reply.writeNoException();
            return true;
        }
        if (code == 2) {
            synchronized (lock) {
                reply.writeNoException();
                reply.writeString(logs.toString());
                logs.setLength(0);
                reply.writeInt(active ? 1 : 0);
            }
            return true;
        }
        if (code == 3) {
            int operation = data.readInt();
            String id = data.readString();
            byte[] image = data.createByteArray();
            String panelType = data.readString();
            int powerMs = data.readInt();
            int pollMs = data.readInt();
            submit(operation, id, image, panelType, powerMs, pollMs);
            reply.writeNoException();
            return true;
        }
        stop();
        reply.writeNoException();
        return true;
    }

    private void start() {
        synchronized (lock) {
            if (active) throw new IllegalStateException("水墨屏会话已启动");
            logs.setLength(0);
            active = true;
            try {
                workDir = new File("/data/local/tmp/inkreader-" + android.os.Process.myPid()
                        + "-" + System.nanoTime());
                if (!workDir.mkdir()) throw new IllegalStateException("无法创建受保护临时目录");
                if (!new ProcessBuilder("chmod", "700", workDir.getAbsolutePath())
                        .start().waitFor(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("保护临时目录超时");
                }
                stopFile = new File(workDir, "stop");
                ProcessBuilder builder = new ProcessBuilder(
                        "/system/bin/app_process", "/system/bin",
                        "org.nfcresearch.qpolllab.ReaderPanelWorker",
                        "45", stopFile.getAbsolutePath(), workDir.getAbsolutePath())
                        .redirectErrorStream(true);
                builder.environment().put("CLASSPATH", apk);
                child = builder.start();
                commands = new BufferedWriter(new OutputStreamWriter(
                        child.getOutputStream(), StandardCharsets.UTF_8));
                final Process running = child;
                Thread reader = new Thread(() -> {
                    try (BufferedReader input = new BufferedReader(
                            new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = input.readLine()) != null) log(line);
                    } catch (Exception error) {
                        log("QP|ERROR|读取工作进程：" + error);
                    } finally {
                        try {
                            int exit = running.waitFor(10, TimeUnit.SECONDS) ? running.exitValue() : -1;
                            log("QP|BRIDGE_END|工作进程退出码=" + exit);
                        } catch (Exception ignored) {
                        }
                        synchronized (lock) {
                            active = false;
                        }
                        cleanup();
                    }
                }, "inkreader-panel-reader");
                reader.setDaemon(true);
                reader.start();
            } catch (Exception error) {
                active = false;
                cleanup();
                throw new IllegalStateException("无法启动面板进程：" + error);
            }
        }
    }

    private void submit(int operation, String id, byte[] image, String panelType,
                        int powerMs, int pollMs) {
        if (operation != 1 && operation != 2) throw new IllegalArgumentException("操作类型错误");
        if (id == null || id.isEmpty() || id.length() > 48) throw new IllegalArgumentException("命令编号错误");
        if (image == null || image.length != 105984) throw new IllegalArgumentException("图像长度错误");
        if (!"A0".equals(panelType) && !"A1".equals(panelType)) {
            throw new IllegalArgumentException("屏幕型号错误");
        }
        if (powerMs < 100 || powerMs > 12700 || powerMs % 100 != 0
                || pollMs < 0 || pollMs > 500) {
            throw new IllegalArgumentException("刷新参数错误");
        }
        synchronized (lock) {
            if (!active || commands == null || workDir == null) {
                throw new IllegalStateException("面板工作进程尚未启动");
            }
            try {
                File imageFile = new File(workDir, "frame-" + (++sequence) + ".bin");
                try (FileOutputStream output = new FileOutputStream(imageFile)) {
                    output.write(image);
                }
                String sha = hex(MessageDigest.getInstance("SHA-256").digest(image));
                commands.write((operation == 1 ? "PRELOAD" : "ADVANCE")
                        + "\t" + id + "\t" + imageFile.getAbsolutePath()
                        + "\t" + sha + "\t" + panelType
                        + "\t" + powerMs + "\t" + pollMs + "\n");
                commands.flush();
            } catch (Exception error) {
                throw new IllegalStateException("发送面板命令失败：" + error);
            }
        }
    }

    private void stop() {
        final Process process;
        synchronized (lock) {
            if (stopFile != null) {
                try {
                    stopFile.createNewFile();
                } catch (Exception ignored) {
                }
            }
            if (commands != null) {
                try {
                    commands.write("STOP\n");
                    commands.flush();
                    commands.close();
                } catch (Exception ignored) {
                }
            }
            process = child;
            active = false;
            child = null;
            commands = null;
        }
        Thread waiter = new Thread(() -> {
            if (process != null) {
                try {
                    if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroy();
                    if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                } catch (Exception ignored) {
                }
            }
            cleanup();
        }, "inkreader-panel-stop");
        waiter.setDaemon(true);
        waiter.start();
    }

    private void cleanup() {
        File dir = workDir;
        if (dir != null && dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) file.delete();
            }
            dir.delete();
        }
        workDir = null;
        stopFile = null;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}

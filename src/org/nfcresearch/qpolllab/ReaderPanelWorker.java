package org.nfcresearch.qpolllab;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Long-lived privileged NFC worker. It keeps the ISO-DEP field and authenticated
 * panel session alive so the next frame can be written before the user turns.
 */
public final class ReaderPanelWorker {
    private static Object service;
    private static Class<?> adapterType;
    private static final Binder token = new Binder();
    private static final AtomicBoolean tagSeen = new AtomicBoolean();
    private static volatile Tag detectedTag;
    private static int discoveredCode;
    private static Object callbackProxy;
    private static boolean readerTouched;
    private static boolean qtagTouched;
    private static boolean readerReleased;
    private static boolean qtagDisabled;
    private static IBinder callbackBinder;

    private static synchronized void emit(String kind, String message) {
        String safe = String.valueOf(message).replace('\n', ' ').replace('\r', ' ');
        System.out.println("QP|" + kind + "|" + safe);
        System.out.flush();
    }

    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        try {
            return adapterType.getMethod(name, types).invoke(service, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw error;
        }
    }

    private static void init() throws Exception {
        int uid = android.os.Process.myUid();
        if (uid != 0 && uid != 2000) throw new SecurityException("需要 Shizuku 或 Root 授权");
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "nfc");
        if (binder == null) throw new IllegalStateException("未找到 NFC 服务");
        adapterType = Class.forName("android.nfc.INfcAdapter");
        service = Class.forName("android.nfc.INfcAdapter$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        int state = (Integer) invoke("getState", new Class<?>[0]);
        if (state != 3) throw new IllegalStateException("请先在系统设置中开启 NFC");
    }

    private static int qtag(int action) throws Exception {
        return (Integer) invoke("sendVendorNciMessage",
                new Class<?>[]{int.class, int.class, int.class, byte[].class},
                1, 15, 112, new byte[]{0x31, (byte) action});
    }

    private static void reader(int flags) throws Exception {
        Bundle extras = new Bundle();
        extras.putInt("presence", 5000);
        invoke("setReaderMode", new Class<?>[]{IBinder.class,
                        Class.forName("android.nfc.IAppCallback"), int.class, Bundle.class, String.class},
                token, callback(), flags, extras,
                android.os.Process.myUid() == 2000 ? "com.android.shell" : "android");
    }

    private static Object callback() throws Exception {
        if (callbackProxy != null) return callbackProxy;
        Class<?> type = Class.forName("android.nfc.IAppCallback");
        Class<?> stub = Class.forName("android.nfc.IAppCallback$Stub");
        Field field = stub.getDeclaredField("TRANSACTION_onTagDiscovered");
        field.setAccessible(true);
        discoveredCode = field.getInt(null);
        callbackProxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> {
                    if ("asBinder".equals(method.getName())) return callbackBinder;
                    if ("toString".equals(method.getName())) return "InkReaderCallback";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(object);
                    if ("equals".equals(method.getName())) return object == args[0];
                    return null;
                });
        callbackBinder = new android.os.Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code == IBinder.INTERFACE_TRANSACTION) {
                    if (reply != null) reply.writeString("android.nfc.IAppCallback");
                    return true;
                }
                if (code != discoveredCode) return super.onTransact(code, data, reply, flags);
                data.enforceInterface("android.nfc.IAppCallback");
                Tag tag = data.readInt() == 0 ? null : Tag.CREATOR.createFromParcel(data);
                if (tag != null && tagSeen.compareAndSet(false, true)) {
                    detectedTag = tag;
                    emit("TAG", "收到墨水屏标签 UID=" + hex(tag.getId()));
                }
                if (reply != null) reply.writeNoException();
                return true;
            }
        };
        return callbackProxy;
    }

    private static void cleanup() {
        if (readerTouched && callbackBinder != null) {
            try {
                reader(0);
                readerReleased = true;
                emit("READER_OFF", "ReaderMode 已释放");
            } catch (Exception error) {
                emit("CLEANUP_ERROR", "释放 ReaderMode：" + error);
            }
        }
        if (qtagTouched) {
            try {
                int status = qtag(0);
                qtagDisabled = status == 0;
                emit(qtagDisabled ? "QTAG_OFF" : "CLEANUP_ERROR",
                        "关闭 QTag，API 状态=" + status);
            } catch (Exception error) {
                emit("CLEANUP_ERROR", "关闭 QTag：" + error);
            }
        }
    }

    public static void main(String[] args) {
        int exit = 0;
        try {
            if (args.length != 3) throw new IllegalArgumentException("参数数量错误");
            int scanSeconds = Integer.parseInt(args[0]);
            File stop = new File(args[1]).getCanonicalFile();
            File root = new File(args[2]).getCanonicalFile();
            if (!root.isDirectory()) throw new IllegalArgumentException("临时目录不存在");
            init();
            qtagTouched = true;
            int status = qtag(1);
            emit("QTAG_ON", "启用 QTag，API 状态=" + status);
            if (status != 0) throw new IllegalStateException("QTag 启用失败");
            readerTouched = true;
            reader(0x81);
            emit("START", "reader:" + scanSeconds);
            long deadline = SystemClock.elapsedRealtime() + scanSeconds * 1000L;
            while (!tagSeen.get() && !stop.exists()
                    && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(30);
            }
            if (stop.exists()) throw new InterruptedException("用户停止");
            if (!tagSeen.get()) throw new IllegalStateException(
                    "未发现墨水屏标签；保持手机壳贴合并核对 Shizuku 授权");

            Tag tag = detectedTag;
            IsoDep iso = IsoDep.get(tag);
            if (iso == null) throw new IllegalArgumentException("目标未提供 ISO-DEP");
            emit("STAGE", "connecting|正在建立持久 ISO-DEP 连接");
            iso.connect();
            if (iso.getMaxTransceiveLength() < 257) {
                throw new IllegalArgumentException("平台单次 APDU 长度不足 257 字节");
            }
            PanelProtocol.Control control = new PanelProtocol.Control() {
                @Override public long now() { return SystemClock.elapsedRealtime(); }
                @Override public void sleep(long ms) throws Exception { Thread.sleep(ms); }
                @Override public void check() throws Exception {
                    if (stop.exists()) throw new InterruptedException("会话已停止");
                }
                @Override public void event(String kind, String message) {
                    emit(kind, message);
                }
            };
            PanelProtocol protocol = new PanelProtocol((command, timeout) -> {
                control.check();
                iso.setTimeout(timeout);
                String ins = String.format(Locale.ROOT, "%02X", command[1] & 255);
                if (command[1] != (byte) 0xD3 && command[1] != (byte) 0xDE) {
                    emit("APDU_BEGIN", "INS=" + ins + "；超时=" + timeout + " ms");
                }
                return iso.transceive(command);
            }, control);
            PanelProtocol.Info info = protocol.prepare();
            emit("READY", info.description());

            String lastSha = null;
            try (BufferedReader input = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while (!stop.exists() && (line = input.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    String[] parts = line.split("\\t", -1);
                    if ("STOP".equals(parts[0])) break;
                    if (parts.length != 7
                            || (!"PRELOAD".equals(parts[0]) && !"ADVANCE".equals(parts[0]))) {
                        emit("ERROR", "无效命令");
                        break;
                    }
                    String id = parts[1];
                    File imageFile = new File(parts[2]).getCanonicalFile();
                    String expectedSha = parts[3];
                    int powerMs = Integer.parseInt(parts[5]);
                    int pollMs = Integer.parseInt(parts[6]);
                    if (!isInside(root, imageFile)) throw new SecurityException("图像路径越界");
                    byte[] image = readImage(imageFile);
                    String actualSha = hex(MessageDigest.getInstance("SHA-256").digest(image));
                    if (!actualSha.equalsIgnoreCase(expectedSha)) {
                        emit("ERROR", id + "|图像快照校验不符，未写入");
                        break;
                    }
                    long started = SystemClock.elapsedRealtime();
                    if ("PRELOAD".equals(parts[0])) {
                        protocol.writeImage(image);
                        lastSha = actualSha;
                        emit("PRELOADED", id + "|" + actualSha + "|"
                                + (SystemClock.elapsedRealtime() - started));
                    } else {
                        boolean fast = actualSha.equals(lastSha);
                        if (!fast) protocol.writeImage(image);
                        int refreshPowerMs = fast ? Math.min(powerMs, 500) : powerMs;
                        int refreshPollMs = fast ? Math.min(pollMs, 50) : pollMs;
                        emit("FAST_TURN", fast
                                ? "预传命中，使用 " + refreshPowerMs + " ms 快速 D4"
                                : "预传未命中，完整传输后使用 " + refreshPowerMs + " ms D4");
                        protocol.refresh(refreshPowerMs, refreshPollMs);
                        lastSha = null;
                        emit("ADVANCED", id + "|" + actualSha + "|" + (fast ? "1" : "0")
                                + "|" + (SystemClock.elapsedRealtime() - started));
                    }
                    imageFile.delete();
                }
            }
        } catch (InterruptedException error) {
            emit("CANCELLED", error.getMessage());
            exit = 3;
        } catch (Throwable error) {
            emit("ERROR", error.getClass().getSimpleName() + ": " + error.getMessage());
            exit = 1;
        } finally {
            cleanup();
            boolean restored = (!readerTouched || readerReleased)
                    && (!qtagTouched || qtagDisabled);
            emit(restored ? "RESTORED" : "RESTORE_FAILED",
                    restored ? "已恢复普通 NFC 寻卡" : "NFC 恢复未确认，请重启 NFC 开关");
            emit("END", "面板会话结束");
        }
        System.exit(exit);
    }

    private static boolean isInside(File root, File file) throws Exception {
        String parent = root.getCanonicalPath() + File.separator;
        return file.getCanonicalPath().startsWith(parent);
    }

    private static byte[] readImage(File file) throws Exception {
        if (file.length() != PanelProtocol.IMAGE_BYTES) {
            throw new IllegalArgumentException("图像长度必须为 105984 字节");
        }
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int n = input.read(data, offset, data.length - offset);
                if (n < 0) break;
                offset += n;
            }
            if (offset != data.length) throw new IllegalArgumentException("图像文件不完整");
        }
        return data;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}

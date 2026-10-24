package com.inkreader;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import rikka.shizuku.Shizuku;

/** App-side handle for the persistent D3/D4 Shizuku session. */
public final class PanelClient {
    public interface Listener {
        void onPanelStatus(String message);
        void onPanelReady();
        void onPreloadDone(String id);
        void onAdvanced(String id, boolean fast, long millis);
        void onPanelFailure(String message);
    }

    private static final int PERMISSION_CODE = 611;
    private static final String TOKEN = "com.inkreader.reader.PanelSession";
    private final Activity activity;
    private final Listener listener;
    private final String panelType;
    private final int powerMs;
    private final int pollMs;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong ids = new AtomicLong();
    private volatile IBinder binder;
    private volatile boolean bound;
    private volatile boolean serviceReady;
    private Thread pollThread;

    private final Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
            new ComponentName("com.inkreader.reader", "com.inkreader.PanelShizukuService"))
            .tag("inkreader-panel")
            .version(21)
            .daemon(false)
            .processNameSuffix("eink_panel_v21");

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            if (closed.get()) return;
            binder = service;
            bound = true;
            status("已连接传输服务，正在寻找手机壳墨水屏");
            try {
                transact(1);
                startPolling();
            } catch (Exception error) {
                failure("启动面板会话失败：" + error.getMessage());
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            binder = null;
            bound = false;
            serviceReady = false;
            if (!closed.get()) failure("Shizuku 传输服务已断开");
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != PERMISSION_CODE || closed.get()) return;
                if (grantResult == PackageManager.PERMISSION_GRANTED) bind();
                else failure("未授予 Shizuku 权限");
            };

    public PanelClient(Activity activity, Listener listener, String panelType,
                       int powerMs, int pollMs) {
        this.activity = activity;
        this.listener = listener;
        this.panelType = panelType;
        this.powerMs = powerMs;
        this.pollMs = pollMs;
    }

    public void start() {
        if (closed.get() || !started.compareAndSet(false, true)) return;
        Shizuku.addRequestPermissionResultListener(permissionListener);
        try {
            if (!Shizuku.pingBinder()) {
                failure("Shizuku 未运行；小说仍可正常离线阅读");
                return;
            }
            if (Shizuku.isPreV11()) {
                failure("Shizuku 版本过旧，请升级");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                status("等待 Shizuku 授权");
                Shizuku.requestPermission(PERMISSION_CODE);
                return;
            }
            bind();
        } catch (Throwable error) {
            failure("Shizuku 不可用：" + error.getMessage());
        }
    }

    private void bind() {
        if (closed.get() || bound) return;
        bound = true;
        try {
            status("正在连接 Shizuku 传输服务");
            Shizuku.bindUserService(args, connection);
        } catch (Throwable error) {
            bound = false;
            failure("绑定 Shizuku 服务失败：" + error.getMessage());
        }
    }

    public boolean isReady() {
        return serviceReady && !closed.get();
    }

    public String submitAdvance(byte[] packed) {
        return submit(2, packed);
    }

    public String submitPreload(byte[] packed) {
        return submit(1, packed);
    }

    private String submit(int operation, byte[] packed) {
        IBinder service = binder;
        if (service == null || !serviceReady || closed.get()) return null;
        String id = Long.toString(ids.incrementAndGet());
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(TOKEN);
            request.writeInt(operation);
            request.writeString(id);
            request.writeByteArray(packed);
            request.writeString(panelType);
            request.writeInt(powerMs);
            request.writeInt(pollMs);
            if (!service.transact(3, request, reply, 0)) {
                failure("传输服务不兼容");
                return null;
            }
            reply.readException();
            return id;
        } catch (Exception error) {
            failure("提交页面失败：" + error.getMessage());
            return null;
        } finally {
            request.recycle();
            reply.recycle();
        }
    }

    private void startPolling() {
        if (pollThread != null) return;
        pollThread = new Thread(() -> {
            boolean lastActive = true;
            while (!closed.get() && lastActive) {
                IBinder service = binder;
                if (service == null) break;
                Parcel request = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    request.writeInterfaceToken(TOKEN);
                    if (!service.transact(2, request, reply, 0)) break;
                    reply.readException();
                    String lines = reply.readString();
                    lastActive = reply.readInt() != 0;
                    dispatch(lines);
                } catch (Exception error) {
                    if (!closed.get()) failure("读取传输状态失败：" + error.getMessage());
                    break;
                } finally {
                    request.recycle();
                    reply.recycle();
                }
                SystemClock.sleep(70);
            }
            if (!closed.get()) {
                serviceReady = false;
                if (!lastActive) failure("水墨屏传输已结束，请重新开始");
            }
        }, "inkreader-panel-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void dispatch(String lines) {
        if (lines == null) return;
        for (String line : lines.split("\\n")) {
            if (!line.startsWith("QP|")) continue;
            String[] parts = line.split("\\|", 3);
            if (parts.length != 3) continue;
            String kind = parts[1];
            String value = parts[2];
            switch (kind) {
                case "READY":
                    serviceReady = true;
                    status("墨水屏已连接，首屏已进入传输队列");
                    onMain(listener::onPanelReady);
                    break;
                case "PRELOADED": {
                    String id = before(value, '|');
                    onMain(() -> listener.onPreloadDone(id));
                    break;
                }
                case "ADVANCED": {
                    String[] values = value.split("\\|", -1);
                    String id = values.length > 0 ? values[0] : "";
                    boolean fast = values.length > 2 && "1".equals(values[2]);
                    long millis = 0;
                    try {
                        if (values.length > 3) millis = Long.parseLong(values[3]);
                    } catch (NumberFormatException ignored) {
                    }
                    final long elapsed = millis;
                    onMain(() -> listener.onAdvanced(id, fast, elapsed));
                    break;
                }
                case "PROGRESS": {
                    String[] values = value.split("\\|", 3);
                    if (values.length >= 3) {
                        try {
                            int done = Integer.parseInt(values[0]);
                            int total = Integer.parseInt(values[1]);
                            int percent = total <= 0 ? 0 : done * 100 / total;
                            status("后台预传 " + percent + "% · " + values[2]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    break;
                }
                case "STAGE": {
                    int split = value.indexOf('|');
                    status(split >= 0 ? value.substring(split + 1) : value);
                    break;
                }
                case "FAST_TURN":
                    status(value);
                    break;
                case "ERROR":
                case "RESTORE_FAILED":
                    failure(value);
                    break;
                case "CANCELLED":
                    serviceReady = false;
                    status("水墨屏会话已停止");
                    break;
                default:
                    break;
            }
        }
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        IBinder service = binder;
        if (service != null) {
            Parcel request = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                request.writeInterfaceToken(TOKEN);
                service.transact(4, request, reply, 0);
                reply.readException();
            } catch (Exception ignored) {
            } finally {
                request.recycle();
                reply.recycle();
            }
        }
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener);
            if (bound) Shizuku.unbindUserService(args, connection, false);
        } catch (Throwable ignored) {
        }
        binder = null;
        bound = false;
        serviceReady = false;
    }

    private void transact(int code) throws Exception {
        IBinder service = binder;
        if (service == null) throw new IllegalStateException("服务未连接");
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(TOKEN);
            if (!service.transact(code, request, reply, 0)) {
                throw new IllegalStateException("传输服务不兼容");
            }
            reply.readException();
        } finally {
            request.recycle();
            reply.recycle();
        }
    }

    private void status(String message) {
        onMain(() -> listener.onPanelStatus(message));
    }

    private void failure(String message) {
        serviceReady = false;
        onMain(() -> listener.onPanelFailure(message));
    }

    private void onMain(Runnable action) {
        if (closed.get()) return;
        activity.runOnUiThread(() -> {
            if (!closed.get()) action.run();
        });
    }

    private static String before(String value, char delimiter) {
        int index = value.indexOf(delimiter);
        return index < 0 ? value : value.substring(0, index);
    }
}

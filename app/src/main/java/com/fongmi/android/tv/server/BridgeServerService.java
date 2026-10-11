package com.fongmi.android.tv.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.BridgeActivity;
import com.fongmi.android.tv.R;
import com.github.catvod.Proxy;

/** Keeps the LAN bridge responsive after the user switches to OKbox. */
public final class BridgeServerService extends Service {

    private static final String CHANNEL = "tvbox_spider_bridge";
    private static final int NOTIFICATION_ID = 9_978;
    private static final String DISCOVERY_TYPE = "_okboxbridge._tcp.";
    private NsdManager.RegistrationListener discoveryListener;

    public static void start(Context context) {
        Intent intent = new Intent(context, BridgeServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForegroundService();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Server.get().start();
        registerDiscovery();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        unregisterDiscovery();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerDiscovery() {
        if (discoveryListener != null || Proxy.getPort() <= 0) return;
        NsdManager manager = getSystemService(NsdManager.class);
        if (manager == null) return;
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("OKBOX-Bridge");
        info.setServiceType(DISCOVERY_TYPE);
        info.setPort(Proxy.getPort());
        discoveryListener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo serviceInfo) { }
            @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) { discoveryListener = null; }
            @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) { }
        };
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Throwable error) {
            discoveryListener = null;
        }
    }

    private void unregisterDiscovery() {
        NsdManager manager = getSystemService(NsdManager.class);
        NsdManager.RegistrationListener listener = discoveryListener;
        discoveryListener = null;
        if (manager == null || listener == null) return;
        try { manager.unregisterService(listener); } catch (Throwable ignored) { }
    }

    private void startForegroundService() {
        Notification notification = notification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "TVBox Spider Bridge", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持局域网 Bridge 服务可用");
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification() {
        Intent launch = new Intent(this, BridgeActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getActivity(this, 0, launch, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("TVBox Spider Bridge 正在运行")
                .setContentText("局域网服务器保持可用")
                .setContentIntent(pending)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }
}

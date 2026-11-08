package com.sirvienta.android;

import android.app.Notification;
import android.content.Context;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class NotificationAccessService extends NotificationListenerService {
    private static volatile NotificationAccessService instance;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    static boolean hasNotificationAccess(Context context) {
        String enabledListeners = Settings.Secure.getString(
                context.getContentResolver(),
                "enabled_notification_listeners");
        return enabledListeners != null && enabledListeners.contains(context.getPackageName());
    }

    static DeviceActionHandler.Result readActiveNotifications(String requestedApp) {
        NotificationAccessService service = instance;
        if (service == null) {
            return new DeviceActionHandler.Result(
                    false,
                    "Android aún no ha conectado el acceso a notificaciones. Abre Ajustes > Acceso a notificaciones y vuelve a intentarlo.");
        }

        String filter = requestedApp == null ? "" : requestedApp.trim().toLowerCase(Locale.ROOT);
        List<String> messages = new ArrayList<>();
        StatusBarNotification[] notifications;
        try {
            notifications = service.getActiveNotifications();
        } catch (SecurityException error) {
            return new DeviceActionHandler.Result(
                    false,
                    "Android no permite leer las notificaciones. Revisa el permiso de Sirvienta en Ajustes.");
        }

        for (StatusBarNotification item : notifications) {
            if (item == null || item.getPackageName().equals(service.getPackageName())) {
                continue;
            }
            Notification notification = item.getNotification();
            if (notification == null || notification.extras == null) {
                continue;
            }
            CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (TextUtils.isEmpty(text)) {
                text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
            }
            String packageName = item.getPackageName().toLowerCase(Locale.ROOT);
            String appLabel = packageName;
            try {
                appLabel = service.getPackageManager()
                        .getApplicationLabel(service.getPackageManager().getApplicationInfo(
                                item.getPackageName(),
                                0))
                        .toString();
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                // Use the package name when the posting app is no longer installed.
            }
            if (!filter.isEmpty()
                    && !appLabel.toLowerCase(Locale.ROOT).contains(filter)
                    && !packageName.contains(filter)) {
                continue;
            }
            if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) {
                continue;
            }
            String summary = (TextUtils.isEmpty(title) ? "" : title + ": ")
                    + (TextUtils.isEmpty(text) ? "" : text);
            if (summary.length() > 240) {
                summary = summary.substring(0, 237) + "...";
            }
            messages.add(appLabel + " — " + summary);
            if (messages.size() == 6) {
                break;
            }
        }

        if (messages.isEmpty()) {
            return new DeviceActionHandler.Result(
                    true,
                    filter.isEmpty()
                            ? "No veo notificaciones activas en este momento."
                            : "No veo notificaciones activas de " + requestedApp + ".");
        }
        return new DeviceActionHandler.Result(true, TextUtils.join("\n", messages));
    }
}

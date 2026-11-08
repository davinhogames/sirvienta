package com.sirvienta.android;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONException;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

public class WakeWordService extends Service {
    public static final String EXTRA_BEGIN_COMMAND = "begin_command";
    public static final String EXTRA_APP_FOREGROUND = "app_foreground";
    public static final String PREF_LISTENING_ACTIVE = "listening_active";
    public static final String PREF_ASSISTANT_ENGAGED = "assistant_engaged";
    public static final String PREF_APP_FOREGROUND = "app_foreground";
    public static final String ACTION_STOP = "com.sirvienta.android.STOP_LISTENING";
    public static final String ACTION_UPDATE_APP_VISIBILITY = "com.sirvienta.android.UPDATE_APP_VISIBILITY";
    public static final String ACTION_BEGIN_COMMAND = "com.sirvienta.android.BEGIN_COMMAND";

    private static final String TAG = "SirvientaWakeService";
    private static final String CHANNEL_ID = "sirvienta_voice";
    private static final int NOTIFICATION_ID = 5001;
    private static final String PREFS = "sirvienta";
    private static final String PREF_GAME_VISITS = "game_visits:";
    private static final String PREF_LAST_GAME_NOTICE_AT = "last_game_notice_at";
    private static final int GAME_VISIT_NOTICE_AT = 7;
    private static final int GAME_NOTICE_CHANCE_PERCENT = 30;
    private static final long GAME_NOTICE_COOLDOWN_MS = 8L * 60L * 60L * 1_000L;
    private static final long GAME_USAGE_POLL_INTERVAL_MS = 5_000L;
    private static final long COMMAND_TIMEOUT_MS = 10_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final OfflineVoiceInput voiceInput = new OfflineVoiceInput();
    private final BundledSpeechOutput speechOutput = BundledSpeechOutput.getInstance();
    private final Runnable commandTimeout = this::handleCommandTimeout;

    private AvatarOverlay avatarOverlay;
    private volatile boolean serviceActive;
    private volatile boolean assistantEngaged;
    private boolean recognitionActive;
    private boolean waitingForSpeechOutput;
    private volatile boolean pendingDirectCommand;
    private long lastGameUsageCheck;
    private String foregroundPackage;
    private String pendingGameNotice;

    private void handleCommandTimeout() {
        if (serviceActive && recognitionActive && !waitingForSpeechOutput) {
            finishRecognition("No he oído ningún comando. Vuelvo a la escucha local.");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        avatarOverlay = new AvatarOverlay(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopListening();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_BEGIN_COMMAND.equals(intent.getAction())) {
            if (serviceActive) {
                beginVoiceCommand();
                return START_STICKY;
            }
            intent.putExtra(EXTRA_BEGIN_COMMAND, true);
        }
        if (intent != null && ACTION_UPDATE_APP_VISIBILITY.equals(intent.getAction())) {
            boolean appForeground = intent.getBooleanExtra(EXTRA_APP_FOREGROUND, false);
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_APP_FOREGROUND, appForeground)
                    .apply();
            if (appForeground) {
                avatarOverlay.hide();
            } else if (serviceActive) {
                updateAvatarVisibility();
            }
            if (!serviceActive) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            return START_STICKY;
        }
        if (serviceActive) {
            updateNotification("La escucha local de Sirvienta ya está activa.");
            return START_STICKY;
        }

        startForegroundNow();
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_LISTENING_ACTIVE, true)
                .apply();
        serviceActive = true;
        assistantEngaged = false;
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_ASSISTANT_ENGAGED, false)
                .apply();
        lastGameUsageCheck = System.currentTimeMillis();
        mainHandler.postDelayed(this::checkGameUsage, GAME_USAGE_POLL_INTERVAL_MS);
        boolean beginWithCommand = intent != null && intent.getBooleanExtra(EXTRA_BEGIN_COMMAND, false);
        initializeVoiceEngine(beginWithCommand);
        return START_STICKY;
    }

    private void initializeVoiceEngine(boolean beginWithCommand) {
        worker.execute(() -> {
            try {
                if (!serviceActive) {
                    return;
                }
                boolean startWithCommand = beginWithCommand || pendingDirectCommand;
                voiceInput.start(getApplicationContext(), new OfflineVoiceInput.Listener() {
                    @Override
                    public void onWakeWord() {
                        mainHandler.post(WakeWordService.this::onWakeWordDetected);
                    }

                    @Override
                    public void onCommand(String text) {
                        mainHandler.post(() -> processVoiceCommand(text));
                    }

                    @Override
                    public void onGoodbye() {
                        mainHandler.post(() -> {
                            setAssistantEngaged(false);
                            mainHandler.removeCallbacks(commandTimeout);
                            updateNotification("Hasta luego. Sirvienta queda en espera.");
                            speak("Hasta luego.");
                        });
                    }

                    @Override
                    public void onError(String message, Throwable error) {
                        mainHandler.post(() -> handleVoiceInputError(message, error));
                    }
                }, startWithCommand);
                mainHandler.post(() -> {
                    if (!serviceActive) {
                        return;
                    }
                    updateNotification(startWithCommand
                            ? "Te escucho. Di tu comando sin conexión."
                            : "Escucha local activa en segundo plano. Di “Sirvienta”.");
                    if (startWithCommand) {
                        beginVoiceCommand();
                    }
                });
            } catch (IOException | RuntimeException error) {
                Log.e(TAG, "No se pudo iniciar el reconocimiento de voz local.", error);
                mainHandler.post(() -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit()
                            .putBoolean(PREF_LISTENING_ACTIVE, false)
                            .apply();
                    updateNotification("No se pudo iniciar el reconocimiento offline: " + error.getMessage());
                    serviceActive = false;
                    stopForeground(true);
                    stopSelf();
                });
            }
        });
    }

    private void onWakeWordDetected() {
        if (!serviceActive || (recognitionActive && !assistantEngaged)) {
            return;
        }
        if (assistantEngaged) {
            setAssistantEngaged(false);
            recognitionActive = true;
            mainHandler.removeCallbacks(commandTimeout);
            voiceInput.pauseForResponse();
            avatarOverlay.hide();
            updateNotification("Sirvienta en espera. Di «Sirvienta» para volver a activarla.");
            speak("Vale. Me quedo en espera. Di Sirvienta cuando quieras llamarme.");
            return;
        }
        setAssistantEngaged(true);
        recognitionActive = true;
        mainHandler.removeCallbacks(commandTimeout);
        mainHandler.postDelayed(commandTimeout, COMMAND_TIMEOUT_MS);
        boolean avatarShown = showAvatarInBackground();
        updateNotification(avatarShown
                ? "Sirvienta activada. Di tu comando."
                : "Sirvienta activada. Di tu comando. Permite «Mostrar sobre otras apps» para ver el avatar.");
    }

    private void beginVoiceCommand() {
        if (!serviceActive) {
            return;
        }
        if (!voiceInput.isActive()) {
            pendingDirectCommand = true;
            return;
        }
        pendingDirectCommand = false;
        setAssistantEngaged(true);
        recognitionActive = true;
        mainHandler.removeCallbacks(commandTimeout);
        mainHandler.postDelayed(commandTimeout, COMMAND_TIMEOUT_MS);
        boolean avatarShown = showAvatarInBackground();
        voiceInput.beginCommand();
        updateNotification(avatarShown
                ? "Te escucho. Toca el avatar para abrir Sirvienta."
                : "Te escucho. Di tu comando. Permite «Mostrar sobre otras apps» para ver el avatar.");
    }

    private void processVoiceCommand(String text) {
        if (!serviceActive || text == null || text.trim().isEmpty()) {
            finishRecognition("No he entendido el comando. Vuelvo a la escucha local.");
            return;
        }
        mainHandler.removeCallbacks(commandTimeout);
        setAssistantEngaged(true);
        recognitionActive = true;
        updateNotification("Procesando localmente: " + text);
        worker.execute(() -> {
            try {
                String prompt = LocalConversationHistory.buildPrompt(this, text);
                LocalConversationHistory.append(this, "user", text);
                DeviceActionHandler.Result localAction = DeviceActionHandler.executeLocalCommand(this, text);
                if (localAction == null) {
                    LocalLlm.load(getApplicationContext());
                }
                String reply = localAction == null ? LocalLlm.generate(prompt, 192) : localAction.message;
                if (localAction == null) {
                    LocalLlm.close();
                }
                LocalConversationHistory.append(this, "assistant", reply);
                mainHandler.post(() -> {
                    if (serviceActive) {
                        updateNotification(reply);
                        speak(reply);
                    }
                });
            } catch (IOException | JSONException | IllegalStateException | IllegalArgumentException error) {
                Log.e(TAG, "Falló la generación local de la respuesta.", error);
                mainHandler.post(() -> {
                    if (serviceActive) {
                        String message = "No he podido procesar el comando en este móvil: " + error.getMessage();
                        updateNotification(message);
                        speak(message);
                    }
                });
            }
        });
    }

    private void handleVoiceInputError(String message, Throwable error) {
        Log.e(TAG, message, error);
        serviceActive = false;
        recognitionActive = false;
        setAssistantEngaged(false);
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_LISTENING_ACTIVE, false)
                .apply();
        updateNotification(message + " " + error.getMessage());
        stopForeground(true);
        stopSelf();
    }

    private void finishRecognition(String message) {
        recognitionActive = false;
        mainHandler.removeCallbacks(commandTimeout);
        if (message != null) {
            updateNotification(message);
        }
        if (!serviceActive || waitingForSpeechOutput) {
            return;
        }
        if (!announcePendingGameNotice()) {
            updateAvatarVisibility();
            resumeWakeWordDetection();
        }
    }

    private void resumeWakeWordDetection() {
        if (!serviceActive) {
            return;
        }
        mainHandler.postDelayed(() -> {
            if (!serviceActive) {
                return;
            }
            recognitionActive = false;
            voiceInput.resumeAfterResponse();
            updateNotification("Escucha local activa en segundo plano. Di “Sirvienta”.");
        }, 500);
    }

    private void speak(String text) {
        waitingForSpeechOutput = true;
        voiceInput.pauseForResponse();
        speechOutput.speak(this, text, new BundledSpeechOutput.Listener() {
            @Override
            public void onComplete() {
                mainHandler.post(WakeWordService.this::finishSpeechOutput);
            }

            @Override
            public void onError(Throwable error) {
                Log.e(TAG, "Falló la reproducción de la voz local.", error);
                mainHandler.post(() -> {
                    updateNotification("No se pudo reproducir la voz local: " + error.getMessage());
                    finishSpeechOutput();
                });
            }
        });
    }

    private void finishSpeechOutput() {
        waitingForSpeechOutput = false;
        recognitionActive = false;
        if (!announcePendingGameNotice()) {
            updateAvatarVisibility();
            resumeWakeWordDetection();
        }
    }

    private boolean showAvatarInBackground() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (preferences.getBoolean(PREF_APP_FOREGROUND, false)) {
            avatarOverlay.hide();
            return false;
        }
        return avatarOverlay.show();
    }

    private void updateAvatarVisibility() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (serviceActive
                && assistantEngaged
                && !preferences.getBoolean(PREF_APP_FOREGROUND, false)) {
            avatarOverlay.show();
        } else {
            avatarOverlay.hide();
        }
    }

    private void setAssistantEngaged(boolean engaged) {
        assistantEngaged = engaged;
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_ASSISTANT_ENGAGED, engaged)
                .apply();
        if (!engaged) {
            avatarOverlay.hide();
        }
    }

    private void checkGameUsage() {
        if (!serviceActive) {
            return;
        }
        long now = System.currentTimeMillis();
        if (hasGameUsageAccess()) {
            UsageStatsManager usageStatsManager =
                    (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            if (usageStatsManager != null) {
                try {
                    UsageEvents events = usageStatsManager.queryEvents(lastGameUsageCheck, now);
                    lastGameUsageCheck = now;
                    UsageEvents.Event event = new UsageEvents.Event();
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event);
                        handleUsageEvent(event);
                    }
                } catch (SecurityException error) {
                    Log.e(TAG, "Android revocó el acceso al uso de aplicaciones.", error);
                    lastGameUsageCheck = now;
                }
            }
        } else {
            lastGameUsageCheck = now;
        }
        mainHandler.postDelayed(this::checkGameUsage, GAME_USAGE_POLL_INTERVAL_MS);
    }

    private boolean hasGameUsageAccess() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        return appOps != null
                && appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(),
                        getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    private void handleUsageEvent(UsageEvents.Event event) {
        int eventType = event.getEventType();
        boolean resumed = eventType == UsageEvents.Event.MOVE_TO_FOREGROUND;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resumed |= eventType == UsageEvents.Event.ACTIVITY_RESUMED;
        }

        String packageName = event.getPackageName();
        if (!resumed || packageName == null || packageName.equals(foregroundPackage)) {
            return;
        }
        foregroundPackage = packageName;
        String gameName = getGameName(packageName);
        if (gameName != null) {
            recordGameVisit(packageName, gameName);
        }
    }

    private String getGameName(String packageName) {
        try {
            ApplicationInfo applicationInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                applicationInfo = getPackageManager().getApplicationInfo(
                        packageName,
                        PackageManager.ApplicationInfoFlags.of(0));
            } else {
                applicationInfo = getPackageManager().getApplicationInfo(packageName, 0);
            }
            if (applicationInfo.category != ApplicationInfo.CATEGORY_GAME) {
                return null;
            }
            CharSequence label = getPackageManager().getApplicationLabel(applicationInfo);
            return label == null || label.length() == 0 ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException error) {
            Log.w(TAG, "No se pudo identificar la aplicación en primer plano: " + packageName);
            return null;
        }
    }

    private void recordGameVisit(String packageName, String gameName) {
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        String preferenceKey = PREF_GAME_VISITS + day + ":" + packageName;
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        int visits = preferences.getInt(preferenceKey, 0) + 1;
        preferences.edit().putInt(preferenceKey, visits).apply();
        if (visits != GAME_VISIT_NOTICE_AT) {
            return;
        }
        long now = System.currentTimeMillis();
        long lastNotice = preferences.getLong(PREF_LAST_GAME_NOTICE_AT, 0L);
        if (now - lastNotice < GAME_NOTICE_COOLDOWN_MS
                || ThreadLocalRandom.current().nextInt(100) >= GAME_NOTICE_CHANCE_PERCENT) {
            return;
        }

        String[] comments = {
                "Séptima visita a " + gameName + " hoy… se nota que te gusta.",
                "Ya van siete entradas a " + gameName + " hoy. ¿Lo añadimos a favoritos?",
                gameName + " te está viendo mucho hoy. ¡Siete visitas ya!"
        };
        pendingGameNotice = comments[ThreadLocalRandom.current().nextInt(comments.length)];
        preferences.edit().putLong(PREF_LAST_GAME_NOTICE_AT, now).apply();
        updateNotification(pendingGameNotice);
        if (!recognitionActive && !waitingForSpeechOutput) {
            announcePendingGameNotice();
        }
    }

    private boolean announcePendingGameNotice() {
        if (pendingGameNotice == null || !serviceActive || recognitionActive || waitingForSpeechOutput) {
            return false;
        }
        String notice = pendingGameNotice;
        pendingGameNotice = null;
        recognitionActive = true;
        updateAvatarVisibility();
        voiceInput.pauseForResponse();
        speak(notice);
        return true;
    }

    private void startForegroundNow() {
        Notification notification = buildNotification("Preparando reconocimiento local.");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Escucha de Sirvienta",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Indica cuándo Sirvienta tiene el micrófono activo.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String message) {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopService = new Intent(this, WakeWordService.class);
        stopService.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopService,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Sirvienta · local")
                .setContentText(message)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_pause, "Detener", stopPendingIntent)
                .build();
    }

    private void updateNotification(String message) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification(message));
    }

    private void stopListening() {
        serviceActive = false;
        recognitionActive = false;
        setAssistantEngaged(false);
        avatarOverlay.hide();
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_LISTENING_ACTIVE, false)
                .apply();
        mainHandler.removeCallbacksAndMessages(null);
        voiceInput.close();
        speechOutput.stop();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        serviceActive = false;
        setAssistantEngaged(false);
        avatarOverlay.hide();
        mainHandler.removeCallbacksAndMessages(null);
        voiceInput.close();
        speechOutput.stop();
        worker.shutdownNow();
        super.onDestroy();
    }
}

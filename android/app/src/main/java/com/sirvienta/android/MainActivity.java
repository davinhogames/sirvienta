package com.sirvienta.android;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 41;
    private static final int ASSISTANT_ROLE_REQUEST = 42;
    private static final int SAVE_TEXT_REQUEST = 43;
    private static final String PREFS = "sirvienta";
    private static final String PREF_LOCAL_HISTORY = "local_history";
    private static final String PREF_LISTENING_ACTIVE = WakeWordService.PREF_LISTENING_ACTIVE;
    private static final String PREF_SPEAK_REPLIES = "speak_replies";
    private static final String PREF_AUTO_LISTENING = "auto_listening";
    private static final String PREF_OVERLAY_PROMPTED = "overlay_prompted";
    private static final int COLOR_TEXT = 0xfff4f5f7;
    private static final int COLOR_MUTED = 0xffa8b0bd;
    private static final int COLOR_SURFACE = 0xff171c25;
    private static final int COLOR_BACKGROUND = 0xff0a0911;
    private static final int COLOR_PANEL = 0xff14121d;
    private static final int COLOR_PINK = 0xffff67c8;
    private static final int COLOR_CYAN = 0xff69e5ed;

    private final ExecutorService networkWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService updateWorker = Executors.newSingleThreadExecutor();
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable voiceStatusRefresh = new Runnable() {
        @Override
        public void run() {
            refreshListeningStatus();
            mainHandler.postDelayed(this, 750);
        }
    };
    private EditText messageInput;
    private Button sendButton;
    private Button saveTextButton;
    private Button shareTextButton;
    private Button talkButton;
    private Button listenButton;
    private TextView connectionStatus;
    private TextView listeningStatus;
    private ImageView avatarPreview;
    private LinearLayout conversation;
    private ScrollView conversationScroll;
    private CheckBox speakReplies;
    private CheckBox autoListening;
    private Button gameUsageButton;
    private Button avatarOverlayButton;
    private AlertDialog settingsDialog;
    private boolean assistantStartRequested;
    private boolean commandRequestedAfterPermission;
    private boolean listeningRequestedAfterPermission;
    private boolean speechOutputPending;
    private String lastAssistantResponse = "";
    private int updateNoticeShownForVersionCode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        assistantStartRequested = getIntent().getBooleanExtra(
                SirvientaVoiceInteractionSessionService.EXTRA_START_LISTENING,
                false);
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildInterface(preferences);
        loadConversation();
        checkForUpdates();
        refreshListeningStatus();
        if (assistantStartRequested) {
            startListeningFromAssistant();
        } else if (preferences.getBoolean(PREF_AUTO_LISTENING, true)) {
            mainHandler.post(this::startListeningAutomatically);
        }
    }

    private void buildInterface(SharedPreferences preferences) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BACKGROUND);
        root.setPadding(dp(16), dp(12), dp(16), dp(10));
        getWindow().setStatusBarColor(COLOR_BACKGROUND);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("SIRVIENTA");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(21);
        title.setLetterSpacing(0.1f);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button settingsButton = button("AJUSTES");
        settingsButton.setTextSize(11);
        header.addView(settingsButton, wrapWrap());
        root.addView(header, matchWrap());

        connectionStatus = new TextView(this);
        connectionStatus.setText("●  ACCIONES LOCALES LISTAS · IA BAJO DEMANDA");
        connectionStatus.setTextColor(COLOR_CYAN);
        connectionStatus.setTextSize(10);
        connectionStatus.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        connectionStatus.setPadding(0, dp(2), 0, dp(10));
        root.addView(connectionStatus, matchWrap());

        LinearLayout voiceControls = new LinearLayout(this);
        voiceControls.setOrientation(LinearLayout.VERTICAL);
        voiceControls.setPadding(dp(15), dp(13), dp(15), dp(13));
        voiceControls.setBackground(roundedBackground(COLOR_PANEL, 0xff343044, 20));
        LinearLayout settingsContent = new LinearLayout(this);
        settingsContent.setOrientation(LinearLayout.VERTICAL);
        settingsContent.setPadding(dp(16), dp(12), dp(16), dp(12));
        settingsContent.setBackground(roundedBackground(COLOR_PANEL, 0xff343044, 18));

        TextView heroKicker = new TextView(this);
        heroKicker.setText("SISTEMA DE VOZ  /  VERSION " + BuildConfig.VERSION_NAME);
        heroKicker.setTextColor(COLOR_CYAN);
        heroKicker.setTextSize(10);
        heroKicker.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        voiceControls.addView(heroKicker, matchWrap());

        TextView heroTitle = new TextView(this);
        heroTitle.setText("Tu asistente.\nA tu manera.");
        heroTitle.setTextColor(COLOR_TEXT);
        heroTitle.setTextSize(25);
        heroTitle.setTypeface(null, Typeface.BOLD);
        heroTitle.setPadding(0, dp(8), 0, dp(3));
        voiceControls.addView(heroTitle, matchWrap());

        TextView localMode = new TextView(this);
        localMode.setText("Comandos del móvil disponibles al instante · conversación IA bajo demanda");
        localMode.setTextColor(COLOR_MUTED);
        localMode.setTextSize(12);
        localMode.setPadding(0, 0, 0, dp(9));
        voiceControls.addView(localMode, matchWrap());

        listeningStatus = new TextView(this);
        listeningStatus.setTextColor(COLOR_CYAN);
        listeningStatus.setTextSize(11);
        listeningStatus.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        listeningStatus.setPadding(0, dp(5), 0, dp(8));
        voiceControls.addView(listeningStatus, matchWrap());
        avatarPreview = new ImageView(this);
        avatarPreview.setImageResource(R.drawable.sirvienta_avatar);
        avatarPreview.setContentDescription("Avatar de Sirvienta activa");
        avatarPreview.setAdjustViewBounds(true);
        avatarPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        avatarPreview.setVisibility(View.GONE);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(82), dp(92));
        avatarParams.gravity = Gravity.CENTER_HORIZONTAL;
        voiceControls.addView(avatarPreview, avatarParams);

        LinearLayout voiceButtons = new LinearLayout(this);
        voiceButtons.setOrientation(LinearLayout.HORIZONTAL);
        listenButton = button("ACTIVAR ESCUCHA");
        listenButton.setOnClickListener(view -> startListening());
        voiceButtons.addView(listenButton, weightedButton());
        Button stopButton = button("APAGAR");
        stopButton.setOnClickListener(view -> stopListening());
        voiceButtons.addView(stopButton, weightedButton());
        root.addView(voiceControls, matchWrap());
        LinearLayout.LayoutParams voiceButtonsParams = matchWrap();
        voiceButtonsParams.setMargins(0, dp(8), 0, dp(7));
        root.addView(voiceButtons, voiceButtonsParams);

        LinearLayout assistantButtons = new LinearLayout(this);
        assistantButtons.setOrientation(LinearLayout.HORIZONTAL);
        talkButton = button("Hablar");
        talkButton.setOnClickListener(view -> startDirectSpeechRecognition());
        assistantButtons.addView(talkButton, weightedButton());
        Button assistantButton = button("Asistente Android");
        assistantButton.setOnClickListener(view -> requestAssistantRole());
        assistantButtons.addView(assistantButton, weightedButton());
        settingsContent.addView(assistantButtons, matchWrap());

        speakReplies = new CheckBox(this);
        speakReplies.setText("Leer las respuestas en voz alta");
        speakReplies.setTextColor(COLOR_TEXT);
        speakReplies.setButtonTintList(android.content.res.ColorStateList.valueOf(COLOR_PINK));
        speakReplies.setChecked(preferences.getBoolean(PREF_SPEAK_REPLIES, true));
        speakReplies.setOnCheckedChangeListener((button, checked) ->
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREF_SPEAK_REPLIES, checked)
                        .apply());
        settingsContent.addView(speakReplies, matchWrap());
        autoListening = new CheckBox(this);
        autoListening.setText("Iniciar la escucha automáticamente al abrir");
        autoListening.setTextColor(COLOR_TEXT);
        autoListening.setButtonTintList(android.content.res.ColorStateList.valueOf(COLOR_PINK));
        autoListening.setChecked(preferences.getBoolean(PREF_AUTO_LISTENING, true));
        autoListening.setOnCheckedChangeListener((button, checked) ->
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREF_AUTO_LISTENING, checked)
                        .apply());
        settingsContent.addView(autoListening, matchWrap());
        gameUsageButton = button("Permitir avisos de juegos");
        gameUsageButton.setOnClickListener(view -> openGameUsageSettings());
        settingsContent.addView(gameUsageButton, matchWrap());
        avatarOverlayButton = button("Permitir avatar flotante");
        avatarOverlayButton.setOnClickListener(view -> openAvatarOverlaySettings());
        settingsContent.addView(avatarOverlayButton, matchWrap());
        Button notificationAccessButton = button("Configurar lectura de notificaciones");
        notificationAccessButton.setOnClickListener(view -> openNotificationAccessSettings());
        settingsContent.addView(notificationAccessButton, matchWrap());

        conversationScroll = new ScrollView(this);
        conversationScroll.setFillViewport(true);
        conversationScroll.setBackground(roundedBackground(COLOR_SURFACE, 0xff282533, 18));
        conversationScroll.setPadding(dp(10), dp(9), dp(10), dp(9));
        conversation = new LinearLayout(this);
        conversation.setOrientation(LinearLayout.VERTICAL);
        conversationScroll.addView(conversation);
        LinearLayout.LayoutParams conversationParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        conversationParams.setMargins(0, dp(2), 0, dp(9));
        root.addView(
                conversationScroll,
                conversationParams);

        LinearLayout composer = new LinearLayout(this);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(10), dp(4), dp(4), dp(4));
        composer.setBackground(roundedBackground(COLOR_PANEL, 0xff393447, 15));
        messageInput = new EditText(this);
        messageInput.setSingleLine(false);
        messageInput.setMaxLines(3);
        messageInput.setTextColor(COLOR_TEXT);
        messageInput.setHintTextColor(COLOR_MUTED);
        messageInput.setHint("Di «Sirvienta» o escribe aquí…");
        messageInput.setTextSize(14);
        messageInput.setPadding(dp(6), dp(9), dp(6), dp(9));
        messageInput.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        messageInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        messageInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        messageInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessageFromInput();
                return true;
            }
            return false;
        });
        composer.addView(
                messageInput,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sendButton = button("Enviar");
        sendButton.setTextColor(COLOR_BACKGROUND);
        sendButton.setBackground(roundedBackground(COLOR_PINK, COLOR_PINK, 12));
        sendButton.setOnClickListener(view -> sendMessageFromInput());
        composer.addView(sendButton, wrapWrap());
        LinearLayout.LayoutParams composerParams = matchWrap();
        composerParams.setMargins(0, 0, 0, dp(5));
        root.addView(composer, composerParams);

        LinearLayout shortcuts = new LinearLayout(this);
        shortcuts.setOrientation(LinearLayout.HORIZONTAL);
        Button spotifyShortcut = button("♫  Spotify");
        spotifyShortcut.setTextColor(COLOR_CYAN);
        spotifyShortcut.setOnClickListener(view -> messageInput.setText(
                "Métete en Spotify y reproduce la canción "));
        shortcuts.addView(spotifyShortcut, weightedButton());
        Button whatsappShortcut = button("WhatsApp  ↗");
        whatsappShortcut.setTextColor(COLOR_PINK);
        whatsappShortcut.setOnClickListener(view -> messageInput.setText(
                "Escríbele a papá un WhatsApp diciendo que "));
        shortcuts.addView(whatsappShortcut, weightedButton());
        LinearLayout.LayoutParams shortcutParams = matchWrap();
        shortcutParams.setMargins(0, 0, 0, dp(7));
        root.addView(shortcuts, shortcutParams);

        LinearLayout textActions = new LinearLayout(this);
        textActions.setOrientation(LinearLayout.HORIZONTAL);
        textActions.setPadding(0, dp(4), 0, 0);
        saveTextButton = button("Guardar texto");
        saveTextButton.setEnabled(false);
        saveTextButton.setOnClickListener(view -> saveLastText());
        textActions.addView(saveTextButton, weightedButton());
        shareTextButton = button("Compartir texto");
        shareTextButton.setEnabled(false);
        shareTextButton.setOnClickListener(view -> shareLastText());
        textActions.addView(shareTextButton, weightedButton());
        root.addView(textActions, matchWrap());

        settingsDialog = new AlertDialog.Builder(this)
                .setTitle("Ajustes de Sirvienta")
                .setView(settingsContent)
                .setPositiveButton("Listo", null)
                .create();
        settingsButton.setOnClickListener(view -> settingsDialog.show());

        setContentView(root);
        refreshGameUsageButton();
        refreshAvatarOverlayButton();
        refreshListeningStatus();
    }

    private GradientDrawable roundedBackground(int color, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private void loadConversation() {
        conversation.removeAllViews();
        lastAssistantResponse = "";
        refreshTextActions();
        try {
            JSONArray messages = readLocalHistory();
            if (messages.length() == 0) {
                addMessage("Sirvienta", "Hola. Estoy lista para ayudarte sin conexión.", false);
                return;
            }
            for (int index = 0; index < messages.length(); index++) {
                JSONObject item = messages.optJSONObject(index);
                if (item != null) {
                    addMessage(
                            "user".equals(item.optString("role")) ? "Tú" : "Sirvienta",
                            item.optString("content", ""),
                            false);
                }
            }
        } catch (JSONException error) {
            addMessage("Sistema", "No se pudo leer el historial local: " + error.getMessage(), false);
        }
    }

    private void sendMessageFromInput() {
        String text = messageInput.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > 1800) {
            messageInput.setError("Limita cada mensaje a 1800 caracteres para el modelo local.");
            return;
        }
        messageInput.setText("");
        sendButton.setEnabled(false);
        talkButton.setEnabled(false);
        addMessage("Tú", text, false);
        addMessage("Sirvienta", "Pensando…", false);
        boolean speakResponse = speakReplies.isChecked();

        networkWorker.execute(() -> {
            try {
                DeviceActionHandler.Result localAction =
                        DeviceActionHandler.executeLocalCommand(getApplicationContext(), text);
                String prompt = localAction == null ? buildLocalPrompt(text) : null;
                LocalConversationHistory.append(getApplicationContext(), "user", text);
                String reply;
                if (localAction != null) {
                    reply = localAction.message;
                } else {
                    runOnUiThread(() -> {
                        if (connectionStatus != null) {
                            connectionStatus.setText("●  CARGANDO IA LOCAL");
                            connectionStatus.setTextColor(COLOR_CYAN);
                        }
                    });
                    LocalLlm.load(getApplicationContext());
                    reply = LocalLlm.generate(prompt, 192);
                }
                LocalConversationHistory.append(getApplicationContext(), "assistant", reply);
                if (localAction == null && speakResponse) {
                    LocalLlm.close();
                }
                runOnUiThread(() -> {
                    removeThinkingMessage();
                    addMessage("Sirvienta", reply, false);
                    sendButton.setEnabled(true);
                    talkButton.setEnabled(true);
                    if (localAction == null && connectionStatus != null) {
                        connectionStatus.setText("●  ACCIONES LOCALES LISTAS · IA BAJO DEMANDA");
                        connectionStatus.setTextColor(COLOR_CYAN);
                    }
                    if (speakResponse) {
                        speakReply(reply);
                    } else {
                        finishVoiceTurn();
                    }
                });
            } catch (IOException | IllegalStateException | IllegalArgumentException | JSONException error) {
                runOnUiThread(() -> {
                    removeThinkingMessage();
                    addMessage("Error", error.getMessage(), false);
                    if (connectionStatus != null) {
                        connectionStatus.setText("●  ACCIONES LOCALES LISTAS · IA BAJO DEMANDA");
                        connectionStatus.setTextColor(COLOR_CYAN);
                    }
                    sendButton.setEnabled(true);
                    talkButton.setEnabled(true);
                    finishVoiceTurn();
                });
            }
        });
    }

    private JSONArray readLocalHistory() throws JSONException {
        String storedHistory = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_LOCAL_HISTORY, "[]");
        return new JSONArray(storedHistory);
    }

    private String buildLocalPrompt(String currentMessage) throws JSONException {
        return LocalConversationHistory.buildPrompt(this, currentMessage);
    }

    private void startDirectSpeechRecognition() {
        if (!hasRequiredPermissions()) {
            commandRequestedAfterPermission = true;
            requestPermissions(requiredPermissions(), PERMISSION_REQUEST);
            return;
        }
        startCommandListening();
    }

    private void startCommandListening() {
        Intent intent = new Intent(this, WakeWordService.class);
        intent.setAction(WakeWordService.ACTION_BEGIN_COMMAND);
        try {
            if (getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean(PREF_LISTENING_ACTIVE, false)) {
                startService(intent);
            } else {
                startWakeWordService(true);
            }
            talkButton.setText("Di tu comando");
        } catch (RuntimeException error) {
            Log.e("SirvientaMain", "Android no pudo iniciar el reconocimiento local.", error);
            addMessage("Error", "Android no pudo activar el micrófono: " + error.getMessage(), false);
        }
    }

    private void speakReply(String text) {
        speechOutputPending = true;
        BundledSpeechOutput.getInstance().speak(this, text, new BundledSpeechOutput.Listener() {
            @Override
            public void onComplete() {
                mainHandler.post(() -> {
                    speechOutputPending = false;
                    finishVoiceTurn();
                });
            }

            @Override
            public void onError(Throwable error) {
                Log.e("SirvientaMain", "Falló la voz española incluida.", error);
                mainHandler.post(() -> {
                    speechOutputPending = false;
                    addMessage("Error", "No se pudo reproducir la voz local: " + error.getMessage(), false);
                    finishVoiceTurn();
                });
            }
        });
    }

    private void finishVoiceTurn() {
        speechOutputPending = false;
    }

    private void startListening() {
        if (!hasRequiredPermissions()) {
            listeningRequestedAfterPermission = true;
            requestPermissions(requiredPermissions(), PERMISSION_REQUEST);
            return;
        }
        startWakeWordService(false);
        promptForAvatarOverlayIfNeeded();
    }

    private void startListeningAutomatically() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (!hasRequiredPermissions()) {
            addMessage(
                    "Sistema",
                    "Para activar la escucha automática, concede a Sirvienta el permiso de micrófono y notificaciones. Android mostrará una notificación permanente mientras escuche.",
                    false);
            requestPermissions(requiredPermissions(), PERMISSION_REQUEST);
            return;
        }
        startWakeWordService(false);
        promptForAvatarOverlayIfNeeded();
    }

    private void promptForAvatarOverlayIfNeeded() {
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this)
                || preferences.getBoolean(PREF_OVERLAY_PROMPTED, false)) {
            return;
        }
        preferences.edit().putBoolean(PREF_OVERLAY_PROMPTED, true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Avatar flotante")
                .setMessage("Para mostrar el avatar animado en una esquina mientras Sirvienta escucha en segundo plano, permite «Mostrar sobre otras apps». La escucha funciona aunque no lo permitas.")
                .setPositiveButton("Configurar", (dialog, which) -> openAvatarOverlaySettings())
                .setNegativeButton("Ahora no", null)
                .show();
    }

    private void startListeningFromAssistant() {
        if (!hasRequiredPermissions()) {
            requestPermissions(requiredPermissions(), PERMISSION_REQUEST);
            return;
        }
        assistantStartRequested = false;
        startWakeWordService(false);
    }

    private void stopListening() {
        stopService(new Intent(this, WakeWordService.class));
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_LISTENING_ACTIVE, false)
                .apply();
        refreshListeningStatus();
    }

    private void requestAssistantRole() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            addMessage("Sistema", "El asistente predeterminado necesita Android 10 o posterior.", false);
            return;
        }
        RoleManager roleManager = getSystemService(RoleManager.class);
        if (roleManager == null || !roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
            addMessage("Sistema", "Este Android no ofrece el rol de asistente digital.", false);
            return;
        }
        if (roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            addMessage("Sistema", "Sirvienta ya es el asistente predeterminado.", false);
            return;
        }
        startActivityForResult(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT),
                ASSISTANT_ROLE_REQUEST);
    }

    private void openGameUsageSettings() {
        if (hasGameUsageAccess()) {
            addMessage(
                    "Sistema",
                    "Avisos de juegos activos. Sirvienta te avisará al entrar por séptima vez al mismo juego en un día.",
                    false);
            return;
        }
        addMessage(
                "Sistema",
                "Activa «Sirvienta» en Acceso al uso para detectar juegos abiertos en este móvil. Los nombres de las apps se procesan aquí y no se envían al servidor.",
                false);
        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
    }

    private boolean hasGameUsageAccess() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        return appOps != null
                && appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(),
                        getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    private void refreshGameUsageButton() {
        if (gameUsageButton != null) {
            gameUsageButton.setText(hasGameUsageAccess()
                    ? "Avisos de juegos activados"
                    : "Permitir avisos de juegos");
        }
    }

    private void openAvatarOverlaySettings() {
        if (Settings.canDrawOverlays(this)) {
            addMessage(
                    "Sistema",
                    "Avatar flotante activado. Aparecerá animado en una esquina cuando Sirvienta esté escuchando en segundo plano.",
                    false);
            return;
        }
        addMessage(
                "Sistema",
                "Permite «Mostrar sobre otras apps» para que el avatar aparezca sin abrir el menú. La escucha y las respuestas de voz funcionan aunque no concedas este permiso.",
                false);
        Intent settingsIntent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(settingsIntent);
    }

    private void openNotificationAccessSettings() {
        if (NotificationAccessService.hasNotificationAccess(this)) {
            addMessage(
                    "Sistema",
                    "Sirvienta puede leer las notificaciones activas que Android muestra. El contenido se procesa en este móvil y solo se consulta cuando lo pides.",
                    false);
        } else {
            addMessage(
                    "Sistema",
                    "Android exige que actives manualmente el acceso a notificaciones. Sirvienta solo podrá leer notificaciones visibles; no puede abrir ni inspeccionar la bandeja privada dentro de Instagram.",
                    false);
        }
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (ActivityNotFoundException error) {
            Log.e("SirvientaMain", "No se encontraron los ajustes de acceso a notificaciones.", error);
            addMessage("Error", "No se pudieron abrir los ajustes de notificaciones.", false);
        }
    }

    private void refreshAvatarOverlayButton() {
        if (avatarOverlayButton != null) {
            avatarOverlayButton.setText(Settings.canDrawOverlays(this)
                    ? "Avatar flotante activado"
                    : "Permitir avatar flotante");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        preferences.edit().putBoolean(WakeWordService.PREF_APP_FOREGROUND, true).apply();
        if (preferences.getBoolean(PREF_LISTENING_ACTIVE, false)) {
            Intent visibilityIntent = new Intent(this, WakeWordService.class);
            visibilityIntent.setAction(WakeWordService.ACTION_UPDATE_APP_VISIBILITY);
            visibilityIntent.putExtra(WakeWordService.EXTRA_APP_FOREGROUND, true);
            startService(visibilityIntent);
        }
        refreshGameUsageButton();
        refreshAvatarOverlayButton();
        mainHandler.removeCallbacks(voiceStatusRefresh);
        mainHandler.post(voiceStatusRefresh);
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(voiceStatusRefresh);
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        preferences.edit().putBoolean(WakeWordService.PREF_APP_FOREGROUND, false).apply();
        if (preferences.getBoolean(PREF_LISTENING_ACTIVE, false)) {
            Intent visibilityIntent = new Intent(this, WakeWordService.class);
            visibilityIntent.setAction(WakeWordService.ACTION_UPDATE_APP_VISIBILITY);
            visibilityIntent.putExtra(WakeWordService.EXTRA_APP_FOREGROUND, false);
            startService(visibilityIntent);
        }
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SAVE_TEXT_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                writeLastText(data.getData());
            }
            return;
        }
        if (requestCode == ASSISTANT_ROLE_REQUEST) {
            addMessage(
                    "Sistema",
                    resultCode == RESULT_OK
                            ? "Sirvienta es el asistente. Usa el gesto o botón de asistente de Android."
                            : "No se cambió el asistente predeterminado.",
                    false);
        }
    }

    private void saveLastText() {
        if (lastAssistantResponse.isEmpty()) {
            return;
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        Intent createDocument = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, "Sirvienta-" + timestamp + ".txt");
        try {
            startActivityForResult(createDocument, SAVE_TEXT_REQUEST);
        } catch (ActivityNotFoundException error) {
            Log.e("SirvientaMain", "No hay un selector de documentos disponible.", error);
            addMessage("Error", "No se pudo abrir el selector para guardar el texto.", false);
        }
    }

    private void writeLastText(Uri destination) {
        String text = lastAssistantResponse;
        networkWorker.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) {
                    throw new IOException("Android no pudo abrir el archivo elegido.");
                }
                output.write(text.getBytes(StandardCharsets.UTF_8));
                output.flush();
                runOnUiThread(() ->
                        addMessage("Sistema", "Texto guardado correctamente.", false));
            } catch (IOException | SecurityException error) {
                Log.e("SirvientaMain", "No se pudo guardar el texto seleccionado.", error);
                runOnUiThread(() ->
                        addMessage("Error", "No se pudo guardar el texto: " + error.getMessage(), false));
            }
        });
    }

    private void shareLastText() {
        if (lastAssistantResponse.isEmpty()) {
            return;
        }
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, lastAssistantResponse);
        try {
            startActivity(Intent.createChooser(share, "Compartir texto de Sirvienta"));
        } catch (ActivityNotFoundException error) {
            Log.e("SirvientaMain", "No hay aplicaciones disponibles para compartir texto.", error);
            addMessage("Error", "No hay ninguna aplicación disponible para compartir el texto.", false);
        }
    }

    private void refreshTextActions() {
        boolean hasText = !lastAssistantResponse.isEmpty();
        if (saveTextButton != null) {
            saveTextButton.setEnabled(hasText);
        }
        if (shareTextButton != null) {
            shareTextButton.setEnabled(hasText);
        }
    }

    private void checkForUpdates() {
        String manifestUrl = BuildConfig.UPDATE_MANIFEST_URL.trim();
        if (manifestUrl.isEmpty()) {
            Log.i("SirvientaMain", "No se configuró el servidor HTTPS de actualizaciones.");
            return;
        }
        updateWorker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(manifestUrl);
                if (!"https".equalsIgnoreCase(url.getProtocol())) {
                    throw new IOException("El servidor de actualizaciones debe usar HTTPS.");
                }
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8_000);
                connection.setReadTimeout(8_000);
                connection.setRequestProperty("Accept", "application/json");
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException(
                            "El servidor de actualizaciones respondió HTTP " + connection.getResponseCode());
                }
                JSONObject release;
                try (InputStream input = connection.getInputStream();
                        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                    release = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
                }
                if (!release.optBoolean("available", false)) {
                    return;
                }
                int latestVersionCode = release.optInt("version_code", 0);
                String latestVersionName = release.optString("version_name", "").trim();
                String downloadUrl = release.optString("download_url", "").trim();
                URL resolvedDownloadUrl = new URL(url, downloadUrl);
                if (latestVersionCode <= BuildConfig.VERSION_CODE
                        || latestVersionName.isEmpty()
                        || !"https".equalsIgnoreCase(resolvedDownloadUrl.getProtocol())) {
                    return;
                }
                if (latestVersionCode == updateNoticeShownForVersionCode) {
                    return;
                }
                updateNoticeShownForVersionCode = latestVersionCode;
                runOnUiThread(() -> announceAvailableUpdate(latestVersionName, resolvedDownloadUrl.toString()));
            } catch (IOException | JSONException error) {
                Log.w("SirvientaMain", "No se pudo comprobar si hay una actualización.", error);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void announceAvailableUpdate(String versionName, String downloadUrl) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        String notice = "Hay una actualización de Sirvienta disponible (versión "
                + versionName
                + "). Toca para descargarla; Android te pedirá confirmar la instalación.";
        addMessage("Sistema", notice, false);
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_SPEAK_REPLIES, true)) {
            speakReply("Hay una actualización de Sirvienta. Te aviso para que puedas instalarla.");
        }
        new AlertDialog.Builder(this)
                .setTitle("Actualización disponible")
                .setMessage(notice)
                .setNegativeButton("Más tarde", null)
                .setPositiveButton("Descargar", (dialog, which) -> {
                    Intent download = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
                    try {
                        startActivity(download);
                    } catch (ActivityNotFoundException error) {
                        Log.e("SirvientaMain", "No se pudo abrir la descarga de la actualización.", error);
                        addMessage("Error", "No se pudo abrir el enlace de actualización.", false);
                    }
                })
                .show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadConversation();
        if (intent.getBooleanExtra(
                SirvientaVoiceInteractionSessionService.EXTRA_START_LISTENING,
                false)) {
            assistantStartRequested = true;
            startListeningFromAssistant();
        }
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[] {
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        }
        return new String[] {Manifest.permission.RECORD_AUDIO};
    }

    private boolean hasRequiredPermissions() {
        for (String permission : requiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST) {
            return;
        }
        if (!hasRequiredPermissions()) {
            assistantStartRequested = false;
            addMessage("Sistema", "Se necesitan los permisos de micrófono y notificaciones.", false);
            return;
        }
        if (commandRequestedAfterPermission) {
            commandRequestedAfterPermission = false;
            startCommandListening();
        } else if (listeningRequestedAfterPermission) {
            listeningRequestedAfterPermission = false;
            startListening();
        } else if (assistantStartRequested) {
            startListeningFromAssistant();
        } else {
            if (getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean(PREF_AUTO_LISTENING, true)) {
                startListeningAutomatically();
            }
        }
    }

    private void startWakeWordService(boolean beginWithCommand) {
        Intent intent = new Intent(this, WakeWordService.class);
        intent.putExtra(WakeWordService.EXTRA_BEGIN_COMMAND, beginWithCommand);
        try {
            ContextCompat.startForegroundService(this, intent);
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_LISTENING_ACTIVE, true)
                    .apply();
            refreshListeningStatus();
        } catch (RuntimeException error) {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_LISTENING_ACTIVE, false)
                    .apply();
            addMessage("Error", "Android no pudo iniciar la escucha: " + error.getMessage(), false);
        }
    }

    private void addMessage(String speaker, String text, boolean animate) {
        if (conversation == null) {
            return;
        }
        if ("Sirvienta".equals(speaker) && !"Pensando…".equals(text)) {
            lastAssistantResponse = text == null ? "" : text;
            refreshTextActions();
        }
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(13), dp(10), dp(13), dp(10));
        bubble.setBackground(roundedBackground(
                "Tú".equals(speaker) ? 0xff20202d : 0xff191823,
                "Tú".equals(speaker) ? 0xff68415f : 0xff343044,
                15));
        TextView label = new TextView(this);
        label.setText(speaker);
        label.setTextColor("Tú".equals(speaker) ? COLOR_PINK : COLOR_CYAN);
        label.setTextSize(11);
        label.setTypeface(null, Typeface.BOLD);
        label.setLetterSpacing(0.06f);
        bubble.addView(label, matchWrap());
        TextView content = new TextView(this);
        content.setText(text == null ? "Se produjo un error sin detalle." : text);
        content.setTextColor(COLOR_TEXT);
        content.setTextSize(14);
        content.setPadding(0, dp(3), 0, 0);
        bubble.addView(content, matchWrap());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(8));
        conversation.addView(bubble, params);
        conversationScroll.post(() -> conversationScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void removeThinkingMessage() {
        for (int index = conversation.getChildCount() - 1; index >= 0; index--) {
            View child = conversation.getChildAt(index);
            if (child instanceof LinearLayout) {
                LinearLayout bubble = (LinearLayout) child;
                if (bubble.getChildCount() > 1
                        && bubble.getChildAt(1) instanceof TextView
                        && "Pensando…".contentEquals(((TextView) bubble.getChildAt(1)).getText())) {
                    conversation.removeViewAt(index);
                    return;
                }
            }
        }
    }

    private void refreshListeningStatus() {
        if (listeningStatus == null) {
            return;
        }
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean active = preferences
                .getBoolean(PREF_LISTENING_ACTIVE, false);
        boolean engaged = active && preferences.getBoolean(
                WakeWordService.PREF_ASSISTANT_ENGAGED,
                false);
        listeningStatus.setText(!active
                ? "● MICRÓFONO APAGADO · ACTIVA LA ESCUCHA"
                : engaged
                        ? "● SIRVIENTA ACTIVA · TE ESCUCHO"
                        : "● EN ESPERA · DI «SIRVIENTA» PARA ACTIVAR");
        listeningStatus.setTextColor(!active ? COLOR_MUTED : engaged ? COLOR_PINK : COLOR_CYAN);
        if (avatarPreview != null) {
            avatarPreview.setVisibility(engaged ? View.VISIBLE : View.GONE);
        }
    }

    private Button button(String title) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(12);
        button.setTypeface(null, Typeface.BOLD);
        button.setAllCaps(false);
        button.setPadding(dp(12), dp(5), dp(12), dp(5));
        button.setMinHeight(dp(44));
        button.setBackground(roundedBackground(0xff211e2c, 0xff3d374b, 13));
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        networkWorker.shutdownNow();
        updateWorker.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

# SIRVIENTA

SIRVIENTA is a modular personal assistant platform inspired by a high-end futuristic OS, designed as a real, secure, and extensible base for voice-driven automation.

## Architecture

- `sirvienta/config.py` - environment-driven config and settings
- `sirvienta/database.py` - SQLite persistence for memory, automation, reminders and activity logs
- `sirvienta/tools.py` - tool registry and permissions-aware executor
- `sirvienta/voice.py` - wake word, STT and TTS adapters
- `sirvienta/assistant.py` - plan-and-execute orchestration layer
- `sirvienta/app.py` - FastAPI backend and UI serving
- `web/` - dashboard + orb UI

## Quick start

1. Create a virtual environment.
2. Install requirements:
   `pip install -r requirements.txt`
3. Copy `.env.example` to `.env` and adjust values.
4. Start the app:
   `python main.py`
5. Install and start [Ollama](https://ollama.com/), then download a model:
   `ollama pull qwen2.5:3b`
6. Set `OLLAMA_URL` in `.env` to an address reachable by the machine running Sirvienta. If Ollama runs on that same machine, keep `http://127.0.0.1:11434`; if Ollama runs elsewhere, use that server's local-network IP.
7. Start Sirvienta with `python main.py`, open `http://<computer-ip>:8000/` for the download page, or `http://<computer-ip>:8000/app` for the assistant dashboard.

### Open the web app on your phone

Keep `python main.py` running on the computer and connect the phone to the same non-guest Wi-Fi network. On a trusted home network, set the Windows Wi-Fi network profile to **Private** and allow Python through Windows Defender Firewall on private networks. Do not expose the app on public Wi-Fi or the public internet: its assistant API is not authenticated. Open `http://<computer-ip>:<APP_PORT>/app` in the phone's browser, replacing the IP with the computer's local IPv4 address from `ipconfig`; do not use `127.0.0.1`, `localhost`, or `0.0.0.0` on the phone. `APP_PORT` defaults to `8000`.

The locally hosted download page is at `/` (also `/descargar`). The GitHub Pages site is built from that page and publishes the signed Android beta APK through the repository's latest GitHub Release. The iPhone card on the public site is explicitly marked as in development: the current responsive web assistant is not an iPhone app and does not provide background wake-word listening.

### Android update notices and publishing limits

The Android app checks for a newer version on launch only when it is built with an HTTPS update manifest URL:

```powershell
cd android
.\gradlew.bat assembleDebug "-PsirvientaUpdateManifestUrl=https://YOUR-DOMAIN/api/android-release"
```

The locally hosted website's `/api/android-release` reports the APK currently present in that server's Android build output, and `/downloads/android` serves that file. The public GitHub workflow publishes a separate signed APK and update manifest; it assigns a higher version code to each successful workflow run. When a newer version is found, Sirvienta announces it once and opens the download after the user confirms; Android still requires the user to approve installing the downloaded APK.

The APK produced locally is debug-signed. Android only accepts an in-place update when the new APK has the same signing certificate as the installed app. The first signed public beta will therefore not install over a debug-signed beta; uninstalling the debug app first removes its local data. The GitHub Actions release workflow requires a stable signing key stored as GitHub Actions secrets; never commit the key or paste it into chat. The workflow publishes a signed beta and `latest.json`, then deploys the download page to GitHub Pages. It replaces the prior `sirvienta-latest` release so the stable download URL does not accumulate large APKs. Android still requires the user to approve an APK update.

### Public GitHub publishing setup

1. Create a public GitHub repository, push this source to its `main` branch, and enable **Settings → Pages → Build and deployment → GitHub Actions**.
2. To publish Android APKs, create and back up a permanent release keystore outside the repository. Add these repository Actions secrets: `ANDROID_SIGNING_KEYSTORE_BASE64` (the keystore's base64 contents), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Do not reuse the debug key, commit the keystore, or share its contents.
3. Each push to `main` deploys the download site. When all four Android signing secrets are configured, it also builds the ARM64 APK and publishes it with the update manifest as the `sirvienta-latest` GitHub Release. Until then, the page reports that no APK is published. Git LFS stores the bundled Qwen GGUF model; the workflow checks it out to compile Android. The static site does not contain the conversational backend, which remains unauthenticated and must not be exposed publicly.

The public page URL will be `https://<owner>.github.io/<repository>/`. The model's upstream Qwen2.5-0.5B-Instruct release is marked Apache-2.0; its attribution is included in `android/app/src/main/assets/THIRD_PARTY_NOTICES.txt`.

### iPhone background listening

The iPhone option currently available is the web app, which must stay open in Safari and cannot keep Sirvienta listening in the background. Meeting the background-listening requirement needs a native iOS app and device/App Store testing. iOS controls background microphone access, shows microphone-use indicators, and can stop apps; it cannot be guaranteed to wake after the user force-quits it. Building, signing, and validating that app requires macOS with Xcode and Apple signing access, which are not available in this Windows workspace.

If the page does not load, check that the server says it is listening on `0.0.0.0:<APP_PORT>`, that both devices are on the same Wi-Fi, and that the network does not enable client isolation. The page can load over HTTP, but mobile browsers generally require HTTPS for microphone access from a LAN address.

## Features implemented

- Local wake-word gating by keyword match with pluggable provider design
- Open-ended AI conversations through Ollama, with recent context persisted per conversation
- When enabled, recent saved memories are supplied to Ollama as personal context
- Intent parsing for common commands: open app, search web, time/date, reminders, timers, memory query, status requests
- Windows app launching by voice or chat, with common Spanish aliases and installed Start Menu shortcuts (for example, `abre la calculadora`, `abre Chrome`, or `abre el explorador`)
- Risk-based permission checks for actions
- SQLite-backed memory and reminders
- FastAPI endpoints for status and assistant processing
- A mobile-first responsive dashboard with browser speech input and orb animation

## Mobile voice limitations

The web app does not request microphone access until you tap **Activar micrófono**. Browser speech recognition may use an online provider; when it is unavailable, Sirvienta can use the local Vosk recognizer on the computer running the server. Neural web replies use the configured `TTS_VOICE` (default: `es-ES-ElviraNeural`) and require network access; the browser's own Spanish voice is a fallback. Microphone use from a phone generally requires HTTPS. The web page must remain open and active, and cannot listen over other apps or while the screen is locked. Use **Detener escucha** or say **“adiós”** to stop listening.

The native Android app is a separate, offline-first experience: its Qwen chat model, Vosk Spanish speech recognition, and Piper/Sherpa Spanish speech output are bundled with the app. It does not need the Python server, Ollama, or Internet access for those local features. The app copies the Vosk model and pronunciation data (about 78 MB combined) to private storage on first use; allow time and free storage for this step. The APK includes large models and currently targets `arm64-v8a`, so it is much larger than an ordinary app. The wake word is detected from Vosk's recognized text rather than a dedicated acoustic wake-word model, so recognition can vary with noise, pronunciation, and battery constraints.

Commands to open apps from Android are carried out on the phone; say **“abre [nombre de la app]”**. From the web app or desktop, app launches happen on the computer hosting Sirvienta. On Windows, supported examples include Calculadora, Bloc de notas, Explorador, Chrome, Edge, Firefox, VS Code, and Spotify.

Say **“apunta [nota]”** or **“recuerda que [dato]”** to save a note in the server's Sirvienta database, and **“lee mis notas”** to hear the latest notes. Notes are saved only after an explicit request. From Android, say **“pon una alarma a las 7 de la mañana”** or **“pon un temporizador de 5 minutos”**; Sirvienta asks for clarification if an alarm time could mean either morning or evening, then hands the explicit request to Android's Clock app. These alarm and timer commands are available through the Android app, not the web or desktop.

### Android setup

**Estado de validación:** la compilación Android y la síntesis offline del modelo español se han probado en este entorno. Todavía no se ha instalado ni probado en un teléfono físico; la escucha con pantalla bloqueada, el consumo de batería y la compatibilidad con el fabricante siguen pendientes de esa prueba. No se debe considerar certificada para todos los móviles.

1. Abre `android/` en Android Studio y deja que Gradle sincronice, o compila desde PowerShell con `.\gradlew.bat assembleDebug` dentro de esa carpeta.
2. Instala `android/app/build/outputs/apk/debug/app-debug.apk` en un móvil Android `arm64-v8a`. El APK es grande porque lleva el modelo local; revisa su tamaño y deja espacio adicional para los archivos que se copian al almacenamiento privado en el primer uso.
3. Abre Sirvienta y concede el permiso de micrófono y, cuando Android lo solicite, el de notificaciones. La escucha se inicia automáticamente al abrir la app. Para el avatar animado, permite también **Mostrar sobre otras apps** cuando Android abra sus ajustes.
4. Di **“Sirvienta”** y después el comando. La app procesa el chat con Qwen, reconoce voz con Vosk y pronuncia las respuestas con la voz Piper incluida, sin backend. Al salir de la app, una burbuja animada en una esquina indica que el servicio sigue escuchando; al tocarla se abre Sirvienta. **Detener** en la app o en la notificación termina la escucha.
5. El servicio foreground puede continuar al salir de la app, pero Android y las capas de ahorro de batería del fabricante pueden pausarlo o detenerlo. La escucha automática se intenta cada vez que abres Sirvienta; tras forzar el cierre desde Ajustes o reiniciar el móvil, vuelve a abrirla. Android no permite a una aplicación abrir libremente su pantalla desde el fondo al oír una palabra; Sirvienta procesa el comando en segundo plano y muestra el avatar flotante. Tocar el avatar abre la interfaz.
6. Di **“apunta que…”**, **“escribe una nota…”** o **“recuerda que…”** para guardar notas localmente, y **“lee mis notas”** para escucharlas. Para un aviso local, di **“recuérdame [tarea] en 20 minutos”**; Android puede retrasar un poco la notificación para ahorrar batería. También se admiten hora, alarmas, temporizadores y apertura de aplicaciones instaladas.
7. En el chat, usa **Guardar texto** para elegir dónde crear un archivo `.txt` con la última respuesta de Sirvienta, o **Compartir texto** para seleccionar una aplicación y confirmar el destino. Los botones se habilitan cuando hay una respuesta disponible.

La detección de “Sirvienta” se basa en palabras reconocidas por el modelo Vosk, no en un detector acústico especializado; puede activarse tarde o no detectar la palabra en condiciones ruidosas. El micrófono permanece activo mientras el servicio funciona y puede consumir batería; la notificación persistente indica que está escuchando y permite detenerlo. El avatar necesita permiso de superposición y los avisos de juegos requieren acceso al uso. Si los activas, Sirvienta puede hacer un comentario juguetón al entrar por séptima vez en el mismo juego del día, pero solo ocasionalmente y con un intervalo mínimo de ocho horas entre comentarios. Las notas y recordatorios se guardan en este teléfono; no se sincronizan con el backend. La app está configurada actualmente solo para `arm64-v8a`.

El backend web sigue necesitando el servidor Python y Ollama, y su voz neural web requiere conexión. Esto no afecta al modo local de la app Android.

Ollama keeps its model loaded for 10 minutes and generates at most 120 tokens per reply by default to reduce repeat-turn latency and keep spoken replies concise. Adjust `OLLAMA_KEEP_ALIVE` and `OLLAMA_NUM_PREDICT` in `.env` if needed.

## Production notes

This scaffold is intentionally modular and includes clear extension points for OpenAI, Ollama, local model providers, desktop app adapters, and permissions-aware tool execution. Real OS integrations should be added behind provider adapters and validated with explicit permissions.

## Safety

- Secrets are not committed; use environment variables
- Dangerous actions require explicit confirmation or a risk gate
- Logs keep technical errors separately from user-facing messages

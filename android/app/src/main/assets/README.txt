Offline Android assets

The APK includes the Qwen language model, Spanish Vosk speech-recognition
model, Piper Spanish voice, and sherpa-onnx pronunciation data. Android copies
the Vosk model and pronunciation data into private app storage on first use.
No Picovoice keyword files, access key, Python server, or Ollama service are
required by the native Android app.

The chat can save its latest Sirvienta reply as a UTF-8 text file through
Android's document picker, or share it through Android's app chooser.

The wake word is matched against Vosk's recognized text; it is not a dedicated
acoustic wake-word model. Continuous microphone use requires the foreground
service notification and may be limited by Android battery-management policies.
See the project README for installation and device-testing limitations.

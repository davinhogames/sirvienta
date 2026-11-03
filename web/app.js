const statusText = document.getElementById('statusText');
const stateLabel = document.getElementById('stateLabel');
const activityLogs = document.getElementById('activityLogs');
const conversationHistory = document.getElementById('conversationHistory');
const assistantReply = document.getElementById('assistantReply');
const assistantForm = document.getElementById('assistantForm');
const messageInput = document.getElementById('messageInput');
const clock = document.getElementById('clock');
const date = document.getElementById('date');
const wakeButton = document.getElementById('wakeButton');
const stopWakeButton = document.getElementById('stopWakeButton');
const statusButton = document.getElementById('statusButton');
const voiceToggle = document.getElementById('voiceToggle');
const repeatReply = document.getElementById('repeatReply');
const sendButton = assistantForm.querySelector('button[type="submit"]');
const navLinks = document.querySelectorAll('.nav');
const BrowserSpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
const conversationKey = 'sirvienta.conversationId';
const voicePreferenceKey = 'sirvienta.voiceEnabled';
let conversationId = localStorage.getItem(conversationKey);
let voiceEnabled = localStorage.getItem(voicePreferenceKey) !== 'false';
let lastReply = '';
let voiceStream = null;
let voiceAudioContext = null;
let voiceAudioSource = null;
let voiceProcessor = null;
let voiceSocket = null;
let voiceReconnectTimer = null;
let voiceSessionActive = false;
let neuralAudio = null;
let neuralAudioUrl = null;
let neuralSpeechAbortController = null;
let speakerIsSpeaking = false;
let browserRecognition = null;
let restartBrowserRecognition = true;
let browserSpeechPaused = false;
let voiceListenerEnabled = false;
let forceLocalVoiceRecognition = false;
if (!conversationId) {
  conversationId = window.crypto?.randomUUID
    ? window.crypto.randomUUID()
    : `mobile-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  localStorage.setItem(conversationKey, conversationId);
}

async function loadStatus() {
  const response = await fetch('/api/status');
  if (!response.ok) throw new Error('No se pudo consultar el estado del sistema.');
  const payload = await response.json();
  statusText.textContent = payload.mode === 'online' ? 'ONLINE' : 'OFFLINE';

  if (!payload.activity || !payload.activity.length) {
    const emptyLog = document.createElement('div');
    emptyLog.className = 'log-item';
    emptyLog.textContent = 'Sistema listo.';
    activityLogs.replaceChildren(emptyLog);
    return;
  }

  const logs = payload.activity.map((item) => {
    const entry = document.createElement('div');
    entry.className = 'log-item';
    const time = item.timestamp ? new Date(item.timestamp).toLocaleTimeString() : '';
    entry.textContent = `${time} · ${item.tool || 'assistant'} · ${item.result || 'OK'}`;
    return entry;
  });
  activityLogs.replaceChildren(...logs);
}

function createConversationMessage(role, content) {
  const message = document.createElement('article');
  message.className = `message ${role}`;

  const label = document.createElement('span');
  label.className = 'message-role';
  label.textContent = role === 'user' ? 'Tú' : 'Sirvienta';

  const body = document.createElement('p');
  body.className = 'message-content';
  body.textContent = content;

  message.append(label, body);
  return message;
}

function renderConversation(messages) {
  if (!messages.length) {
    const emptyState = document.createElement('p');
    emptyState.className = 'conversation-empty';
    emptyState.textContent = 'Aún no hay mensajes. Escribe algo para empezar.';
    conversationHistory.replaceChildren(emptyState);
    return;
  }

  conversationHistory.replaceChildren(
    ...messages.map(({ role, content }) => createConversationMessage(role, content))
  );
  conversationHistory.scrollTop = conversationHistory.scrollHeight;
}

function appendConversationMessage(role, content) {
  if (!content) return;
  conversationHistory.querySelector('.conversation-empty')?.remove();
  conversationHistory.append(createConversationMessage(role, content));
  conversationHistory.scrollTop = conversationHistory.scrollHeight;
}

async function loadConversation() {
  const response = await fetch(`/api/conversations/${encodeURIComponent(conversationId)}`);
  const payload = await response.json();
  if (!response.ok || payload.error) {
    throw new Error(payload.error || 'No se pudo cargar la conversación.');
  }
  renderConversation(payload.messages || []);
}

async function sendPrompt(event) {
  event.preventDefault();
  const text = messageInput.value.trim();
  if (!text || sendButton.disabled) return;

  sendButton.disabled = true;
  stateLabel.textContent = 'PROCESANDO';
  assistantReply.textContent = 'Analizando tu solicitud...';
  appendConversationMessage('user', text);

  try {
    const response = await fetch('/api/message', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text, conversation_id: conversationId, client: 'web' })
    });
    const payload = await response.json();
    if (!response.ok || payload.error) {
      throw new Error(payload.error || 'No se pudo enviar el comando.');
    }

    assistantReply.textContent = payload.text || 'No se obtuvo respuesta.';
    lastReply = payload.text || '';
    appendConversationMessage('assistant', lastReply);
    repeatReply.disabled = !lastReply;
    if (voiceEnabled && lastReply) speakReply(lastReply);
    stateLabel.textContent = payload.status === 'error'
      ? 'ERROR'
      : voiceSessionActive ? 'ESCUCHANDO' : 'DI SIRVIENTA';
    messageInput.value = '';
    try {
      await loadStatus();
    } catch (error) {
      console.error(error);
    }
  } catch (error) {
    const message = error.message || 'No se pudo conectar con Sirvienta.';
    assistantReply.textContent = message;
    appendConversationMessage('assistant', message);
    stateLabel.textContent = 'SIN CONEXIÓN';
  } finally {
    sendButton.disabled = false;
  }
}

function updateClock() {
  const now = new Date();
  clock.textContent = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  date.textContent = now.toLocaleDateString([], { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' });
}

assistantForm.addEventListener('submit', sendPrompt);
voiceToggle.addEventListener('click', () => {
  voiceEnabled = !voiceEnabled;
  localStorage.setItem(voicePreferenceKey, String(voiceEnabled));
  updateVoiceToggle();
  if (voiceEnabled) {
    speakReply(lastReply || 'Voz activada.');
  } else if ('speechSynthesis' in window) {
    window.speechSynthesis.cancel();
    neuralSpeechAbortController?.abort();
    neuralSpeechAbortController = null;
    if (neuralAudio) {
      neuralAudio.pause();
      neuralAudio = null;
    }
    if (neuralAudioUrl) {
      URL.revokeObjectURL(neuralAudioUrl);
      neuralAudioUrl = null;
    }
    speakerIsSpeaking = false;
    finishSpeechPlayback();
  }
});
repeatReply.addEventListener('click', () => {
  if (lastReply) speakReply(lastReply);
});
navLinks.forEach((link) => {
  link.addEventListener('click', () => {
    navLinks.forEach((item) => {
      item.classList.toggle('active', item === link);
      if (item === link) {
        item.setAttribute('aria-current', 'location');
      } else {
        item.removeAttribute('aria-current');
      }
    });
  });
});

function updateVoiceToggle() {
  voiceToggle.textContent = voiceEnabled ? 'Silenciar voz' : 'Activar voz';
  voiceToggle.setAttribute('aria-pressed', String(voiceEnabled));
}

async function speakReply(text) {
  if (!('speechSynthesis' in window) || !window.SpeechSynthesisUtterance) {
    console.warn('La síntesis de voz no está disponible en este navegador.');
    finishSpeechPlayback();
    return;
  }
  if (browserRecognition && !browserSpeechPaused) {
    browserSpeechPaused = true;
    try {
      browserRecognition.stop();
    } catch (error) {
      browserSpeechPaused = false;
      if (error.name !== 'InvalidStateError') {
        console.error('No se pudo pausar el reconocimiento durante la respuesta:', error);
      }
    }
  }
  window.speechSynthesis.cancel();
  neuralSpeechAbortController?.abort();
  neuralSpeechAbortController = new AbortController();
  const abortController = neuralSpeechAbortController;
  if (neuralAudio) {
    neuralAudio.pause();
    neuralAudio = null;
  }
  if (neuralAudioUrl) {
    URL.revokeObjectURL(neuralAudioUrl);
    neuralAudioUrl = null;
  }
  speakerIsSpeaking = true;
  stateLabel.textContent = 'GENERANDO VOZ';
  try {
    const response = await fetch(`/api/speech?text=${encodeURIComponent(text)}`, {
      signal: abortController.signal,
    });
    if (neuralSpeechAbortController !== abortController) return;
    if (!response.ok) {
      const errorPayload = await response.json().catch(() => ({}));
      throw new Error(errorPayload.detail || `La voz neural respondió HTTP ${response.status}.`);
    }
    const audioBlob = await response.blob();
    neuralAudioUrl = URL.createObjectURL(audioBlob);
    const player = new Audio(neuralAudioUrl);
    neuralAudio = player;
    player.onplay = () => {
      stateLabel.textContent = 'HABLANDO';
    };
    player.onended = () => {
      neuralAudio = null;
      if (neuralAudioUrl) URL.revokeObjectURL(neuralAudioUrl);
      neuralAudioUrl = null;
      speakerIsSpeaking = false;
      finishSpeechPlayback();
      if (stateLabel.textContent === 'HABLANDO') {
        stateLabel.textContent = voiceListenerEnabled
          ? (voiceSessionActive ? 'ESCUCHANDO' : 'DI SIRVIENTA')
          : 'ESCUCHA DETENIDA';
      }
    };
    player.onerror = () => {
      if (neuralAudio !== player) return;
      neuralAudio = null;
      if (neuralAudioUrl) URL.revokeObjectURL(neuralAudioUrl);
      neuralAudioUrl = null;
      console.error('No se pudo reproducir el audio de voz neural.');
      speakWithBrowserVoice(text);
    };
    await player.play();
    if (neuralSpeechAbortController === abortController) {
      neuralSpeechAbortController = null;
    }
    return;
  } catch (error) {
    if (neuralSpeechAbortController !== abortController) return;
    neuralSpeechAbortController = null;
    if (neuralAudio) {
      neuralAudio.pause();
      neuralAudio = null;
    }
    if (neuralAudioUrl) URL.revokeObjectURL(neuralAudioUrl);
    neuralAudioUrl = null;
    console.error('No se pudo usar la voz neural; se probará la voz del navegador:', error);
    speakWithBrowserVoice(text);
  }
}

function speakWithBrowserVoice(text) {
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = 'es-ES';
  utterance.rate = 1;
  utterance.pitch = 1;
  const spanishVoices = window.speechSynthesis.getVoices()
    .filter((voice) => voice.lang.toLowerCase().startsWith('es'));
  const spanishVoice = spanishVoices.find((voice) => /neural|natural|online/i.test(voice.name))
    || spanishVoices[0];
  if (spanishVoice) utterance.voice = spanishVoice;
  utterance.onstart = () => {
    stateLabel.textContent = 'HABLANDO';
  };
  utterance.onend = () => {
    speakerIsSpeaking = false;
    finishSpeechPlayback();
    if (stateLabel.textContent === 'HABLANDO') {
      stateLabel.textContent = voiceListenerEnabled
        ? (voiceSessionActive ? 'ESCUCHANDO' : 'DI SIRVIENTA')
        : 'ESCUCHA DETENIDA';
    }
  };
  utterance.onerror = (event) => {
    speakerIsSpeaking = false;
    finishSpeechPlayback();
    if (event.error !== 'canceled' && event.error !== 'interrupted') {
      console.error('Speech synthesis error:', event.error);
    }
    if (stateLabel.textContent === 'HABLANDO') {
      stateLabel.textContent = voiceListenerEnabled
        ? (voiceSessionActive ? 'ESCUCHANDO' : 'DI SIRVIENTA')
        : 'ESCUCHA DETENIDA';
    }
  };
  speakerIsSpeaking = true;
  window.speechSynthesis.speak(utterance);
}

function finishSpeechPlayback() {
  if (browserSpeechPaused) {
    browserSpeechPaused = false;
    if (restartBrowserRecognition && browserRecognition) {
      setTimeout(() => {
        try {
          browserRecognition.start();
        } catch (error) {
          if (error.name !== 'InvalidStateError') {
            console.error('No se pudo reanudar el reconocimiento:', error);
          }
        }
      }, 250);
    }
  }
}

updateVoiceToggle();

async function startVoiceListener() {
  wakeButton.disabled = true;
  wakeButton.textContent = 'Activando…';
  if (!window.isSecureContext) {
    voiceListenerEnabled = false;
    stateLabel.textContent = 'VOZ NO DISPONIBLE';
    assistantReply.textContent =
      'El navegador requiere una conexión segura para usar el micrófono. En el móvil, abre Sirvienta con HTTPS o usa la aplicación Android.';
    wakeButton.hidden = false;
    wakeButton.disabled = false;
    wakeButton.textContent = 'Reintentar voz';
    stopWakeButton.hidden = true;
    return;
  }

  voiceListenerEnabled = true;
  if (BrowserSpeechRecognition && !forceLocalVoiceRecognition) {
    try {
      startBrowserVoiceListener();
    } catch (error) {
      voiceListenerEnabled = false;
      stateLabel.textContent = 'ERROR DE VOZ';
      assistantReply.textContent = error.message || 'No se pudo iniciar el reconocimiento de voz.';
      wakeButton.hidden = false;
      wakeButton.disabled = false;
      wakeButton.textContent = 'Reintentar voz';
      stopWakeButton.hidden = true;
      console.error(error);
    }
    return;
  }
  try {
    if (!navigator.mediaDevices?.getUserMedia || !window.AudioContext) {
      throw new Error('Este navegador no permite escuchar el micrófono de forma continua.');
    }
    assistantReply.textContent = 'Solicitando acceso al micrófono...';
    if (!voiceStream) {
      voiceStream = await navigator.mediaDevices.getUserMedia({ audio: true });
      voiceAudioContext = new AudioContext();
      await voiceAudioContext.resume();
      voiceAudioSource = voiceAudioContext.createMediaStreamSource(voiceStream);
      voiceProcessor = voiceAudioContext.createScriptProcessor(2048, voiceAudioSource.channelCount, 1);
      voiceProcessor.onaudioprocess = (event) => {
        const output = event.outputBuffer.getChannelData(0);
        output.fill(0);
        if (!voiceSocket || voiceSocket.readyState !== WebSocket.OPEN || voiceSocket.bufferedAmount > 64_000) {
          return;
        }
        if (speakerIsSpeaking) {
          const silenceSamples = Math.floor(event.inputBuffer.length * 16_000 / voiceAudioContext.sampleRate);
          voiceSocket.send(new Int16Array(silenceSamples).buffer);
          return;
        }
        voiceSocket.send(resampleToPcm(event.inputBuffer, voiceAudioContext.sampleRate, 16_000));
      };
      voiceAudioSource.connect(voiceProcessor);
      voiceProcessor.connect(voiceAudioContext.destination);
    }
    connectVoiceSocket();
    wakeButton.hidden = true;
    stopWakeButton.hidden = false;
  } catch (error) {
    voiceListenerEnabled = false;
    stateLabel.textContent = 'ERROR DE VOZ';
    const errors = {
      NotAllowedError: 'Permite el acceso al micrófono en los permisos del navegador para SIRVIENTA.',
      NotFoundError: 'No se ha encontrado un micrófono disponible en este ordenador.'
    };
    assistantReply.textContent = errors[error.name] || error.message || 'No se pudo iniciar el micrófono.';
    wakeButton.hidden = false;
    wakeButton.disabled = false;
    wakeButton.textContent = 'Reintentar voz';
    stopWakeButton.hidden = true;
    console.error(error);
  }
}

function stopVoiceListener() {
  voiceListenerEnabled = false;
  voiceSessionActive = false;
  restartBrowserRecognition = false;
  browserSpeechPaused = false;
  clearTimeout(voiceReconnectTimer);

  if (browserRecognition) {
    try {
      browserRecognition.stop();
    } catch (error) {
      if (error.name !== 'InvalidStateError') {
        console.error('No se pudo detener el reconocimiento:', error);
      }
    }
  }

  if (voiceProcessor) {
    voiceProcessor.onaudioprocess = null;
    voiceProcessor.disconnect();
    voiceProcessor = null;
  }
  if (voiceAudioSource) {
    voiceAudioSource.disconnect();
    voiceAudioSource = null;
  }
  if (voiceStream) {
    voiceStream.getTracks().forEach((track) => track.stop());
    voiceStream = null;
  }
  if (voiceAudioContext) {
    const audioContext = voiceAudioContext;
    voiceAudioContext = null;
    audioContext.close().catch((error) => {
      console.error('No se pudo cerrar el audio del micrófono:', error);
    });
  }
  if (voiceSocket && voiceSocket.readyState < WebSocket.CLOSING) {
    const socket = voiceSocket;
    voiceSocket = null;
    socket.close();
  }

  wakeButton.hidden = false;
  wakeButton.disabled = false;
  wakeButton.textContent = 'Activar micrófono';
  stopWakeButton.hidden = true;
  stateLabel.textContent = 'ESCUCHA DETENIDA';
}

function startBrowserVoiceListener() {
  if (browserRecognition) {
    restartBrowserRecognition = true;
    try {
      browserRecognition.start();
    } catch (error) {
      if (error.name !== 'InvalidStateError') throw error;
    }
    return;
  }

  browserRecognition = new BrowserSpeechRecognition();
  browserRecognition.lang = 'es-ES';
  browserRecognition.continuous = true;
  browserRecognition.interimResults = true;
  browserRecognition.maxAlternatives = 3;
  restartBrowserRecognition = true;
  browserRecognition.onstart = () => {
    if (!voiceListenerEnabled) return;
    wakeButton.hidden = true;
    wakeButton.disabled = false;
    stopWakeButton.hidden = false;
    stateLabel.textContent = voiceSessionActive ? 'ESCUCHANDO' : 'DI SIRVIENTA';
    assistantReply.textContent =
      'La voz puede procesarse en línea. Las respuestas usan voz neural española.';
  };
  browserRecognition.onresult = (event) => {
    if (!voiceListenerEnabled || speakerIsSpeaking) return;
    for (let index = event.resultIndex; index < event.results.length; index += 1) {
      const alternatives = event.results[index];
      let bestAlternative = alternatives[0];
      for (let alternativeIndex = 1; alternativeIndex < alternatives.length; alternativeIndex += 1) {
        if (alternatives[alternativeIndex].confidence > bestAlternative.confidence) {
          bestAlternative = alternatives[alternativeIndex];
        }
      }
      const transcript = bestAlternative.transcript.trim();
      if (!transcript) continue;
      if (alternatives.isFinal) {
        handleBrowserVoiceTranscript(transcript);
      } else if (voiceSessionActive) {
        assistantReply.textContent = `Te oigo: ${transcript}…`;
      }
    }
  };
  browserRecognition.onerror = (event) => {
    if (!voiceListenerEnabled) return;
    const messages = {
      'not-allowed': 'Permite el acceso al micrófono en el navegador.',
      network: 'El reconocimiento en línea no está disponible. Comprueba la conexión a Internet.',
      'service-not-allowed': 'El navegador no permite usar su servicio de reconocimiento de voz.',
    };
    if (event.error === 'not-allowed' || event.error === 'service-not-allowed' || event.error === 'network') {
      restartBrowserRecognition = false;
      wakeButton.hidden = false;
      wakeButton.disabled = false;
      wakeButton.textContent = 'Reintentar voz';
      stopWakeButton.hidden = true;
      stateLabel.textContent = 'ERROR DE VOZ';
      assistantReply.textContent = messages[event.error] || 'No se pudo iniciar el reconocimiento de voz.';
      if (event.error === 'network') {
        browserRecognition = null;
        forceLocalVoiceRecognition = true;
        setTimeout(() => {
          startVoiceListener().catch((error) => console.error(error));
        }, 0);
      }
    } else if (event.error !== 'no-speech' && event.error !== 'aborted') {
      console.error('Speech recognition error:', event.error);
    }
  };
  browserRecognition.onend = () => {
    if (!restartBrowserRecognition || browserSpeechPaused) return;
    setTimeout(() => {
      try {
        browserRecognition.start();
      } catch (error) {
        if (error.name !== 'InvalidStateError') {
          console.error('No se pudo reanudar el reconocimiento:', error);
        }
      }
    }, 250);
  };

  try {
    browserRecognition.start();
  } catch (error) {
    browserRecognition = null;
    stateLabel.textContent = 'ERROR DE VOZ';
    assistantReply.textContent = error.message || 'No se pudo iniciar el reconocimiento de voz.';
    wakeButton.hidden = false;
    wakeButton.disabled = false;
    wakeButton.textContent = 'Reintentar voz';
    stopWakeButton.hidden = true;
    console.error(error);
  }
}

function handleBrowserVoiceTranscript(transcript) {
  const cleanedTranscript = transcript.trim().replace(/^[\s,.!?¿¡]+|[\s,.!?¿¡]+$/g, '');
  if (!cleanedTranscript) return;

  if (!voiceSessionActive) {
    const wakeWord = /\bsirvienta\b/i.exec(cleanedTranscript);
    if (!wakeWord) return;
    voiceSessionActive = true;
    const command = cleanedTranscript
      .slice(wakeWord.index + wakeWord[0].length)
      .replace(/^[\s,.!?¿¡]+/, '')
      .trim();
    stateLabel.textContent = command ? 'PROCESANDO' : 'ESCUCHANDO';
    assistantReply.textContent = command ? `Te oigo: ${command}` : 'Te escucho...';
    if (command) {
      sendBrowserVoiceCommand(command);
    } else if (voiceEnabled) {
      speakReply('Te escucho.');
    }
    return;
  }

  const normalizedTranscript = cleanedTranscript
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase();
  if (/\badios(?:\s*,?\s*sirvienta)?\b/.test(normalizedTranscript)) {
    voiceSessionActive = false;
    stopVoiceListener();
    assistantReply.textContent = 'Hasta luego.';
    appendConversationMessage('assistant', 'Hasta luego.');
    if (voiceEnabled) {
      speakReply('Hasta luego.');
    }
    return;
  }

  sendBrowserVoiceCommand(cleanedTranscript);
}

async function sendBrowserVoiceCommand(text) {
  stateLabel.textContent = 'PROCESANDO';
  assistantReply.textContent = `He oído: ${text}. Procesando...`;
  appendConversationMessage('user', text);
  try {
    const response = await fetch('/api/message', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text, conversation_id: conversationId, client: 'web' }),
    });
    const payload = await response.json();
    if (!response.ok || payload.error) {
      throw new Error(payload.error || 'No se pudo enviar el comando.');
    }

    lastReply = payload.text || 'No se obtuvo respuesta.';
    assistantReply.textContent = lastReply;
    appendConversationMessage('assistant', lastReply);
    repeatReply.disabled = !payload.text;
    stateLabel.textContent = payload.status === 'error' ? 'ERROR' : 'ESCUCHANDO';
    try {
      await loadStatus();
    } catch (error) {
      console.error(error);
    }
    if (voiceEnabled && payload.text) {
      speakReply(payload.text);
    }
  } catch (error) {
    const message = error.message || 'No se pudo conectar con Sirvienta.';
    assistantReply.textContent = message;
    appendConversationMessage('assistant', message);
    stateLabel.textContent = 'ERROR';
  }
}

function connectVoiceSocket() {
  if (!voiceListenerEnabled) return;
  if (voiceSocket && [WebSocket.CONNECTING, WebSocket.OPEN].includes(voiceSocket.readyState)) return;
  const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const socket = new WebSocket(`${scheme}//${window.location.host}/api/voice?conversation_id=${encodeURIComponent(conversationId)}`);
  voiceSocket = socket;
  socket.binaryType = 'arraybuffer';
  socket.onopen = () => {
    if (!voiceListenerEnabled) return;
    wakeButton.hidden = true;
    wakeButton.disabled = false;
    stopWakeButton.hidden = false;
    stateLabel.textContent = 'DI SIRVIENTA';
    assistantReply.textContent = 'Escucha activa. Di «Sirvienta» para empezar y «adiós» para detener la escucha.';
  };
  socket.onmessage = (event) => {
    if (!voiceListenerEnabled) return;
    const payload = JSON.parse(event.data);
    if (payload.type === 'ready') {
      stateLabel.textContent = 'DI SIRVIENTA';
      return;
    }
    if (payload.type === 'activated') {
      voiceSessionActive = true;
      stateLabel.textContent = 'ESCUCHANDO';
      assistantReply.textContent = 'Te escucho...';
      if (voiceEnabled) speakReply(payload.text);
      return;
    }
    if (payload.type === 'partial') {
      assistantReply.textContent = `Te oigo: ${payload.text}…`;
      stateLabel.textContent = 'ESCUCHANDO';
      return;
    }
    if (payload.type === 'response') {
      const wasVoiceSessionActive = voiceSessionActive;
      voiceSessionActive = payload.active;
      assistantReply.textContent = payload.text;
      lastReply = payload.text;
      repeatReply.disabled = !lastReply;
      stateLabel.textContent = payload.status === 'error' ? 'ERROR' : voiceSessionActive ? 'ESCUCHANDO' : 'DI SIRVIENTA';
      if (wasVoiceSessionActive && !voiceSessionActive) {
        stopVoiceListener();
      }
      if (voiceEnabled && lastReply) {
        speakReply(lastReply);
      }
      loadConversation().catch((error) => console.error(error));
      return;
    }
    if (payload.type === 'activity') {
      renderActivity(payload.activity);
      return;
    }
    if (payload.type === 'error') {
      assistantReply.textContent = payload.text;
      stateLabel.textContent = 'ERROR DE VOZ';
    }
  };
  socket.onerror = () => {
    assistantReply.textContent = 'No se pudo conectar con la escucha de voz. Reintentando...';
  };
  socket.onclose = () => {
    if (voiceSocket === socket) voiceSocket = null;
    if (!voiceListenerEnabled || voiceSocket) return;
    voiceSessionActive = false;
    stateLabel.textContent = 'RECONECTANDO VOZ';
    wakeButton.hidden = true;
    stopWakeButton.hidden = false;
    clearTimeout(voiceReconnectTimer);
    voiceReconnectTimer = setTimeout(() => {
      if (voiceListenerEnabled) connectVoiceSocket();
    }, 2000);
  }
}

wakeButton.addEventListener('click', startVoiceListener);
stopWakeButton.addEventListener('click', stopVoiceListener);

function resampleToPcm(audioBuffer, sourceSampleRate, targetSampleRate) {
  const channels = Array.from(
    { length: audioBuffer.numberOfChannels },
    (_, channel) => audioBuffer.getChannelData(channel)
  );
  const sampleCount = Math.floor(audioBuffer.length * targetSampleRate / sourceSampleRate);
  const pcm = new Int16Array(sampleCount);
  for (let index = 0; index < sampleCount; index += 1) {
    const sourcePosition = index * sourceSampleRate / targetSampleRate;
    const sourceIndex = Math.floor(sourcePosition);
    const fraction = sourcePosition - sourceIndex;
    let sample = 0;
    for (const channel of channels) {
      const first = channel[sourceIndex] || 0;
      const second = channel[sourceIndex + 1] || first;
      sample += first + (second - first) * fraction;
    }
    sample /= channels.length;
    const clipped = Math.max(-1, Math.min(1, sample));
    pcm[index] = clipped < 0 ? clipped * 0x8000 : clipped * 0x7fff;
  }
  return pcm.buffer;
}

function renderActivity(activity) {
  if (!activity || !activity.length) {
    const emptyLog = document.createElement('div');
    emptyLog.className = 'log-item';
    emptyLog.textContent = 'Todavía no hay actividad.';
    activityLogs.replaceChildren(emptyLog);
    return;
  }
  const logs = activity.map((item) => {
    const entry = document.createElement('div');
    entry.className = 'log-item';
    const time = item.timestamp ? new Date(item.timestamp).toLocaleTimeString() : '';
    entry.textContent = `${time} · ${item.tool || 'assistant'} · ${item.result || 'OK'}`;
    return entry;
  });
  activityLogs.replaceChildren(...logs);
}

statusButton.addEventListener('click', async () => {
  stateLabel.textContent = 'ACTUALIZANDO';
  try {
    await loadStatus();
    stateLabel.textContent = 'EN ESPERA';
  } catch (error) {
    statusText.textContent = 'OFFLINE';
    assistantReply.textContent = 'No se pudo conectar con Sirvienta.';
    stateLabel.textContent = 'SIN CONEXIÓN';
    console.error(error);
  }
});

updateClock();
setInterval(updateClock, 1000 * 30);
loadConversation().catch((error) => {
  conversationHistory.textContent = 'No se pudo cargar el historial de esta conversación.';
  console.error(error);
});
loadStatus().catch((error) => {
  statusText.textContent = 'OFFLINE';
  activityLogs.textContent = 'No se pudo cargar la actividad.';
  console.error(error);
});

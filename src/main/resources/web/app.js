let ws;
let audioContext;
let processor;
let gainNode;
let stream;
let isMuted = false;
let isDeafened = false;
let isPTT = false;
let isPTTActive = false;
let isWhisper = false;
let micVolume = 1.0;
let speakerVolume = 1.0;
let sessionUUID;
let sessionKey;
let playerUUID;
let playerName;

function connect() {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(`${protocol}//${location.host}/ws`);

    ws.onopen = () => {
        console.log('WebSocket connected');
    };

    ws.onmessage = (event) => {
        if (event.data instanceof Blob) {
            handleBinaryMessage(event.data);
        } else {
            handleTextMessage(JSON.parse(event.data));
        }
    };

    ws.onclose = () => {
        console.log('WebSocket closed');
        setTimeout(connect, 3000);
    };
}

function handleTextMessage(msg) {
    switch (msg.type) {
        case 'session':
            sessionUUID = msg.uuid;
            sessionKey = msg.key;
            playerUUID = msg.playerUUID;
            playerName = msg.playerName;
            document.getElementById('server-name').textContent = playerName;
            showScreen('voice-screen');
            startAudio();
            break;

        case 'auth_ok':
            addSystemMessage('Erfolgreich verbunden!');
            break;

        case 'auth_failed':
            showPairingError('Ungültiger Code oder Code abgelaufen');
            break;

        case 'chat':
            addChatMessage(msg.sender, msg.message);
            break;

        case 'emoji':
            addEmojiMessage(msg.sender, msg.emoji);
            break;

        case 'player_list':
            updatePlayerList(msg.players);
            break;

        case 'player_talk_start':
            markTalking(msg.uuid, true);
            break;

        case 'player_talk_stop':
            markTalking(msg.uuid, false);
            break;

        case 'error':
            addSystemMessage('Fehler: ' + msg.message);
            break;
    }
}

function handleBinaryMessage(blob) {
    blob.arrayBuffer().then(buffer => {
        if (!audioContext) return;
        const int16 = new Int16Array(buffer);
        const float32 = new Float32Array(int16.length);
        for (let i = 0; i < int16.length; i++) {
            float32[i] = int16[i] / 32768.0;
        }
        playAudio(float32);
    });
}

function playAudio(samples) {
    if (isDeafened) return;
    const buffer = audioContext.createBuffer(1, samples.length, 48000);
    buffer.getChannelData(0).set(samples);
    const source = audioContext.createBufferSource();
    source.buffer = buffer;
    const vol = audioContext.createGain();
    vol.gain.value = speakerVolume;
    source.connect(vol);
    vol.connect(audioContext.destination);
    source.start();
}

async function startAudio() {
    try {
        stream = await navigator.mediaDevices.getUserMedia({
            audio: {
                sampleRate: 48000,
                channelCount: 1,
                echoCancellation: false,
                noiseSuppression: false,
                autoGainControl: false
            }
        });

        audioContext = new AudioContext({ sampleRate: 48000 });
        const source = audioContext.createMediaStreamSource(stream);

        gainNode = audioContext.createGain();
        gainNode.gain.value = micVolume;
        source.connect(gainNode);

        if (audioContext.audioWorklet) {
            try {
                await audioContext.audioWorklet.addModule('processor.js');
                processor = new AudioWorkletNode(audioContext, 'pcm-processor');
                processor.port.onmessage = (e) => {
                    if (e.data === 'tick') {
                        sendAudioFrame();
                    }
                };
                gainNode.connect(processor);
                processor.connect(audioContext.destination);
            } catch (e) {
                console.warn('AudioWorklet not available, falling back to ScriptProcessor');
                startScriptProcessor();
            }
        } else {
            startScriptProcessor();
        }

        setupKeyboardListeners();
    } catch (e) {
        console.error('Audio error:', e);
        addSystemMessage('Mikrofonzugriff verweigert');
    }
}

function startScriptProcessor() {
    const bufferSize = 960;
    processor = audioContext.createScriptProcessor(bufferSize, 1, 1);
    gainNode.connect(processor);
    processor.connect(audioContext.destination);
    processor.onaudioprocess = (e) => {
        if (isMuted || (isPTT && !isPTTActive)) return;
        const input = e.inputBuffer.getChannelData(0);
        sendAudioSamples(input);
    };
}

function sendAudioFrame() {
    if (isMuted || (isPTT && !isPTTActive)) return;
    const input = gainNode.context.createBuffer(1, 960, 48000);
    // Placeholder - actual implementation reads from processor
}

function sendAudioSamples(samples) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    if (isMuted || (isPTT && !isPTTActive)) return;

    const targetSamples = isWhisper ? samples.length / 2 : samples.length;
    const int16 = new Int16Array(targetSamples);
    for (let i = 0; i < targetSamples; i++) {
        let s = isWhisper ? samples[i * 2] : samples[i];
        s = Math.max(-1, Math.min(1, s));
        int16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
    }

    const msg = {
        type: 'audio',
        whisper: isWhisper,
        samples: Array.from(int16)
    };
    ws.send(JSON.stringify(msg));
}

function pairWithCode() {
    const code = document.getElementById('code-input').value.trim();
    if (!/^\d{6}$/.test(code)) {
        showPairingError('Bitte genau 6 Ziffern eingeben');
        return;
    }

    document.getElementById('connect-btn').disabled = true;
    document.getElementById('connect-btn').textContent = 'Verbinden...';

    connect();

    setTimeout(() => {
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ type: 'pair', code: code }));
        }
    }, 500);
}

function toggleMute() {
    isMuted = !isMuted;
    const btn = document.getElementById('mute-btn');
    btn.textContent = isMuted ? '🔇 Stumm' : '🎤 An';
    btn.classList.toggle('active', isMuted);
    document.getElementById('mic-icon').classList.toggle('muted', isMuted);
}

function toggleDeafen() {
    isDeafened = !isDeafened;
    const btn = document.getElementById('deafen-btn');
    btn.textContent = isDeafened ? '🔇 Taub' : '🔊 An';
    btn.classList.toggle('active', isDeafened);
}

function togglePTT() {
    isPTT = !isPTT;
    const btn = document.getElementById('ptt-btn');
    btn.classList.toggle('active', isPTT);
    if (!isPTT) {
        isPTTActive = false;
        btn.classList.remove('ptt-active');
    }
    addSystemMessage(isPTT ? 'Push-to-Talk aktiviert (Leertaste zum Reden)' : 'Push-to-Talk deaktiviert');
}

function toggleWhisper() {
    isWhisper = !isWhisper;
    const btn = document.getElementById('whisper-btn');
    btn.classList.toggle('active', isWhisper);
    document.getElementById('mic-icon').classList.toggle('whisper', isWhisper);
    addSystemMessage(isWhisper ? 'Flüstern aktiviert - nur nah hörbar' : 'Flüstern deaktiviert');
}

function updateMicVolume(val) {
    micVolume = val / 100;
    document.getElementById('mic-volume-val').textContent = val + '%';
    if (gainNode) gainNode.gain.value = micVolume;
}

function updateSpeakerVolume(val) {
    speakerVolume = val / 100;
    document.getElementById('speaker-volume-val').textContent = val + '%';
}

function setupKeyboardListeners() {
    document.addEventListener('keydown', (e) => {
        if (e.code === 'Space' && isPTT && !e.repeat) {
            if (document.activeElement.tagName !== 'INPUT') {
                e.preventDefault();
                isPTTActive = true;
                document.getElementById('ptt-btn').classList.add('ptt-active');
                document.getElementById('mic-icon').classList.remove('muted');
            }
        }
    });

    document.addEventListener('keyup', (e) => {
        if (e.code === 'Space' && isPTT) {
            if (document.activeElement.tagName !== 'INPUT') {
                e.preventDefault();
                isPTTActive = false;
                document.getElementById('ptt-btn').classList.remove('ptt-active');
                if (isMuted) {
                    document.getElementById('mic-icon').classList.add('muted');
                }
            }
        }
    });

    document.addEventListener('keydown', (e) => {
        if (e.code === 'KeyV' && e.ctrlKey && e.shiftKey) {
            e.preventDefault();
            toggleMute();
        }
        if (e.code === 'KeyD' && e.ctrlKey && e.shiftKey) {
            e.preventDefault();
            toggleDeafen();
        }
    });
}

function sendEmoji(emoji) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    ws.send(JSON.stringify({ type: 'emoji', emoji: emoji }));
}

function sendChat() {
    const input = document.getElementById('chat-input');
    const msg = input.value.trim();
    if (!msg || !ws || ws.readyState !== WebSocket.OPEN) return;
    ws.send(JSON.stringify({ type: 'chat', message: msg }));
    input.value = '';
}

function addChatMessage(sender, message) {
    const div = document.getElementById('chat-messages');
    const msg = document.createElement('div');
    msg.className = 'msg';
    msg.innerHTML = `<span class="sender">${escapeHtml(sender)}</span>: ${escapeHtml(message)}`;
    div.appendChild(msg);
    div.scrollTop = div.scrollHeight;
}

function addEmojiMessage(sender, emoji) {
    const div = document.getElementById('chat-messages');
    const msg = document.createElement('div');
    msg.className = 'msg';
    msg.innerHTML = `<span class="sender">${escapeHtml(sender)}</span> <span class="emoji-msg">${emoji}</span>`;
    div.appendChild(msg);
    div.scrollTop = div.scrollHeight;
}

function addSystemMessage(text) {
    const div = document.getElementById('chat-messages');
    const msg = document.createElement('div');
    msg.className = 'msg';
    msg.innerHTML = `<span class="system">${escapeHtml(text)}</span>`;
    div.appendChild(msg);
    div.scrollTop = div.scrollHeight;
}

function updatePlayerList(players) {
    const ul = document.getElementById('player-list');
    ul.innerHTML = '';
    players.forEach(p => {
        const li = document.createElement('li');
        li.id = 'player-' + p.uuid;
        li.innerHTML = `<span class="dot"></span>${escapeHtml(p.name)}`;
        ul.appendChild(li);
    });
}

function markTalking(uuid, talking) {
    const li = document.getElementById('player-' + uuid);
    if (li) li.classList.toggle('talking', talking);
}

function showPairingError(msg) {
    const el = document.getElementById('pairing-error');
    el.textContent = msg;
    el.classList.remove('hidden');
    document.getElementById('connect-btn').disabled = false;
    document.getElementById('connect-btn').textContent = 'Verbinden';
}

function showScreen(id) {
    document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
    document.getElementById(id).classList.add('active');
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

document.getElementById('code-input').addEventListener('keypress', (e) => {
    if (e.key === 'Enter') pairWithCode();
    if (!/\d/.test(e.key) && e.key !== 'Enter') e.preventDefault();
});

let ws = null;
let audioContext = null;
let micStream = null;
let workletNode = null;
let isMuted = false;
let isDeafened = false;
let playbackQueue = [];
let isPlaying = false;
const SAMPLE_RATE = 48000;
const FRAME_SIZE = 960;

function pairWithCode() {
    const code = document.getElementById('code-input').value.trim();
    if (code.length !== 6 || !/^\d{6}$/.test(code)) {
        showError('Bitte einen gueltigen 6-stelligen Code eingeben.');
        return;
    }

    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = protocol + '//' + location.host + '/ws';

    ws = new WebSocket(wsUrl);
    ws.binaryType = 'arraybuffer';

    ws.onopen = function() {
        ws.send(JSON.stringify({type: 'pair', code: code}));
    };

    ws.onmessage = function(event) {
        if (typeof event.data === 'string') {
            handleTextMessage(JSON.parse(event.data));
        } else {
            handleAudioData(event.data);
        }
    };

    ws.onclose = function() {
        showError('Verbindung getrennt.');
        document.getElementById('pairing-screen').classList.add('active');
        document.getElementById('voice-screen').classList.remove('active');
    };

    ws.onerror = function() {
        showError('Verbindungsfehler.');
    };
}

function handleTextMessage(msg) {
    switch(msg.type) {
        case 'paired':
            document.getElementById('pairing-screen').classList.remove('active');
            document.getElementById('voice-screen').classList.add('active');
            document.getElementById('server-name').textContent = msg.playerName;
            addSystemMessage('Verbunden als ' + msg.playerName);
            startAudio();
            break;
        case 'error':
            showError(msg.message);
            break;
        case 'players':
            updatePlayerList(msg.players);
            break;
        case 'chat':
            addChatMessage(msg.player, msg.message);
            break;
    }
}

function handleAudioData(data) {
    if (isDeafened) return;

    const int16 = new Int16Array(data);
    const float32 = new Float32Array(int16.length);
    for (let i = 0; i < int16.length; i++) {
        float32[i] = int16[i] / 32768.0;
    }

    playAudioChunk(float32);
}

function playAudioChunk(samples) {
    if (!audioContext) return;

    const buffer = audioContext.createBuffer(1, samples.length, SAMPLE_RATE);
    buffer.getChannelData(0).set(samples);

    const source = audioContext.createBufferSource();
    source.buffer = buffer;
    source.connect(audioContext.destination);
    source.onended = function() {
        isPlaying = false;
        processPlaybackQueue();
    };

    isPlaying = true;
    source.start();
}

function processPlaybackQueue() {
    if (playbackQueue.length > 0 && !isPlaying) {
        const next = playbackQueue.shift();
        playAudioChunk(next);
    }
}

async function startAudio() {
    try {
        audioContext = new (window.AudioContext || window.webkitAudioContext)({
            sampleRate: SAMPLE_RATE
        });

        micStream = await navigator.mediaDevices.getUserMedia({
            audio: {
                sampleRate: SAMPLE_RATE,
                channelCount: 1,
                echoCancellation: true,
                noiseSuppression: true
            }
        });

        const source = audioContext.createMediaStreamSource(micStream);

        const processor = audioContext.createScriptProcessor(FRAME_SIZE, 1, 1);
        processor.onaudioprocess = function(event) {
            if (isMuted || !ws || ws.readyState !== WebSocket.OPEN) return;

            const inputBuffer = event.inputBuffer.getChannelData(0);
            let samples;
            if (inputBuffer.length === FRAME_SIZE) {
                samples = inputBuffer;
            } else {
                samples = resample(inputBuffer, audioContext.sampleRate, SAMPLE_RATE);
            }

            const int16 = new Int16Array(samples.length);
            for (let i = 0; i < samples.length; i++) {
                const s = Math.max(-1, Math.min(1, samples[i]));
                int16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
            }

            ws.send(int16.buffer);
        };

        source.connect(processor);
        processor.connect(audioContext.destination);

        addSystemMessage('Mikrofon aktiviert');
    } catch(err) {
        addSystemMessage('Mikrofon-Fehler: ' + err.message);
        console.error('Audio error:', err);
    }
}

function resample(buffer, fromRate, toRate) {
    if (fromRate === toRate) return buffer;
    const ratio = fromRate / toRate;
    const newLength = Math.round(buffer.length / ratio);
    const result = new Float32Array(newLength);
    for (let i = 0; i < newLength; i++) {
        const idx = i * ratio;
        const low = Math.floor(idx);
        const high = Math.min(low + 1, buffer.length - 1);
        const frac = idx - low;
        result[i] = buffer[low] * (1 - frac) + buffer[high] * frac;
    }
    return result;
}

function toggleMute() {
    isMuted = !isMuted;
    const btn = document.getElementById('mute-btn');
    const icon = document.getElementById('mic-icon');
    if (isMuted) {
        btn.textContent = '🔇 Aus';
        btn.classList.add('active');
        icon.classList.add('muted');
    } else {
        btn.textContent = '🎤 An';
        btn.classList.remove('active');
        icon.classList.remove('muted');
    }
}

function toggleDeafen() {
    isDeafened = !isDeafened;
    const btn = document.getElementById('deafen-btn');
    if (isDeafened) {
        btn.textContent = '🔇 Aus';
        btn.classList.add('active');
    } else {
        btn.textContent = '🔊 An';
        btn.classList.remove('active');
    }
}

function sendChat() {
    const input = document.getElementById('chat-input');
    const message = input.value.trim();
    if (!message || !ws || ws.readyState !== WebSocket.OPEN) return;

    ws.send(JSON.stringify({type: 'chat', message: message}));
    addChatMessage('Du', message);
    input.value = '';
}

function addChatMessage(sender, message) {
    const div = document.getElementById('chat-messages');
    const msg = document.createElement('div');
    msg.className = 'msg';
    msg.innerHTML = '<span class="sender">' + escapeHtml(sender) + ':</span> ' + escapeHtml(message);
    div.appendChild(msg);
    div.scrollTop = div.scrollHeight;
}

function addSystemMessage(text) {
    const div = document.getElementById('chat-messages');
    const msg = document.createElement('div');
    msg.className = 'msg';
    msg.innerHTML = '<span class="system">' + escapeHtml(text) + '</span>';
    div.appendChild(msg);
    div.scrollTop = div.scrollHeight;
}

function updatePlayerList(players) {
    const list = document.getElementById('player-list');
    list.innerHTML = '';
    players.forEach(function(name) {
        const li = document.createElement('li');
        li.innerHTML = '<span class="dot"></span> ' + escapeHtml(name);
        list.appendChild(li);
    });
}

function showError(msg) {
    const el = document.getElementById('pairing-error');
    el.textContent = msg;
    el.classList.remove('hidden');
    setTimeout(function() { el.classList.add('hidden'); }, 5000);
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

document.getElementById('code-input').addEventListener('keypress', function(e) {
    if (e.key === 'Enter') pairWithCode();
});

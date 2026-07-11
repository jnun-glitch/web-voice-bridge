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
let playerUUID;
let playerName;
let pendingPairCode = null;

let mapPlayers = [];
let mapScale = 2;
let mapOffsetX = 0;
let mapOffsetY = 0;
let mapDragging = false;
let mapLastMouse = { x: 0, y: 0 };

function connect(pairCode) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        if (pairCode) {
            ws.send(JSON.stringify({ type: 'pair', code: pairCode }));
        }
        return;
    }

    pendingPairCode = pairCode || null;

    let serverHost = location.host;
    const serverInput = document.getElementById('server-input');
    if (serverInput && serverInput.value.trim()) {
        serverHost = serverInput.value.trim();
    }

    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(`${protocol}//${serverHost}/ws`);

    ws.onopen = () => {
        console.log('WebSocket connected');
        if (pendingPairCode) {
            ws.send(JSON.stringify({ type: 'pair', code: pendingPairCode }));
            pendingPairCode = null;
        }
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
        setTimeout(() => connect(null), 3000);
    };
}

function handleTextMessage(msg) {
    switch (msg.type) {
        case 'session':
            sessionUUID = msg.uuid;
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

        case 'world_map':
            mapPlayers = msg.players || [];
            drawMap();
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
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
            addSystemMessage('Mikrofon nicht verfügbar - HTTPS erforderlich für Nicht-localhost Zugriff');
            return;
        }

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
                    if (e.data instanceof Float32Array) {
                        sendAudioSamples(e.data);
                    }
                };
                gainNode.connect(processor);
            } catch (e) {
                console.warn('AudioWorklet not available, falling back to ScriptProcessor');
                startScriptProcessor();
            }
        } else {
            startScriptProcessor();
        }

        setupKeyboardListeners();
        addSystemMessage('Mikrofon aktiviert');
    } catch (e) {
        console.error('Audio error:', e);
        if (location.protocol !== 'https:' && location.hostname !== 'localhost' && location.hostname !== '127.0.0.1') {
            addSystemMessage('Mikrofon verweigert: Öffne die Seite über HTTPS oder localhost');
        } else {
            addSystemMessage('Mikrofonzugriff verweigert - bitte im Browser erlauben');
        }
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

function sendAudioSamples(samples) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    if (isMuted || (isPTT && !isPTTActive)) return;

    let int16;
    if (isWhisper) {
        int16 = new Int16Array(960);
        const ratio = samples.length / 960;
        for (let i = 0; i < 960; i++) {
            const idx = i * ratio;
            const lo = Math.floor(idx);
            const hi = Math.min(lo + 1, samples.length - 1);
            const frac = idx - lo;
            let s = samples[lo] * (1 - frac) + samples[hi] * frac;
            s = Math.max(-1, Math.min(1, s));
            int16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
        }
    } else {
        int16 = new Int16Array(samples.length);
        for (let i = 0; i < samples.length; i++) {
            let s = Math.max(-1, Math.min(1, samples[i]));
            int16[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
        }
    }

    const buffer = new Uint8Array(1 + int16.length * 2);
    buffer[0] = isWhisper ? 1 : 0;
    const view = new DataView(buffer.buffer, 1);
    for (let i = 0; i < int16.length; i++) {
        view.setInt16(i * 2, int16[i], true);
    }
    ws.send(buffer);
}

function pairWithCode() {
    const code = document.getElementById('code-input').value.trim();
    if (!/^\d{6}$/.test(code)) {
        showPairingError('Bitte genau 6 Ziffern eingeben');
        return;
    }

    document.getElementById('connect-btn').disabled = true;
    document.getElementById('connect-btn').textContent = 'Verbinden...';

    connect(code);
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

function switchTab(tab) {
    document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
    document.getElementById('tab-' + tab).classList.add('active');
    document.getElementById('tab-' + tab + '-content').classList.add('active');
    if (tab === 'map') {
        requestAnimationFrame(drawMap);
        initMapEvents();
    }
}

let mapInited = false;
function initMapEvents() {
    if (mapInited) return;
    mapInited = true;
    const canvas = document.getElementById('world-map');
    canvas.addEventListener('mousedown', (e) => {
        mapDragging = true;
        mapLastMouse = { x: e.clientX, y: e.clientY };
    });
    canvas.addEventListener('mousemove', (e) => {
        if (!mapDragging) return;
        mapOffsetX += e.clientX - mapLastMouse.x;
        mapOffsetY += e.clientY - mapLastMouse.y;
        mapLastMouse = { x: e.clientX, y: e.clientY };
        drawMap();
    });
    canvas.addEventListener('mouseup', () => mapDragging = false);
    canvas.addEventListener('mouseleave', () => mapDragging = false);
    canvas.addEventListener('wheel', (e) => {
        e.preventDefault();
        const factor = e.deltaY > 0 ? 0.9 : 1.1;
        mapScale = Math.max(0.2, Math.min(20, mapScale * factor));
        drawMap();
    });
}

function mapZoom(factor) {
    mapScale = Math.max(0.2, Math.min(20, mapScale * factor));
    drawMap();
}

function mapCenter() {
    if (mapPlayers.length === 0) return;
    const me = mapPlayers.find(p => p.uuid === playerUUID);
    const target = me || mapPlayers[0];
    const canvas = document.getElementById('world-map');
    mapOffsetX = canvas.width / 2 - target.x * mapScale;
    mapOffsetY = canvas.height / 2 - target.z * mapScale;
    drawMap();
}

function drawMap() {
    const canvas = document.getElementById('world-map');
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    canvas.width = canvas.parentElement.clientWidth;
    canvas.height = canvas.parentElement.clientHeight;
    const w = canvas.width;
    const h = canvas.height;

    ctx.fillStyle = '#0a0a1a';
    ctx.fillRect(0, 0, w, h);

    ctx.save();
    ctx.translate(w / 2 + mapOffsetX, h / 2 + mapOffsetY);

    const gridSize = 16 * mapScale;
    if (gridSize > 3) {
        ctx.strokeStyle = 'rgba(255,255,255,0.06)';
        ctx.lineWidth = 1;
        const startX = Math.floor(-w / 2 / gridSize - Math.abs(mapOffsetX) / gridSize) * gridSize;
        const startY = Math.floor(-h / 2 / gridSize - Math.abs(mapOffsetY) / gridSize) * gridSize;
        for (let x = startX; x < w; x += gridSize) {
            ctx.beginPath();
            ctx.moveTo(x, -h);
            ctx.lineTo(x, h);
            ctx.stroke();
        }
        for (let y = startY; y < h; y += gridSize) {
            ctx.beginPath();
            ctx.moveTo(-w, y);
            ctx.lineTo(w, y);
            ctx.stroke();
        }
    }

    ctx.strokeStyle = 'rgba(255,255,255,0.15)';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(0, -h);
    ctx.lineTo(0, h);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-w, 0);
    ctx.lineTo(w, 0);
    ctx.stroke();

    ctx.fillStyle = 'rgba(255,255,255,0.25)';
    ctx.font = '10px monospace';
    ctx.textAlign = 'center';
    for (let i = -500; i <= 500; i += 100) {
        if (i === 0) continue;
        const px = i * mapScale;
        if (px > -w && px < w) {
            ctx.fillText(i, px, 14);
        }
        const py = i * mapScale;
        if (py > -h && py < h) {
            ctx.fillText(i, -14, py + 4);
        }
    }

    const playerColors = [
        '#4ade80', '#60a5fa', '#f472b6', '#fbbf24',
        '#a78bfa', '#34d399', '#fb923c', '#f87171',
        '#22d3ee', '#c084fc'
    ];

    mapPlayers.forEach((p, i) => {
        const px = p.x * mapScale;
        const pz = p.z * mapScale;
        const color = p.uuid === playerUUID ? '#ffffff' : playerColors[i % playerColors.length];

        const yaw = (p.yaw || 0) * Math.PI / 180;
        ctx.save();
        ctx.translate(px, pz);
        ctx.rotate(yaw);
        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.moveTo(0, -8);
        ctx.lineTo(-5, 6);
        ctx.lineTo(5, 6);
        ctx.closePath();
        ctx.fill();
        ctx.restore();

        ctx.fillStyle = color;
        ctx.font = p.uuid === playerUUID ? 'bold 13px sans-serif' : '12px sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText(p.name, px, pz + 20);

        if (p.uuid === playerUUID) {
            ctx.strokeStyle = 'rgba(255,255,255,0.2)';
            ctx.lineWidth = 1;
            ctx.beginPath();
            ctx.arc(px, pz, 12, 0, Math.PI * 2);
            ctx.stroke();
        }
    });

    ctx.restore();

    const me = mapPlayers.find(p => p.uuid === playerUUID);
    const coordsEl = document.getElementById('map-coords');
    if (me && coordsEl) {
        coordsEl.textContent = `X: ${Math.round(me.x)} Y: ${Math.round(me.y)} Z: ${Math.round(me.z)}`;
    }
}

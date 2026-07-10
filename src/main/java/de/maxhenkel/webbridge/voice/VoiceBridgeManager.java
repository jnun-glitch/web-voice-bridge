package de.maxhenkel.webbridge.voice;

import de.maxhenkel.webbridge.WebVoiceBridgePlugin;
import de.maxhenkel.webbridge.server.WebSocketHandler;
import de.maxhenkel.voicechat.api.Entity;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.audiolistener.PlayerAudioListener;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.LocationalSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.packets.SoundPacket;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VoiceBridgeManager {

    private final WebVoiceBridgePlugin plugin;
    private final VoicechatServerApi api;
    private final Map<UUID, BrowserSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> sessionUUIDs = new ConcurrentHashMap<>();

    public static final double WHISPER_DISTANCE = 5.0;
    public static final double NORMAL_DISTANCE = 48.0;

    public VoiceBridgeManager(WebVoiceBridgePlugin plugin, VoicechatApi api) {
        this.plugin = plugin;
        this.api = (VoicechatServerApi) api;
    }

    public String getSessionUUID(UUID playerUuid) {
        UUID uuid = sessionUUIDs.get(playerUuid);
        return uuid != null ? uuid.toString() : null;
    }

    public void startSession(UUID playerUuid, WebSocketHandler handler) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) {
            handler.sendText("{\"type\":\"error\",\"message\":\"Player not online\"}");
            return;
        }

        VoicechatConnection connection = api.getConnectionOf(playerUuid);
        if (connection == null) {
            handler.sendText("{\"type\":\"error\",\"message\":\"Player has no voice chat connection\"}");
            return;
        }

        UUID sessionUUID = UUID.randomUUID();
        sessionUUIDs.put(playerUuid, sessionUUID);

        OpusDecoder decoder = api.createDecoder();
        OpusEncoder encoder = api.createEncoder();

        PlayerAudioListener listener = api.playerAudioListenerBuilder()
                .setPlayer(playerUuid)
                .setPacketListener(packet -> handleSoundPacketForBrowser(playerUuid, packet))
                .build();
        api.registerAudioListener(listener);

        ServerPlayer serverPlayer = api.fromServerPlayer(player.getHandle());
        Entity entity = serverPlayer.getEntity();
        UUID channelUuid = UUID.randomUUID();
        EntityAudioChannel channel = api.createEntityAudioChannel(channelUuid, entity);

        BrowserSession session = new BrowserSession(
                playerUuid, handler, decoder, encoder, listener, channel, channelUuid
        );
        sessions.put(playerUuid, session);

        sendPlayerList(handler);
        plugin.getLogger().info("Browser session started for " + player.getName());
    }

    public void stopSession(UUID playerUuid) {
        BrowserSession session = sessions.remove(playerUuid);
        sessionUUIDs.remove(playerUuid);
        if (session == null) return;

        api.unregisterAudioListener(session.listener());
        if (session.channel() != null && !session.channel().isClosed()) {
            session.channel().close();
        }
        session.decoder().close();
        session.encoder().close();

        Player player = Bukkit.getPlayer(playerUuid);
        String name = player != null ? player.getName() : "Unknown";
        plugin.getLogger().info("Browser session stopped for " + name);
    }

    public void onBrowserAudio(UUID playerUuid, short[] pcmData, boolean whisper) {
        BrowserSession session = sessions.get(playerUuid);
        if (session == null) return;
        if (session.channel().isClosed()) return;

        byte[] opusData = session.encoder().encode(pcmData);
        session.channel().send(opusData);
        session.channel().flush();
    }

    public void onBrowserAudio(UUID playerUuid, short[] pcmData) {
        onBrowserAudio(playerUuid, pcmData, false);
    }

    private void handleSoundPacketForBrowser(UUID playerUuid, SoundPacket<?> packet) {
        BrowserSession session = sessions.get(playerUuid);
        if (session == null) return;

        if (packet.getChannelId().equals(session.channelUuid())) return;

        try {
            byte[] opusData = packet.getOpusEncodedData();
            short[] pcmData = session.decoder().decode(opusData);
            byte[] pcmBytes = shortsToBytes(pcmData);
            session.handler().sendBinary(pcmBytes);
        } catch (Exception e) {
            // Skip corrupted packets silently
        }
    }

    public void onMicrophonePacket(MicrophonePacketEvent event) {
    }

    public void onEntitySoundPacket(EntitySoundPacketEvent event) {
    }

    public void onLocationalSoundPacket(LocationalSoundPacketEvent event) {
    }

    public void onStaticSoundPacket(StaticSoundPacketEvent event) {
    }

    public void onPlayerConnected(PlayerConnectedEvent event) {
        UUID uuid = event.getPlayerUuid();
        if (sessions.containsKey(uuid)) return;
        broadcastPlayerLists();
    }

    public void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        UUID uuid = event.getPlayerUuid();
        BrowserSession session = sessions.get(uuid);
        if (session != null) {
            session.handler().sendText("{\"type\":\"error\",\"message\":\"Minecraft player disconnected\"}");
            session.handler().close();
            stopSession(uuid);
        }
        broadcastPlayerLists();
    }

    public void onPlayerQuit(UUID playerUuid) {
        stopSession(playerUuid);
        broadcastPlayerLists();
    }

    public void sendPlayerList(WebSocketHandler handler) {
        List<Map<String, String>> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<String, String> p = new HashMap<>();
            p.put("uuid", player.getUniqueId().toString());
            p.put("name", player.getName());
            players.add(p);
        }

        StringBuilder json = new StringBuilder("{\"type\":\"player_list\",\"players\":[");
        for (int i = 0; i < players.size(); i++) {
            Map<String, String> p = players.get(i);
            if (i > 0) json.append(",");
            json.append("{\"uuid\":\"").append(p.get("uuid")).append("\",\"name\":\"").append(p.get("name")).append("\"}");
        }
        json.append("]}");
        handler.sendText(json.toString());
    }

    private void broadcastPlayerLists() {
        List<Map<String, String>> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<String, String> p = new HashMap<>();
            p.put("uuid", player.getUniqueId().toString());
            p.put("name", player.getName());
            players.add(p);
        }

        StringBuilder json = new StringBuilder("{\"type\":\"player_list\",\"players\":[");
        for (int i = 0; i < players.size(); i++) {
            Map<String, String> p = players.get(i);
            if (i > 0) json.append(",");
            json.append("{\"uuid\":\"").append(p.get("uuid")).append("\",\"name\":\"").append(p.get("name")).append("\"}");
        }
        json.append("]}");
        String msg = json.toString();

        for (BrowserSession session : sessions.values()) {
            session.handler().sendText(msg);
        }
    }

    public void sendChatToMinecraft(UUID playerUuid, String message) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.broadcastMessage("§7[Web] §f" + player.getName() + ": " + message);
        });
    }

    public void sendEmojiToMinecraft(UUID playerUuid, String emoji) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.broadcastMessage("§7[Web] §f" + player.getName() + " " + emoji);
        });

        for (BrowserSession session : sessions.values()) {
            if (!session.playerUuid().equals(playerUuid)) {
                String name = player != null ? player.getName() : "Unknown";
                session.handler().sendText("{\"type\":\"emoji\",\"sender\":\"" + name + "\",\"emoji\":\"" + emoji + "\"}");
            }
        }
    }

    public boolean hasSession(UUID playerUuid) {
        return sessions.containsKey(playerUuid);
    }

    public void shutdown() {
        for (UUID uuid : new ArrayList<>(sessions.keySet())) {
            stopSession(uuid);
        }
        sessions.clear();
        sessionUUIDs.clear();
    }

    private byte[] shortsToBytes(short[] shorts) {
        byte[] bytes = new byte[shorts.length * 2];
        for (int i = 0; i < shorts.length; i++) {
            bytes[i * 2] = (byte) (shorts[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((shorts[i] >> 8) & 0xFF);
        }
        return bytes;
    }

    public record BrowserSession(
            UUID playerUuid,
            WebSocketHandler handler,
            OpusDecoder decoder,
            OpusEncoder encoder,
            PlayerAudioListener listener,
            EntityAudioChannel channel,
            UUID channelUuid
    ) {}
}

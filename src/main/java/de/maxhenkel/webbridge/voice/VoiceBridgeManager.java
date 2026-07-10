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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VoiceBridgeManager {

    private final WebVoiceBridgePlugin plugin;
    private final VoicechatServerApi api;
    private final Map<UUID, BrowserSession> sessions = new ConcurrentHashMap<>();

    public VoiceBridgeManager(WebVoiceBridgePlugin plugin, VoicechatApi api) {
        this.plugin = plugin;
        this.api = (VoicechatServerApi) api;
    }

    public void startSession(UUID playerUuid, WebSocketHandler handler) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) return;

        VoicechatConnection connection = api.getConnectionOf(playerUuid);
        if (connection == null) {
            handler.sendText("{\"type\":\"error\",\"message\":\"Player has no voice chat connection\"}");
            return;
        }

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

        handler.sendText("{\"type\":\"paired\",\"playerName\":\"" + player.getName() + "\"}");
        sendPlayerList(handler);

        plugin.getLogger().info("Browser session started for " + player.getName());
    }

    public void stopSession(UUID playerUuid) {
        BrowserSession session = sessions.remove(playerUuid);
        if (session == null) return;

        api.unregisterAudioListener(session.listener());
        if (session.channel() != null) {
            session.channel().close();
        }
        session.decoder().close();
        session.encoder().close();

        plugin.getLogger().info("Browser session stopped for " + Bukkit.getOfflinePlayer(playerUuid).getName());
    }

    public void onBrowserAudio(UUID playerUuid, short[] pcmData) {
        BrowserSession session = sessions.get(playerUuid);
        if (session == null) return;
        if (!session.channel().isClosed()) {
            byte[] opusData = session.encoder().encode(pcmData);
            session.channel().send(opusData);
            session.channel().flush();
        }
    }

    private void handleSoundPacketForBrowser(UUID playerUuid, SoundPacket<?> packet) {
        BrowserSession session = sessions.get(playerUuid);
        if (session == null) return;

        // Prevent echo - skip audio from our own channel
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
        // Not needed for current architecture
    }

    public void onEntitySoundPacket(EntitySoundPacketEvent event) {
        // Sound packets are intercepted via PlayerAudioListener
    }

    public void onLocationalSoundPacket(LocationalSoundPacketEvent event) {
        // Sound packets are intercepted via PlayerAudioListener
    }

    public void onStaticSoundPacket(StaticSoundPacketEvent event) {
        // Sound packets are intercepted via PlayerAudioListener
    }

    public void onPlayerConnected(PlayerConnectedEvent event) {
        // Update player list for all connected browsers
    }

    public void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        UUID uuid = event.getPlayerUuid();
        if (sessions.containsKey(uuid)) {
            BrowserSession session = sessions.get(uuid);
            if (session != null) {
                session.handler().sendText("{\"type\":\"error\",\"message\":\"Minecraft player disconnected\"}");
                session.handler().close();
            }
            stopSession(uuid);
        }
    }

    public void onPlayerQuit(UUID playerUuid) {
        stopSession(playerUuid);
    }

    public void sendPlayerList(WebSocketHandler handler) {
        StringBuilder json = new StringBuilder("{\"type\":\"players\",\"players\":[");
        boolean first = true;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!first) json.append(",");
            json.append("\"").append(player.getName()).append("\"");
            first = false;
        }
        json.append("]}");
        handler.sendText(json.toString());
    }

    public void sendChatToMinecraft(UUID playerUuid, String message) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.broadcastMessage("§7[Web] §f" + player.getName() + ": " + message);
        });
    }

    public boolean hasSession(UUID playerUuid) {
        return sessions.containsKey(playerUuid);
    }

    public void shutdown() {
        for (UUID uuid : sessions.keySet()) {
            stopSession(uuid);
        }
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

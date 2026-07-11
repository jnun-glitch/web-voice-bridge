package de.maxhenkel.webbridge.voice;

import de.maxhenkel.webbridge.WebVoiceBridgePlugin;
import de.maxhenkel.webbridge.server.WebSocketHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VoiceBridgeManager {

    private final WebVoiceBridgePlugin plugin;
    private final Object vcApi;
    private final ClassLoader vcCL;
    private final Map<UUID, BrowserSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> sessionUUIDs = new ConcurrentHashMap<>();

    public static final double WHISPER_DISTANCE = 5.0;
    public static final double NORMAL_DISTANCE = 48.0;

    public VoiceBridgeManager(WebVoiceBridgePlugin plugin, Object vcApi) {
        this.plugin = plugin;
        this.vcApi = vcApi;
        this.vcCL = vcApi.getClass().getClassLoader();
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

        try {
            Method getConnectionOf = vcApi.getClass().getMethod("getConnectionOf", UUID.class);
            Object connection = getConnectionOf.invoke(vcApi, playerUuid);
            if (connection == null) {
                handler.sendText("{\"type\":\"error\",\"message\":\"Player has no voice chat connection\"}");
                return;
            }

            stopSession(playerUuid);

            UUID sessionUUID = UUID.randomUUID();
            sessionUUIDs.put(playerUuid, sessionUUID);

            Method createDecoder = vcApi.getClass().getMethod("createDecoder");
            Object decoder = createDecoder.invoke(vcApi);

            Method createEncoder = vcApi.getClass().getMethod("createEncoder");
            Object encoder = createEncoder.invoke(vcApi);

            Class<?> builderClass = Class.forName("de.maxhenkel.voicechat.api.audiolistener.PlayerAudioListener$Builder", true, vcCL);
            Class<?> consumerClass = Class.forName("java.util.function.Consumer", true, vcCL);

            java.util.function.Consumer<Object> packetConsumer = packet -> handleSoundPacketForBrowser(playerUuid, packet);

            Method playerAudioListenerBuilder = vcApi.getClass().getMethod("playerAudioListenerBuilder");
            Object builder = playerAudioListenerBuilder.invoke(vcApi);
            Method setPlayer = builderClass.getMethod("setPlayer", UUID.class);
            setPlayer.invoke(builder, playerUuid);
            Method setPacketListener = builderClass.getMethod("setPacketListener", consumerClass);
            setPacketListener.invoke(builder, packetConsumer);
            Method build = builderClass.getMethod("build");
            Object listener = build.invoke(builder);

            Method registerAudioListener = vcApi.getClass().getMethod("registerAudioListener",
                    Class.forName("de.maxhenkel.voicechat.api.audiolistener.AudioListener", true, vcCL));
            registerAudioListener.invoke(vcApi, listener);

            Class<?> entityClass = Class.forName("de.maxhenkel.voicechat.api.Entity", true, vcCL);
            Method fromEntity = vcApi.getClass().getMethod("fromEntity", org.bukkit.entity.Player.class);
            Object entity = fromEntity.invoke(vcApi, player);

            UUID channelUuid = UUID.randomUUID();
            Method createChannel = vcApi.getClass().getMethod("createEntityAudioChannel", UUID.class, entityClass);
            Object channel = createChannel.invoke(vcApi, channelUuid, entity);

            Map<UUID, Object> senderDecoders = new ConcurrentHashMap<>();

            BrowserSession session = new BrowserSession(
                    playerUuid, handler, decoder, encoder, listener, channel, channelUuid, senderDecoders
            );
            sessions.put(playerUuid, session);

            sendPlayerList(handler);
            plugin.getLogger().info("Browser session started for " + player.getName());

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to start session", e);
            handler.sendText("{\"type\":\"error\",\"message\":\"Internal server error\"}");
        }
    }

    public void stopSession(UUID playerUuid) {
        BrowserSession session = sessions.remove(playerUuid);
        sessionUUIDs.remove(playerUuid);
        if (session == null) return;

        try {
            Class<?> audioListenerClass = Class.forName("de.maxhenkel.voicechat.api.audiolistener.AudioListener", true, vcCL);
            Method unregisterAudioListener = vcApi.getClass().getMethod("unregisterAudioListener", audioListenerClass);
            unregisterAudioListener.invoke(vcApi, session.listener());

            if (session.channel() != null) {
                Method isClosed = session.channel().getClass().getMethod("isClosed");
                if (!(Boolean) isClosed.invoke(session.channel())) {
                    Method flush = session.channel().getClass().getMethod("flush");
                    flush.invoke(session.channel());
                }
            }

            Method closeDecoder = session.decoder().getClass().getMethod("close");
            closeDecoder.invoke(session.decoder());

            Method closeEncoder = session.encoder().getClass().getMethod("close");
            closeEncoder.invoke(session.encoder());

            for (Object d : session.senderDecoders().values()) {
                Method closeSD = d.getClass().getMethod("close");
                closeSD.invoke(d);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error stopping session", e);
        }

        Player player = Bukkit.getPlayer(playerUuid);
        String name = player != null ? player.getName() : "Unknown";
        plugin.getLogger().info("Browser session stopped for " + name);
    }

    public void onBrowserAudio(UUID playerUuid, short[] pcmData, boolean whisper) {
        BrowserSession session = sessions.get(playerUuid);
        if (session == null) return;

        try {
            Method isClosed = session.channel().getClass().getMethod("isClosed");
            if ((Boolean) isClosed.invoke(session.channel())) return;

            Method setDistance = session.channel().getClass().getMethod("setDistance", float.class);
            setDistance.invoke(session.channel(), (float) (whisper ? WHISPER_DISTANCE : NORMAL_DISTANCE));

            Method encode = session.encoder().getClass().getMethod("encode", short[].class);
            byte[] opusData = (byte[]) encode.invoke(session.encoder(), pcmData);

            Method send = session.channel().getClass().getMethod("send", byte[].class);
            send.invoke(session.channel(), opusData);

            Method flush = session.channel().getClass().getMethod("flush");
            flush.invoke(session.channel());
        } catch (Exception e) {
            // Ignore audio errors silently
        }
    }

    public void onBrowserAudio(UUID playerUuid, short[] pcmData) {
        onBrowserAudio(playerUuid, pcmData, false);
    }

    private void handleSoundPacketForBrowser(UUID playerUuid, Object packet) {
        BrowserSession session = sessions.get(playerUuid);
        if (session == null) return;

        try {
            Method getChannelId = packet.getClass().getMethod("getChannelId");
            UUID channelId = (UUID) getChannelId.invoke(packet);
            if (channelId.equals(session.channelUuid())) return;

            Method getSender = packet.getClass().getMethod("getSender");
            UUID senderId = (UUID) getSender.invoke(packet);

            Object decoder = session.senderDecoders().computeIfAbsent(senderId, k -> {
                try {
                    Method createDecoder = vcApi.getClass().getMethod("createDecoder");
                    return createDecoder.invoke(vcApi);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Method getOpusEncodedData = packet.getClass().getMethod("getOpusEncodedData");
            byte[] opusData = (byte[]) getOpusEncodedData.invoke(packet);

            Method decode = decoder.getClass().getMethod("decode", byte[].class);
            short[] pcmData = (short[]) decode.invoke(decoder, opusData);

            byte[] pcmBytes = shortsToBytes(pcmData);
            session.handler().sendBinary(pcmBytes);
        } catch (Exception e) {
            // Skip corrupted packets silently
        }
    }

    public void onMicrophonePacket(Object event) {
    }

    public void onEntitySoundPacket(Object event) {
    }

    public void onLocationalSoundPacket(Object event) {
    }

    public void onStaticSoundPacket(Object event) {
    }

    public void onPlayerConnected(Object event) {
        broadcastPlayerLists();
    }

    public void onPlayerDisconnected(Object event) {
        try {
            Method getPlayerUuid = event.getClass().getMethod("getPlayerUuid");
            UUID uuid = (UUID) getPlayerUuid.invoke(event);

            BrowserSession session = sessions.get(uuid);
            if (session != null) {
                session.handler().sendText("{\"type\":\"error\",\"message\":\"Minecraft player disconnected\"}");
                session.handler().close();
                stopSession(uuid);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error handling player disconnect", e);
        }
        broadcastPlayerLists();
    }

    public void onPlayerQuit(UUID playerUuid) {
        stopSession(playerUuid);
        broadcastPlayerLists();
    }

    public void broadcastWorldMap() {
        if (sessions.isEmpty()) return;

        StringBuilder json = new StringBuilder("{\"type\":\"world_map\",\"players\":[");
        boolean first = true;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!first) json.append(",");
            first = false;
            org.bukkit.Location loc = player.getLocation();
            json.append("{\"uuid\":\"").append(player.getUniqueId())
                    .append("\",\"name\":\"").append(WebSocketHandler.escapeJson(player.getName()))
                    .append("\",\"x\":").append(String.format("%.1f", loc.getX()))
                    .append(",\"y\":").append(String.format("%.1f", loc.getY()))
                    .append(",\"z\":").append(String.format("%.1f", loc.getZ()))
                    .append(",\"yaw\":").append(String.format("%.1f", loc.getYaw()))
                    .append(",\"pitch\":").append(String.format("%.1f", loc.getPitch()))
                    .append(",\"world\":\"").append(WebSocketHandler.escapeJson(loc.getWorld().getName()))
                    .append("\"}");
        }
        json.append("]}");
        String msg = json.toString();

        for (BrowserSession session : sessions.values()) {
            session.handler().sendText(msg);
        }
    }

    public void sendPlayerList(WebSocketHandler handler) {
        StringBuilder json = new StringBuilder("{\"type\":\"player_list\",\"players\":[");
        boolean first = true;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!first) json.append(",");
            first = false;
            json.append("{\"uuid\":\"").append(player.getUniqueId().toString()).append("\",\"name\":\"")
                    .append(WebSocketHandler.escapeJson(player.getName())).append("\"}");
        }
        json.append("]}");
        handler.sendText(json.toString());
    }

    private void broadcastPlayerLists() {
        StringBuilder json = new StringBuilder("{\"type\":\"player_list\",\"players\":[");
        boolean first = true;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!first) json.append(",");
            first = false;
            json.append("{\"uuid\":\"").append(player.getUniqueId().toString()).append("\",\"name\":\"")
                    .append(WebSocketHandler.escapeJson(player.getName())).append("\"}");
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

        String safeName = player.getName();
        String safeMessage = message;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.broadcastMessage("§7[Web] §f" + safeName + ": " + safeMessage);
        });
    }

    public void sendEmojiToMinecraft(UUID playerUuid, String emoji) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) return;

        String safeName = player.getName();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.broadcastMessage("§7[Web] §f" + safeName + " " + emoji);
        });

        String emojiJson = "{\"type\":\"emoji\",\"sender\":\"" + WebSocketHandler.escapeJson(safeName)
                + "\",\"emoji\":\"" + WebSocketHandler.escapeJson(emoji) + "\"}";
        for (BrowserSession session : sessions.values()) {
            if (!session.playerUuid().equals(playerUuid)) {
                session.handler().sendText(emojiJson);
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
            Object decoder,
            Object encoder,
            Object listener,
            Object channel,
            UUID channelUuid,
            Map<UUID, Object> senderDecoders
    ) {}
}

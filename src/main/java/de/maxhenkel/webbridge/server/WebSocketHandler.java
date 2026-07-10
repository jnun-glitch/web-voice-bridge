package de.maxhenkel.webbridge.server;

import de.maxhenkel.webbridge.WebVoiceBridgePlugin;
import de.maxhenkel.webbridge.voice.VoiceBridgeManager;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.util.UUID;

public class WebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private final WebVoiceBridgePlugin plugin;
    private ChannelHandlerContext ctx;
    private UUID pairedPlayerUuid;
    private boolean whisperMode = false;

    public WebSocketHandler(WebVoiceBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof TextWebSocketFrame) {
            handleTextMessage(((TextWebSocketFrame) frame).text());
        } else if (frame instanceof BinaryWebSocketFrame) {
            handleBinaryAudio(((BinaryWebSocketFrame) frame).content());
        }
    }

    private void handleTextMessage(String message) {
        String type = extractJsonString(message, "type");
        if (type == null) return;

        switch (type) {
            case "pair":
                handlePairing(message);
                break;
            case "chat":
                handleChat(message);
                break;
            case "emoji":
                handleEmoji(message);
                break;
            case "audio":
                handleAudioMessage(message);
                break;
            case "whisper":
                handleWhisperToggle(message);
                break;
        }
    }

    private void handlePairing(String message) {
        String code = extractJsonString(message, "code");
        if (code == null) {
            sendText("{\"type\":\"error\",\"message\":\"Invalid message\"}");
            return;
        }

        UUID playerUuid = plugin.getPairingManager().resolveCode(code);
        if (playerUuid == null) {
            sendText("{\"type\":\"auth_failed\",\"message\":\"Invalid or expired code\"}");
            return;
        }

        this.pairedPlayerUuid = playerUuid;
        VoiceBridgeManager bridge = plugin.getVoiceBridge();
        if (bridge == null) {
            sendText("{\"type\":\"error\",\"message\":\"Voice system not ready\"}");
            return;
        }

        org.bukkit.entity.Player player = plugin.getServer().getPlayer(playerUuid);
        if (player == null) {
            sendText("{\"type\":\"error\",\"message\":\"Player not online\"}");
            return;
        }

        sendText("{\"type\":\"session\",\"uuid\":\"" + bridge.getSessionUUID(playerUuid)
                + "\",\"playerUUID\":\"" + playerUuid
                + "\",\"playerName\":\"" + player.getName() + "\"}");

        bridge.startSession(playerUuid, this);
        sendText("{\"type\":\"auth_ok\"}");
    }

    private void handleChat(String message) {
        if (pairedPlayerUuid == null) return;
        String chatMessage = extractJsonString(message, "message");
        if (chatMessage != null && !chatMessage.isEmpty()) {
            VoiceBridgeManager bridge = plugin.getVoiceBridge();
            if (bridge != null) {
                bridge.sendChatToMinecraft(pairedPlayerUuid, chatMessage);
            }
        }
    }

    private void handleEmoji(String message) {
        if (pairedPlayerUuid == null) return;
        String emoji = extractJsonString(message, "emoji");
        if (emoji != null && !emoji.isEmpty()) {
            VoiceBridgeManager bridge = plugin.getVoiceBridge();
            if (bridge != null) {
                bridge.sendEmojiToMinecraft(pairedPlayerUuid, emoji);
            }
        }
    }

    private void handleAudioMessage(String message) {
        if (pairedPlayerUuid == null) return;
        VoiceBridgeManager bridge = plugin.getVoiceBridge();
        if (bridge == null) return;

        whisperMode = message.contains("\"whisper\":true");

        String samplesStr = extractJsonArray(message, "samples");
        if (samplesStr == null) return;

        try {
            String[] parts = samplesStr.split(",");
            short[] pcmData = new short[parts.length];
            for (int i = 0; i < parts.length; i++) {
                pcmData[i] = Short.parseShort(parts[i].trim());
            }
            bridge.onBrowserAudio(pairedPlayerUuid, pcmData, whisperMode);
        } catch (Exception e) {
            // Ignore malformed audio
        }
    }

    private void handleWhisperToggle(String message) {
        whisperMode = message.contains("\"enabled\":true");
    }

    private void handleBinaryAudio(io.netty.buffer.ByteBuf data) {
        if (pairedPlayerUuid == null) {
            data.release();
            return;
        }

        VoiceBridgeManager bridge = plugin.getVoiceBridge();
        if (bridge == null) {
            data.release();
            return;
        }

        try {
            int sampleCount = data.readableBytes() / 2;
            short[] pcmData = new short[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                pcmData[i] = data.readShort();
            }
            data.release();
            bridge.onBrowserAudio(pairedPlayerUuid, pcmData, whisperMode);
        } catch (Exception e) {
            data.release();
        }
    }

    public void sendText(String json) {
        if (ctx != null && ctx.channel().isActive()) {
            ctx.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    public void sendBinary(byte[] data) {
        if (ctx != null && ctx.channel().isActive()) {
            ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(data)));
        }
    }

    public void sendPlayerList(java.util.List<java.util.Map<String, String>> players) {
        StringBuilder sb = new StringBuilder("{\"type\":\"player_list\",\"players\":[");
        for (int i = 0; i < players.size(); i++) {
            java.util.Map<String, String> p = players.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"uuid\":\"").append(p.get("uuid")).append("\",\"name\":\"").append(p.get("name")).append("\"}");
        }
        sb.append("]}");
        sendText(sb.toString());
    }

    public void sendPlayerTalking(String uuid, boolean talking) {
        sendText("{\"type\":\"" + (talking ? "player_talk_start" : "player_talk_stop") + "\",\"uuid\":\"" + uuid + "\"}");
    }

    public void close() {
        if (ctx != null && ctx.channel().isActive()) {
            ctx.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (pairedPlayerUuid != null) {
            VoiceBridgeManager bridge = plugin.getVoiceBridge();
            if (bridge != null) {
                bridge.stopSession(pairedPlayerUuid);
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int keyIdx = json.indexOf(search);
        if (keyIdx == -1) return null;

        int colonIdx = json.indexOf(':', keyIdx + search.length());
        if (colonIdx == -1) return null;

        int startQuote = json.indexOf('"', colonIdx + 1);
        if (startQuote == -1) return null;

        int endQuote = startQuote + 1;
        while (endQuote < json.length()) {
            if (json.charAt(endQuote) == '\\') {
                endQuote += 2;
                continue;
            }
            if (json.charAt(endQuote) == '"') break;
            endQuote++;
        }

        return json.substring(startQuote + 1, endQuote)
                .replace("\\\"", "\"")
                .replace("\\n", "\n");
    }

    private String extractJsonArray(String json, String key) {
        String search = "\"" + key + "\"";
        int keyIdx = json.indexOf(search);
        if (keyIdx == -1) return null;

        int colonIdx = json.indexOf(':', keyIdx + search.length());
        if (colonIdx == -1) return null;

        int startBracket = json.indexOf('[', colonIdx + 1);
        if (startBracket == -1) return null;

        int endBracket = json.indexOf(']', startBracket + 1);
        if (endBracket == -1) return null;

        return json.substring(startBracket + 1, endBracket);
    }
}

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
        if (message.contains("\"type\":\"pair\"")) {
            String code = extractJsonString(message, "code");
            if (code == null) {
                sendText("{\"type\":\"error\",\"message\":\"Invalid message\"}");
                return;
            }

            UUID playerUuid = plugin.getPairingManager().resolveCode(code);
            if (playerUuid == null) {
                sendText("{\"type\":\"error\",\"message\":\"Invalid or expired code\"}");
                return;
            }

            this.pairedPlayerUuid = playerUuid;
            VoiceBridgeManager bridge = plugin.getVoiceBridge();
            if (bridge == null) {
                sendText("{\"type\":\"error\",\"message\":\"Voice system not ready\"}");
                return;
            }

            bridge.startSession(playerUuid, this);
        } else if (message.contains("\"type\":\"chat\"")) {
            if (pairedPlayerUuid == null) return;
            String chatMessage = extractJsonString(message, "message");
            if (chatMessage != null && !chatMessage.isEmpty()) {
                VoiceBridgeManager bridge = plugin.getVoiceBridge();
                if (bridge != null) {
                    bridge.sendChatToMinecraft(pairedPlayerUuid, chatMessage);
                }
            }
        }
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
            bridge.onBrowserAudio(pairedPlayerUuid, pcmData);
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
}

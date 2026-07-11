package de.maxhenkel.webbridge.server;

import de.maxhenkel.webbridge.WebVoiceBridgePlugin;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.io.InputStream;
import java.io.StringWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final WebVoiceBridgePlugin plugin;
    private static final Map<String, String> MIME_TYPES = new HashMap<>();

    static {
        MIME_TYPES.put("html", "text/html");
        MIME_TYPES.put("css", "text/css");
        MIME_TYPES.put("js", "application/javascript");
        MIME_TYPES.put("json", "application/json");
        MIME_TYPES.put("png", "image/png");
        MIME_TYPES.put("svg", "image/svg+xml");
        MIME_TYPES.put("ico", "image/x-icon");
    }

    public HttpRequestHandler(WebVoiceBridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String path = request.uri();

        if (path.startsWith("/ws")) {
            ctx.fireChannelRead(request.retain());
            return;
        }

        int queryIdx = path.indexOf('?');
        if (queryIdx != -1) {
            path = path.substring(0, queryIdx);
        }

        try {
            path = URLDecoder.decode(path, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "Bad Request");
            return;
        }

        if (path.contains("..")) {
            sendError(ctx, HttpResponseStatus.FORBIDDEN, "Forbidden");
            return;
        }

        if (path.equals("/")) {
            path = "/index.html";
        }

        String resourcePath = "web" + path;
        String contentType = getContentType(path);

        try {
            InputStream is = plugin.getResource(resourcePath);
            if (is == null) {
                sendError(ctx, HttpResponseStatus.NOT_FOUND, "Not Found");
                return;
            }

            StringWriter writer = new StringWriter();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                writer.write(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
            }
            is.close();

            byte[] content = writer.toString().getBytes(StandardCharsets.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK,
                    Unpooled.wrappedBuffer(content)
            );
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
            response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");

            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } catch (Exception e) {
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
        }
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.copiedBuffer(message.getBytes(StandardCharsets.UTF_8))
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private String getContentType(String path) {
        int dot = path.lastIndexOf('.');
        if (dot == -1) return "application/octet-stream";
        String ext = path.substring(dot + 1).toLowerCase();
        return MIME_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}

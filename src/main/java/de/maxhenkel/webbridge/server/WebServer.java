package de.maxhenkel.webbridge.server;

import de.maxhenkel.webbridge.WebVoiceBridgePlugin;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;

public class WebServer {

    private final WebVoiceBridgePlugin plugin;
    private final int port;
    private EventLoopGroup group;
    private ChannelFuture channel;
    private boolean running = false;

    public WebServer(WebVoiceBridgePlugin plugin, int port) {
        this.plugin = plugin;
        this.port = port;
    }

    public void start() throws Exception {
        group = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new ChunkedWriteHandler());
                        ch.pipeline().addLast(new HttpObjectAggregator(65536));
                        ch.pipeline().addLast(new WebSocketServerProtocolHandler("/ws"));
                        ch.pipeline().addLast(new HttpRequestHandler(plugin));
                        ch.pipeline().addLast(new WebSocketHandler(plugin));
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        channel = bootstrap.bind(port).sync();
        running = true;
    }

    public void stop() {
        running = false;
        if (channel != null) {
            channel.channel().close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    public boolean isRunning() {
        return running;
    }
}

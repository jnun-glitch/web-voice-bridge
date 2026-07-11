package de.maxhenkel.webbridge;

import de.maxhenkel.webbridge.github.GitHubGist;
import de.maxhenkel.webbridge.server.WebServer;
import de.maxhenkel.webbridge.voice.VoiceBridgeManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.logging.Level;

public class WebVoiceBridgePlugin extends JavaPlugin implements CommandExecutor, Listener {

    public static WebVoiceBridgePlugin INSTANCE;
    private PairingManager pairingManager;
    private WebServer webServer;
    private VoiceBridgeManager voiceBridge;
    private GitHubGist gitHubGist;

    private volatile Object vcApi;
    private volatile boolean registeredWithVC = false;

    @Override
    public void onEnable() {
        INSTANCE = this;
        saveDefaultConfig();

        pairingManager = new PairingManager(this);
        gitHubGist = new GitHubGist(this);

        getCommand("connect").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        registerWithVoicechat();

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!registeredWithVC) {
                registerWithVoicechat();
            }
        }, 40L, 100L);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (voiceBridge != null) {
                voiceBridge.broadcastWorldMap();
            }
        }, 20L, 10L);

        getLogger().info("WebVoiceBridge enabled!");
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
        }
        if (voiceBridge != null) {
            voiceBridge.shutdown();
        }
        getLogger().info("WebVoiceBridge disabled!");
    }

    private void registerWithVoicechat() {
        if (registeredWithVC) return;

        Object service = findVoicechatService();
        if (service == null) {
            getLogger().info("Simple Voice Chat not yet available, retrying...");
            return;
        }

        try {
            ClassLoader vcCL = service.getClass().getClassLoader();
            Class<?> vcPluginClass = Class.forName("de.maxhenkel.voicechat.api.VoicechatPlugin", true, vcCL);

            Object proxy = Proxy.newProxyInstance(vcCL, new Class<?>[]{vcPluginClass}, new VoicechatPluginHandler());

            Method registerMethod = service.getClass().getMethod("registerPlugin", vcPluginClass);
            registerMethod.invoke(service, proxy);

            registeredWithVC = true;
            getLogger().info("Successfully registered with Simple Voice Chat API!");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to register with Simple Voice Chat", e);
        }
    }

    private Object findVoicechatService() {
        try {
            org.bukkit.plugin.Plugin vcPlugin = Bukkit.getPluginManager().getPlugin("voicechat");
            if (vcPlugin == null) return null;

            Object servicesManager = Bukkit.getServicesManager();
            Method getRegistrations = servicesManager.getClass().getMethod("getRegistrations", org.bukkit.plugin.Plugin.class);
            java.util.List<?> registrations = (java.util.List<?>) getRegistrations.invoke(servicesManager, vcPlugin);
            if (registrations != null) {
                for (Object rsp : registrations) {
                    Method getProvider = rsp.getClass().getMethod("getProvider");
                    Object provider = getProvider.invoke(rsp);
                    if (provider != null && provider.getClass().getName().equals("de.maxhenkel.voicechat.plugins.impl.BukkitVoicechatServiceImpl")) {
                        return provider;
                    }
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error finding voicechat service", e);
        }
        return null;
    }

    private class VoicechatPluginHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "getPluginId":
                    return "web_voice_bridge";

                case "initialize":
                    vcApi = args[0];
                    getLogger().info("Voice Chat API initialized!");
                    return null;

                case "registerEvents":
                    handleRegisterEvents(args[0]);
                    getLogger().info("Voice Chat events registered");
                    return null;

                default:
                    return null;
            }
        }

        private void handleRegisterEvents(Object registration) {
            try {
                ClassLoader cl = registration.getClass().getClassLoader();

                registerEventClass(registration, cl,
                        "de.maxhenkel.voicechat.api.events.MicrophonePacketEvent",
                        event -> { if (voiceBridge != null) voiceBridge.onMicrophonePacket(event); });

                registerEventClass(registration, cl,
                        "de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent",
                        event -> { if (voiceBridge != null) voiceBridge.onEntitySoundPacket(event); });

                registerEventClass(registration, cl,
                        "de.maxhenkel.voicechat.api.events.LocationalSoundPacketEvent",
                        event -> { if (voiceBridge != null) voiceBridge.onLocationalSoundPacket(event); });

                registerEventClass(registration, cl,
                        "de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent",
                        event -> { if (voiceBridge != null) voiceBridge.onStaticSoundPacket(event); });

                registerEventClass(registration, cl,
                        "de.maxhenkel.voicechat.api.events.PlayerConnectedEvent",
                        event -> { if (voiceBridge != null) voiceBridge.onPlayerConnected(event); });

                registerEventClass(registration, cl,
                        "de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent",
                        event -> { if (voiceBridge != null) voiceBridge.onPlayerDisconnected(event); });
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to register voice chat events", e);
            }
        }

        private void registerEventClass(Object registration, ClassLoader cl,
                                         String eventClassName, java.util.function.Consumer<Object> handler) throws Exception {
            Class<?> eventClass = Class.forName(eventClassName, true, cl);
            Class<?> consumerClass = Class.forName("java.util.function.Consumer", true, cl);

            Method registerMethod = registration.getClass().getMethod("registerEvent", Class.class, consumerClass);
            registerMethod.invoke(registration, eventClass, handler);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("connect")) {
            return false;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;

        if (vcApi == null) {
            registerWithVoicechat();
            if (vcApi == null) {
                player.sendMessage(ChatColor.RED + "Simple Voice Chat is not available. Please wait a moment and try again.");
                return true;
            }
        }

        String code = pairingManager.generateCode(player.getUniqueId());

        startWebServerIfNeeded();

        String address = getPublicAddress();
        String url = "http://" + address + ":" + getConfig().getInt("port", 8080);

        player.sendMessage("");

        player.spigot().sendMessage(new TextComponent(new ComponentBuilder("=== Web Voice Bridge ===")
                .color(ChatColor.GREEN).create()));

        TextComponent urlMsg = new TextComponent(new ComponentBuilder("Open: ")
                .color(ChatColor.YELLOW).create());
        TextComponent urlLink = new TextComponent(new ComponentBuilder(url)
                .color(ChatColor.AQUA).underlined(true)
                .event(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Klicken um zu öffnen")))
                .create());
        urlMsg.addExtra(urlLink);
        player.spigot().sendMessage(urlMsg);

        player.spigot().sendMessage(new TextComponent(new ComponentBuilder("Code: ")
                .color(ChatColor.YELLOW)
                .append(new ComponentBuilder(code)
                        .color(ChatColor.WHITE).bold(true).create())
                .create()));

        player.spigot().sendMessage(new TextComponent(new ComponentBuilder("The code expires in " + getConfig().getInt("code-expiry", 10) + " minutes.")
                .color(ChatColor.GRAY).create()));

        player.sendMessage("");

        if (getConfig().getBoolean("enable-gist", true) && !getConfig().getString("github-token", "").isEmpty()) {
            createGistAsync(code, url, player.getName());
        }

        return true;
    }

    private void startWebServerIfNeeded() {
        if (webServer != null && webServer.isRunning()) {
            return;
        }

        voiceBridge = new VoiceBridgeManager(this, vcApi);
        int port = getConfig().getInt("port", 8080);

        webServer = new WebServer(this, port);
        try {
            webServer.start();
            getLogger().info("Web server started on port " + port);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to start web server on port " + port, e);
        }
    }

    private void createGistAsync(String code, String url, String playerName) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String gistUrl = gitHubGist.createPairingGist(code, url, playerName);
                if (gistUrl != null) {
                    getLogger().info("GitHub Gist created: " + gistUrl);
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to create GitHub Gist", e);
            }
        });
    }

    private String getPublicAddress() {
        String configured = getConfig().getString("public-address", "");
        if (!configured.isEmpty()) {
            return configured;
        }

        String ip = Bukkit.getIp();
        if (ip == null || ip.isEmpty() || ip.equals("0.0.0.0")) {
            try {
                return java.net.InetAddress.getLocalHost().getHostAddress();
            } catch (Exception e) {
                return "localhost";
            }
        }
        return ip;
    }

    public PairingManager getPairingManager() {
        return pairingManager;
    }

    public VoiceBridgeManager getVoiceBridge() {
        return voiceBridge;
    }

    public Object getVcApi() {
        return vcApi;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (voiceBridge != null) {
            voiceBridge.onPlayerQuit(event.getPlayer().getUniqueId());
        }
        pairingManager.removeCode(event.getPlayer().getUniqueId());
    }
}

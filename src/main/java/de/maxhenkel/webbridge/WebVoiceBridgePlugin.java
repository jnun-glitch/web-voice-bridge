package de.maxhenkel.webbridge;

import de.maxhenkel.webbridge.github.GitHubGist;
import de.maxhenkel.webbridge.server.WebServer;
import de.maxhenkel.webbridge.voice.VoiceBridgeManager;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.LocationalSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class WebVoiceBridgePlugin extends JavaPlugin implements VoicechatPlugin, CommandExecutor, Listener {

    public static WebVoiceBridgePlugin INSTANCE;
    private PairingManager pairingManager;
    private WebServer webServer;
    private VoiceBridgeManager voiceBridge;
    private GitHubGist gitHubGist;

    private VoicechatApi api;
    private boolean registeredWithVC = false;

    @Override
    public void onEnable() {
        INSTANCE = this;
        saveDefaultConfig();

        pairingManager = new PairingManager(this);
        gitHubGist = new GitHubGist(this);

        getCommand("connect").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            registerWithVoicechat();
        }, 20L);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!registeredWithVC) {
                registerWithVoicechat();
            }
        }, 40L, 100L);

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

        BukkitVoicechatService service = Bukkit.getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            getLogger().info("Simple Voice Chat not yet available, retrying...");
            return;
        }
        service.registerPlugin(this);
        registeredWithVC = true;
        getLogger().info("Successfully registered with Simple Voice Chat API!");
    }

    @Override
    public String getPluginId() {
        return "web_voice_bridge";
    }

    @Override
    public void initialize(VoicechatApi api) {
        this.api = api;
        getLogger().info("Voice Chat API initialized!");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, event -> {
            if (voiceBridge != null) {
                voiceBridge.onMicrophonePacket(event);
            }
        });

        registration.registerEvent(EntitySoundPacketEvent.class, event -> {
            if (voiceBridge != null) {
                voiceBridge.onEntitySoundPacket(event);
            }
        });

        registration.registerEvent(LocationalSoundPacketEvent.class, event -> {
            if (voiceBridge != null) {
                voiceBridge.onLocationalSoundPacket(event);
            }
        });

        registration.registerEvent(StaticSoundPacketEvent.class, event -> {
            if (voiceBridge != null) {
                voiceBridge.onStaticSoundPacket(event);
            }
        });

        registration.registerEvent(PlayerConnectedEvent.class, event -> {
            if (voiceBridge != null) {
                voiceBridge.onPlayerConnected(event);
            }
        });

        registration.registerEvent(PlayerDisconnectedEvent.class, event -> {
            if (voiceBridge != null) {
                voiceBridge.onPlayerDisconnected(event);
            }
        });

        getLogger().info("Voice Chat events registered");
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

        if (api == null) {
            registerWithVoicechat();
            if (api == null) {
                player.sendMessage(ChatColor.RED + "Simple Voice Chat is not available. Please wait a moment and try again.");
                return true;
            }
        }

        String code = pairingManager.generateCode(player.getUniqueId());

        startWebServerIfNeeded();

        String address = getPublicAddress();
        String url = "http://" + address + ":" + getConfig().getInt("port", 8080);

        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "=== Web Voice Bridge ===");
        player.sendMessage(ChatColor.YELLOW + "Open: " + ChatColor.AQUA + url);
        player.sendMessage(ChatColor.YELLOW + "Code: " + ChatColor.WHITE + ChatColor.BOLD + code);
        player.sendMessage(ChatColor.GRAY + "The code expires in " + getConfig().getInt("code-expiry", 10) + " minutes.");
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

        voiceBridge = new VoiceBridgeManager(this, api);
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

    public VoicechatApi getApi() {
        return api;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (voiceBridge != null) {
            voiceBridge.onPlayerQuit(event.getPlayer().getUniqueId());
        }
        pairingManager.removeCode(event.getPlayer().getUniqueId());
    }
}

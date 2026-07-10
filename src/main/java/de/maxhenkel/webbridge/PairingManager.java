package de.maxhenkel.webbridge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class PairingManager {

    private final Map<String, UUID> codeToPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerToCode = new ConcurrentHashMap<>();
    private final Map<String, Long> codeTimestamps = new ConcurrentHashMap<>();
    private final int codeExpiryMinutes;

    public PairingManager(WebVoiceBridgePlugin plugin) {
        this.codeExpiryMinutes = plugin.getConfig().getInt("code-expiry", 10);
    }

    public String generateCode(UUID playerUuid) {
        String oldCode = playerToCode.get(playerUuid);
        if (oldCode != null) {
            codeToPlayer.remove(oldCode);
            codeTimestamps.remove(oldCode);
        }

        String code;
        do {
            code = String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
        } while (codeToPlayer.containsKey(code));

        codeToPlayer.put(code, playerUuid);
        playerToCode.put(playerUuid, code);
        codeTimestamps.put(code, System.currentTimeMillis());

        return code;
    }

    public UUID resolveCode(String code) {
        UUID playerUuid = codeToPlayer.get(code);
        if (playerUuid == null) {
            return null;
        }

        Long timestamp = codeTimestamps.get(code);
        if (timestamp != null) {
            long elapsed = System.currentTimeMillis() - timestamp;
            if (elapsed > codeExpiryMinutes * 60_000L) {
                codeToPlayer.remove(code);
                codeTimestamps.remove(code);
                playerToCode.remove(playerUuid);
                return null;
            }
        }

        return playerUuid;
    }

    public void removeCode(UUID playerUuid) {
        String code = playerToCode.remove(playerUuid);
        if (code != null) {
            codeToPlayer.remove(code);
            codeTimestamps.remove(code);
        }
    }

    public UUID getPlayerByCode(String code) {
        return resolveCode(code);
    }
}

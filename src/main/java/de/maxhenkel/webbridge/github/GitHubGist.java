package de.maxhenkel.webbridge.github;

import de.maxhenkel.webbridge.WebVoiceBridgePlugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

public class GitHubGist {

    private final WebVoiceBridgePlugin plugin;

    public GitHubGist(WebVoiceBridgePlugin plugin) {
        this.plugin = plugin;
    }

    public String createPairingGist(String code, String serverUrl, String playerName) {
        String token = plugin.getConfig().getString("github-token", "");
        if (token.isEmpty()) {
            return null;
        }

        try {
            String fileName = "voicechat-pairing-" + code + ".txt";
            String content = "=== Web Voice Bridge Pairing ===\n\n"
                    + "Server: " + serverUrl + "\n"
                    + "Code: " + code + "\n"
                    + "Initiated by: " + playerName + "\n\n"
                    + "1. Open the server URL in your browser\n"
                    + "2. Enter the pairing code\n"
                    + "3. Allow microphone access\n"
                    + "4. Start talking!\n";

            String json = "{"
                    + "\"description\": \"Voice Chat Pairing Code for " + playerName + "\","
                    + "\"public\": false,"
                    + "\"files\": {"
                    + "\"" + escapeJson(fileName) + "\": {"
                    + "\"content\": \"" + escapeJson(content) + "\""
                    + "}"
                    + "}"
                    + "}";

            URL url = new URL("https://api.github.com/gists");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 201) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String htmlUrl = extractHtmlUrl(response.toString());
                plugin.getLogger().info("Gist created for " + playerName);
                return htmlUrl;
            } else {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                StringBuilder error = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line);
                }
                reader.close();
                plugin.getLogger().warning("GitHub API returned " + responseCode + ": " + error.toString());
                return null;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to create GitHub Gist", e);
            return null;
        }
    }

    private String extractHtmlUrl(String json) {
        int idx = json.indexOf("\"html_url\"");
        if (idx == -1) {
            return null;
        }
        int start = json.indexOf("\"", idx + 10) + 1;
        int end = json.indexOf("\"", start);
        if (start > 0 && end > start) {
            return json.substring(start, end);
        }
        return null;
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

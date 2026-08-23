package com.githubmirror.mirror;

import com.githubmirror.GitHubMirrorPlugin;
import com.githubmirror.config.ConfigManager;
import com.google.gson.Gson;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MirrorManager {

    private final GitHubMirrorPlugin plugin;
    private final ConfigManager configManager;
    private final List<MirrorSite> mirrors;
    private MirrorSite currentMirror;
    private final ExecutorService executorService;
    private final Gson gson;

    public MirrorManager(GitHubMirrorPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.mirrors = new ArrayList<>();
        this.gson = new Gson();
        this.executorService = Executors.newFixedThreadPool(configManager.getSpeedTestConfig().getConcurrent());
        reloadMirrors();
    }

    public void reloadMirrors() {
        mirrors.clear();
        mirrors.addAll(configManager.getEnabledMirrors());
        
        String savedMirrorName = configManager.getCurrentMirror();
        if (!savedMirrorName.isEmpty()) {
            for (MirrorSite mirror : mirrors) {
                if (mirror.getName().equals(savedMirrorName)) {
                    currentMirror = mirror;
                    break;
                }
            }
        }
        
        if (currentMirror == null && !mirrors.isEmpty()) {
            currentMirror = mirrors.get(0);
        }
    }

    public void runSpeedTest(boolean broadcast) {
        CompletableFuture.runAsync(() -> performSpeedTest(broadcast), executorService);
    }

    private void performSpeedTest(boolean broadcast) {
        plugin.getLogger().info("§a[GitHubMirror] 开始测速 " + mirrors.size() + " 个镜像站...");
        
        List<CompletableFuture<MirrorTestResult>> futures = new ArrayList<>();
        ConfigManager.SpeedTestConfig testConfig = configManager.getSpeedTestConfig();
        
        for (MirrorSite mirror : mirrors) {
            CompletableFuture<MirrorTestResult> future = CompletableFuture.supplyAsync(() -> 
                testMirror(mirror, testConfig), executorService);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenRun(() -> {
            List<MirrorTestResult> results = new ArrayList<>();
            for (CompletableFuture<MirrorTestResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    plugin.getLogger().warning("§c[GitHubMirror] 测速任务异常: " + e.getMessage());
                }
            }
            
            processResults(results, broadcast);
        });
    }

    private MirrorTestResult testMirror(MirrorSite mirror, ConfigManager.SpeedTestConfig testConfig) {
        long startTime = System.currentTimeMillis();
        boolean success = false;
        long latency = -1;
        String error = null;
        
        String testUrl = mirror.getTestUrl(testConfig.getTestFile());
        
        for (int attempt = 0; attempt <= testConfig.getRetries(); attempt++) {
            try {
                URL url = new URL(testUrl);
                HttpURLConnection connection = createConnection(url);
                connection.setRequestMethod("HEAD");
                connection.setConnectTimeout(testConfig.getTimeout());
                connection.setReadTimeout(testConfig.getTimeout());
                connection.setInstanceFollowRedirects(true);
                
                int responseCode = connection.getResponseCode();
                if (responseCode >= 200 && responseCode < 400) {
                    success = true;
                    latency = System.currentTimeMillis() - startTime;
                    break;
                } else {
                    error = "HTTP " + responseCode;
                }
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (attempt < testConfig.getRetries()) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            }
        }
        
        if (!success && !testConfig.getFallbackTestFiles().isEmpty()) {
            for (String fallbackFile : testConfig.getFallbackTestFiles()) {
                String fallbackUrl = mirror.getTestUrl(fallbackFile);
                try {
                    URL url = new URL(fallbackUrl);
                    HttpURLConnection connection = createConnection(url);
                    connection.setRequestMethod("HEAD");
                    connection.setConnectTimeout(testConfig.getTimeout());
                    connection.setReadTimeout(testConfig.getTimeout());
                    
                    int responseCode = connection.getResponseCode();
                    if (responseCode >= 200 && responseCode < 400) {
                        success = true;
                        latency = System.currentTimeMillis() - startTime;
                        break;
                    }
                } catch (Exception ignored) {}
            }
        }
        
        return new MirrorTestResult(mirror, success, latency, error);
    }

    private HttpURLConnection createConnection(URL url) throws IOException {
        ConfigManager.ProxyConfig proxyConfig = configManager.getProxyConfig();
        if (proxyConfig.isEnabled() && !proxyConfig.getHost().isEmpty()) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, 
                new InetSocketAddress(proxyConfig.getHost(), proxyConfig.getPort()));
            return (HttpURLConnection) url.openConnection(proxy);
        }
        return (HttpURLConnection) url.openConnection();
    }

    private void processResults(List<MirrorTestResult> results, boolean broadcast) {
        List<MirrorTestResult> successful = new ArrayList<>();
        for (MirrorTestResult result : results) {
            MirrorSite mirror = result.getMirror();
            if (result.isSuccess()) {
                mirror.recordSuccess(result.getLatency());
                successful.add(result);
                if (configManager.isVerbose()) {
                    plugin.getLogger().info(String.format("§a[GitHubMirror] %s: %dms ✓", 
                        mirror.getName(), result.getLatency()));
                }
            } else {
                mirror.recordFailure();
                if (configManager.isVerbose()) {
                    plugin.getLogger().warning(String.format("§c[GitHubMirror] %s: 失败 - %s", 
                        mirror.getName(), result.getError()));
                }
            }
        }

        if (!successful.isEmpty()) {
            successful.sort((a, b) -> Long.compare(a.getLatency(), b.getLatency()));
            MirrorTestResult best = successful.get(0);
            
            if (configManager.isAutoSwitch() && (currentMirror == null || 
                    best.getLatency() < currentMirror.getAverageLatency())) {
                MirrorSite oldMirror = currentMirror;
                currentMirror = best.getMirror();
                configManager.saveCurrentMirror(currentMirror.getName());
                
                String message = String.format("§a[GitHubMirror] 自动切换到最快镜像: %s (%dms)", 
                    currentMirror.getName(), best.getLatency());
                if (oldMirror != null) {
                    message += String.format(" (原: %s, %dms)", oldMirror.getName(), 
                        oldMirror.getAverageLatency() == Long.MAX_VALUE ? -1 : oldMirror.getAverageLatency());
                }
                plugin.getLogger().info(message);
                
                if (broadcast && configManager.getEventConfig().isBroadcastToOps()) {
                    broadcastToOps(message);
                }
            } else if (currentMirror != null) {
                plugin.getLogger().info(String.format("§a[GitHubMirror] 当前镜像 %s (%dms) 仍是最优", 
                    currentMirror.getName(), currentMirror.getAverageLatency()));
            }
        } else {
            plugin.getLogger().severe("§c[GitHubMirror] 所有镜像站测速失败！");
            if (broadcast && configManager.getEventConfig().isBroadcastToOps()) {
                broadcastToOps("§c[GitHubMirror] 所有镜像站测速失败，请检查网络连接！");
            }
        }
    }

    private void broadcastToOps(String message) {
        plugin.getServer().getOnlinePlayers().stream()
            .filter(p -> p.isOp() || p.hasPermission("githubmirror.admin"))
            .forEach(p -> p.sendMessage(message));
    }

    public void setMirror(String name) {
        for (MirrorSite mirror : mirrors) {
            if (mirror.getName().equalsIgnoreCase(name) || mirror.getName().equals(name)) {
                currentMirror = mirror;
                configManager.saveCurrentMirror(name);
                plugin.getLogger().info("§a[GitHubMirror] 手动设置镜像站: " + name);
                return;
            }
        }
        plugin.getLogger().warning("§c[GitHubMirror] 未找到镜像站: " + name);
    }

    public void resetToDefault() {
        if (!mirrors.isEmpty()) {
            currentMirror = mirrors.get(0);
            configManager.saveCurrentMirror(currentMirror.getName());
            plugin.getLogger().info("§a[GitHubMirror] 已重置为默认镜像站: " + currentMirror.getName());
        }
    }

    public MirrorSite getCurrentMirror() {
        return currentMirror;
    }

    public String getCurrentMirrorName() {
        return currentMirror != null ? currentMirror.getName() : "未设置";
    }

    public List<MirrorSite> getAllMirrors() {
        return new ArrayList<>(mirrors);
    }

    public List<MirrorSite> getAvailableMirrors() {
        List<MirrorSite> available = new ArrayList<>();
        for (MirrorSite mirror : mirrors) {
            if (mirror.isAvailable()) {
                available.add(mirror);
            }
        }
        available.sort((a, b) -> Long.compare(a.getAverageLatency(), b.getAverageLatency()));
        return available;
    }

    public String getMirrorUrl(String type) {
        if (currentMirror == null) return null;
        switch (type.toLowerCase()) {
            case "api": return currentMirror.getApiUrl();
            case "raw": return currentMirror.getRawUrl();
            default: return currentMirror.getUrl();
        }
    }

    public String convertUrl(String originalUrl) {
        if (currentMirror == null || originalUrl == null) return originalUrl;
        
        if (originalUrl.startsWith("https://github.com/")) {
            String path = originalUrl.substring("https://github.com/".length());
            return currentMirror.getUrl() + "/" + path;
        } else if (originalUrl.startsWith("https://api.github.com/")) {
            String path = originalUrl.substring("https://api.github.com/".length());
            return currentMirror.getApiUrl() + "/" + path;
        } else if (originalUrl.startsWith("https://raw.githubusercontent.com/")) {
            String path = originalUrl.substring("https://raw.githubusercontent.com/".length());
            return currentMirror.getRawUrl() + "/" + path;
        }
        return originalUrl;
    }

    public static class MirrorTestResult {
        private final MirrorSite mirror;
        private final boolean success;
        private final long latency;
        private final String error;

        public MirrorTestResult(MirrorSite mirror, boolean success, long latency, String error) {
            this.mirror = mirror;
            this.success = success;
            this.latency = latency;
            this.error = error;
        }

        public MirrorSite getMirror() { return mirror; }
        public boolean isSuccess() { return success; }
        public long getLatency() { return latency; }
        public String getError() { return error; }
    }

    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
    }
}
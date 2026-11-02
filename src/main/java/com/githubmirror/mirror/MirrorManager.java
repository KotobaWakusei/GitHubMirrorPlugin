package com.githubmirror.mirror;

import com.githubmirror.GitHubMirrorPlugin;
import com.githubmirror.config.ConfigManager;
import com.githubmirror.interceptor.GitHubURLStreamHandlerFactory;

import org.bukkit.entity.Player;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MirrorManager {

    private final GitHubMirrorPlugin plugin;
    private final ConfigManager configManager;
    /** 只读快照：reload 时整体替换引用，避免与异步测速并发修改 */
    private volatile List<MirrorSite> mirrors = List.of();
    private volatile MirrorSite currentMirror;
    private final ExecutorService executorService;

    public MirrorManager(GitHubMirrorPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.executorService = Executors.newFixedThreadPool(configManager.getSpeedTestConfig().getConcurrent());
        reloadMirrors();
    }

    /**
     * 重载镜像列表。
     * 配置中保存的镜像名在新列表中无效时，回退到最高优先级的启用镜像，
     * 避免 currentMirror 残留已被删除/禁用的旧镜像对象。
     */
    public void reloadMirrors() {
        List<MirrorSite> enabled = configManager.getEnabledMirrors();
        this.mirrors = enabled;

        MirrorSite found = null;
        String savedMirrorName = configManager.getCurrentMirror();
        if (!savedMirrorName.isEmpty()) {
            for (MirrorSite mirror : enabled) {
                if (savedMirrorName.equals(mirror.getName())) {
                    found = mirror;
                    break;
                }
            }
        }
        if (found == null && !enabled.isEmpty()) {
            found = enabled.get(0);
        }
        this.currentMirror = found;
    }

    public void runSpeedTest(boolean broadcast) {
        CompletableFuture.runAsync(() -> performSpeedTest(broadcast), executorService);
    }

    private void performSpeedTest(boolean broadcast) {
        List<MirrorSite> snapshot = mirrors;
        plugin.getLogger().info("§a[GitHubMirror] 开始测速 " + snapshot.size() + " 个镜像站...");

        List<CompletableFuture<MirrorTestResult>> futures = new ArrayList<>();
        ConfigManager.SpeedTestConfig testConfig = configManager.getSpeedTestConfig();

        for (MirrorSite mirror : snapshot) {
            futures.add(CompletableFuture.supplyAsync(() ->
                testMirror(mirror, testConfig), executorService));
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
        // 测速期间本线程绕过全局 URL 拦截器，
        // 避免探测请求被重定向到当前镜像，导致"用当前镜像测当前镜像"的污染结果
        GitHubURLStreamHandlerFactory.setBypassed(true);
        try {
            return doTestMirror(mirror, testConfig);
        } finally {
            GitHubURLStreamHandlerFactory.setBypassed(false);
        }
    }

    private MirrorTestResult doTestMirror(MirrorSite mirror, ConfigManager.SpeedTestConfig testConfig) {
        mirror.setLastTestTime(System.currentTimeMillis());

        String testUrl = mirror.getTestUrl(testConfig.getTestFile());
        if (testUrl == null) {
            return new MirrorTestResult(mirror, false, -1,
                    "镜像类型不支持测试文件(" + mirror.getType() + "模式缺少对应域名映射)");
        }

        String error = null;

        // 主测试文件（延迟在每次尝试开始时计时，重试与等待不计入成功样本）
        for (int attempt = 0; attempt <= testConfig.getRetries(); attempt++) {
            long startTime = System.currentTimeMillis();
            try {
                URL url = new URL(testUrl);
                HttpURLConnection connection = createConnection(url);
                // 使用 GET: 部分前缀代理对 HEAD 支持不佳
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(testConfig.getTimeout());
                connection.setReadTimeout(testConfig.getTimeout());
                connection.setInstanceFollowRedirects(true);

                int responseCode = connection.getResponseCode();
                closeQuietly(connection);
                if (responseCode >= 200 && responseCode < 400) {
                    return new MirrorTestResult(mirror, true,
                            System.currentTimeMillis() - startTime, null);
                }
                error = "HTTP " + responseCode;
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (attempt < testConfig.getRetries()) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // 备用测试文件
        if (!testConfig.getFallbackTestFiles().isEmpty()) {
            for (String fallbackFile : testConfig.getFallbackTestFiles()) {
                String fallbackUrl = mirror.getTestUrl(fallbackFile);
                if (fallbackUrl == null) continue;
                long startTime = System.currentTimeMillis();
                try {
                    URL url = new URL(fallbackUrl);
                    HttpURLConnection connection = createConnection(url);
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(testConfig.getTimeout());
                    connection.setReadTimeout(testConfig.getTimeout());

                    int responseCode = connection.getResponseCode();
                    closeQuietly(connection);
                    if (responseCode >= 200 && responseCode < 400) {
                        return new MirrorTestResult(mirror, true,
                                System.currentTimeMillis() - startTime, null);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        return new MirrorTestResult(mirror, false, -1, error);
    }

    private void closeQuietly(HttpURLConnection connection) {
        try {
            java.io.InputStream in = connection.getErrorStream() != null
                    ? connection.getErrorStream() : connection.getInputStream();
            if (in != null) in.close();
        } catch (Exception ignored) {
        } finally {
            connection.disconnect();
        }
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

    /** 广播给 OP：切回主线程执行，避免异步线程访问 Bukkit API */
    private void broadcastToOps(String message) {
        Runnable task = () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.isOp() || player.hasPermission("githubmirror.admin")) {
                    player.sendMessage(message);
                }
            }
        };
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (Exception ex) {
            // 插件关闭中调度器不可用：降级为直接发送
            task.run();
        }
    }

    /**
     * 手动设置镜像站
     *
     * @return 是否设置成功
     */
    public boolean setMirror(String name) {
        for (MirrorSite mirror : mirrors) {
            if (name.equalsIgnoreCase(mirror.getName())) {
                currentMirror = mirror;
                configManager.saveCurrentMirror(mirror.getName());
                plugin.getLogger().info("§a[GitHubMirror] 手动设置镜像站: " + mirror.getName());
                return true;
            }
        }
        plugin.getLogger().warning("§c[GitHubMirror] 未找到镜像站: " + name);
        return false;
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

    /**
     * 获取当前镜像指定类型的域名。
     * <p>
     * 拼接用途语义保证：prefix 前缀代理镜像没有独立域名，拼接域名一律回退到
     * GitHub 官方地址（不会返回 null 导致 "null/..." 拼接错误）；
     * 完整的 URL 转换请使用 {@link #convertUrl(String)}。
     *
     * @param type "api" / "raw" / 其他表示主站
     */
    public String getMirrorUrl(String type) {
        if (currentMirror == null) return null;
        MirrorSite mirror = currentMirror;
        String key = type == null ? "" : type.toLowerCase();
        switch (key) {
            case "api": {
                String value = mirror.isPrefix() ? null : mirror.getApiUrl();
                return normalizeOrDefault(value, "https://api.github.com");
            }
            case "raw": {
                String value = mirror.isPrefix() ? null : mirror.getRawUrl();
                return normalizeOrDefault(value, "https://raw.githubusercontent.com");
            }
            default: {
                String value = mirror.isPrefix() ? null : mirror.getUrl();
                return normalizeOrDefault(value, "https://github.com");
            }
        }
    }

    private static String normalizeOrDefault(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    /**
     * 将 GitHub 链接转换为当前镜像链接（prefix/replace 两种模式均支持）。
     * 未匹配到已知 GitHub 域名时返回原地址。
     */
    public String convertUrl(String originalUrl) {
        if (currentMirror == null || originalUrl == null) return originalUrl;
        String rewritten = currentMirror.rewrite(originalUrl);
        return rewritten != null ? rewritten : originalUrl;
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
            Thread.currentThread().interrupt();
            executorService.shutdownNow();
        }
    }
}

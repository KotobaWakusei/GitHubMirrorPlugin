package com.githubmirror.config;

import com.githubmirror.GitHubMirrorPlugin;
import com.githubmirror.mirror.MirrorSite;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ConfigManager {

    private final GitHubMirrorPlugin plugin;
    private FileConfiguration config;
    /** 只读快照：loadMirrors 时整体替换，供并发读取 */
    private volatile List<MirrorSite> mirrors = List.of();
    private SpeedTestConfig speedTestConfig;
    private String currentMirror;
    private boolean autoSwitch;
    private boolean verbose;
    private ProxyConfig proxyConfig;
    private EventConfig eventConfig;

    public ConfigManager(GitHubMirrorPlugin plugin) {
        this.plugin = plugin;
        this.mirrors = new ArrayList<>();
        this.speedTestConfig = new SpeedTestConfig();
        this.proxyConfig = new ProxyConfig();
        this.eventConfig = new EventConfig();
    }

    public void loadConfig() {
        plugin.reloadConfig();
        config = plugin.getConfig();

        // 加载镜像站列表
        loadMirrors();

        // 加载测速配置
        loadSpeedTestConfig();

        // 加载其他配置
        currentMirror = config.getString("currentMirror", "");
        autoSwitch = config.getBoolean("autoSwitch", true);
        verbose = config.getBoolean("verbose", true);

        // 加载代理配置
        loadProxyConfig();

        // 加载事件配置
        loadEventConfig();

        if (verbose) {
            plugin.getLogger().info("§a[GitHubMirror] 配置加载完成，共 " + mirrors.size() + " 个镜像站");
        }
    }

    private void loadMirrors() {
        List<MirrorSite> loaded = new ArrayList<>();
        List<?> mirrorList = config.getList("mirrors");
        if (mirrorList != null) {
            Gson gson = new Gson();
            Type listType = new TypeToken<List<MirrorSite>>() {}.getType();
            String json = gson.toJson(mirrorList);
            List<MirrorSite> parsed = gson.fromJson(json, listType);

            if (parsed != null) {
                // enabled 字段缺省时 Gson 保留 MirrorSite 的默认值 true，此处只过滤显式禁用的条目
                parsed.removeIf(m -> m == null || !m.isEnabled());
                parsed.sort((a, b) -> Integer.compare(a.getPriority(), b.getPriority()));
                loaded = parsed;
            }
        }
        this.mirrors = Collections.unmodifiableList(loaded);
    }

    private void loadSpeedTestConfig() {
        // 下限校验，防止非法配置导致线程池/超时异常
        speedTestConfig.setTimeout(Math.max(1000, config.getInt("speedtest.timeout", 5000)));
        speedTestConfig.setRetries(Math.max(0, config.getInt("speedtest.retries", 2)));
        speedTestConfig.setConcurrent(Math.max(1, config.getInt("speedtest.concurrent", 3)));
        speedTestConfig.setIntervalHours(Math.max(0, config.getLong("speedtest.interval", 6)));
        speedTestConfig.setTestFile(config.getString("speedtest.testFile", "https://raw.githubusercontent.com/github/gitignore/main/README.md"));

        List<String> fallbackFiles = config.getStringList("speedtest.fallbackTestFiles");
        if (!fallbackFiles.isEmpty()) {
            speedTestConfig.setFallbackTestFiles(fallbackFiles);
        }
    }

    private void loadProxyConfig() {
        proxyConfig.setEnabled(config.getBoolean("proxy.enabled", false));
        proxyConfig.setHost(config.getString("proxy.host", ""));
        proxyConfig.setPort(config.getInt("proxy.port", 0));
        proxyConfig.setUsername(config.getString("proxy.username", ""));
        proxyConfig.setPassword(config.getString("proxy.password", ""));
    }

    private void loadEventConfig() {
        eventConfig.setOnEnable(config.getBoolean("events.onEnable", true));
        eventConfig.setScheduled(config.getBoolean("events.scheduled", true));
        eventConfig.setBroadcastToOps(config.getBoolean("events.broadcastToOps", true));
    }

    public void saveCurrentMirror(String mirrorName) {
        currentMirror = mirrorName;
        // 捕获当前配置对象引用，避免与主线程 reload 竞争时写入新配置对象
        FileConfiguration cfg = config;
        if (cfg == null) return;

        Runnable saveTask = () -> {
            cfg.set("currentMirror", mirrorName);
            try {
                cfg.save(new File(plugin.getDataFolder(), "config.yml"));
            } catch (IOException e) {
                plugin.getLogger().severe("§c[GitHubMirror] 保存配置失败: " + e.getMessage());
            }
        };

        // Bukkit 的 YamlConfiguration 非线程安全：写盘必须回到主线程
        if (plugin.getServer().isPrimaryThread()) {
            saveTask.run();
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, saveTask);
        } catch (Exception ex) {
            // 插件正在关闭、调度器不可用时降级为直接写盘
            saveTask.run();
        }
    }

    public List<MirrorSite> getMirrors() {
        return new ArrayList<>(mirrors);
    }

    public List<MirrorSite> getEnabledMirrors() {
        List<MirrorSite> enabled = new ArrayList<>();
        for (MirrorSite mirror : mirrors) {
            if (mirror.isEnabled()) {
                enabled.add(mirror);
            }
        }
        return enabled;
    }

    public SpeedTestConfig getSpeedTestConfig() {
        return speedTestConfig;
    }

    public String getCurrentMirror() {
        return currentMirror;
    }

    public boolean isAutoSwitch() {
        return autoSwitch;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public ProxyConfig getProxyConfig() {
        return proxyConfig;
    }

    public EventConfig getEventConfig() {
        return eventConfig;
    }

    public boolean isAutoTestOnEnable() {
        return eventConfig.isOnEnable();
    }

    public boolean isScheduledTestEnabled() {
        return eventConfig.isScheduled();
    }

    public long getTestIntervalHours() {
        return speedTestConfig.getIntervalHours();
    }

    public static class SpeedTestConfig {
        private int timeout = 5000;
        private int retries = 2;
        private int concurrent = 3;
        private long intervalHours = 6;
        private String testFile = "https://raw.githubusercontent.com/github/gitignore/main/README.md";
        private List<String> fallbackTestFiles = new ArrayList<>();

        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }
        public int getRetries() { return retries; }
        public void setRetries(int retries) { this.retries = retries; }
        public int getConcurrent() { return concurrent; }
        public void setConcurrent(int concurrent) { this.concurrent = concurrent; }
        public long getIntervalHours() { return intervalHours; }
        public void setIntervalHours(long intervalHours) { this.intervalHours = intervalHours; }
        public String getTestFile() { return testFile; }
        public void setTestFile(String testFile) { this.testFile = testFile; }
        public List<String> getFallbackTestFiles() { return fallbackTestFiles; }
        public void setFallbackTestFiles(List<String> fallbackTestFiles) { this.fallbackTestFiles = fallbackTestFiles; }
    }

    public static class ProxyConfig {
        private boolean enabled = false;
        private String host = "";
        private int port = 0;
        private String username = "";
        private String password = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class EventConfig {
        private boolean onEnable = true;
        private boolean scheduled = true;
        private boolean broadcastToOps = true;

        public boolean isOnEnable() { return onEnable; }
        public void setOnEnable(boolean onEnable) { this.onEnable = onEnable; }
        public boolean isScheduled() { return scheduled; }
        public void setScheduled(boolean scheduled) { this.scheduled = scheduled; }
        public boolean isBroadcastToOps() { return broadcastToOps; }
        public void setBroadcastToOps(boolean broadcastToOps) { this.broadcastToOps = broadcastToOps; }
    }
}
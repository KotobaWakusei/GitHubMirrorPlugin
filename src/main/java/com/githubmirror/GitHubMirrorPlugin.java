package com.githubmirror;

import com.githubmirror.api.GitHubMirrorAPI;
import com.githubmirror.config.ConfigManager;
import com.githubmirror.interceptor.GitHubURLStreamHandlerFactory;
import com.githubmirror.interceptor.HttpClientInterceptors;
import com.githubmirror.mirror.MirrorManager;
import com.githubmirror.command.MirrorCommand;
import com.githubmirror.listener.PluginListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.net.URL;

public class GitHubMirrorPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private MirrorManager mirrorManager;
    private static GitHubMirrorPlugin instance;

    @Override
    public void onEnable() {
        instance = this;

        // 尽早注册 URL 拦截器 (必须在任何网络请求之前)
        registerURLStreamHandlerFactory();

        // 保存默认配置
        saveDefaultConfig();

        // 初始化配置管理器
        configManager = new ConfigManager(this);
        configManager.loadConfig();

        // 初始化镜像管理器
        mirrorManager = new MirrorManager(this, configManager);

        // 将镜像管理器注册给拦截器 (重定向逻辑统一由 HttpClientInterceptors 提供)
        HttpClientInterceptors.init(this, mirrorManager);

        // 注册命令 (executor 与 tabCompleter 使用同一实例)
        MirrorCommand mirrorCommand = new MirrorCommand(this, mirrorManager, configManager);
        getCommand("githubmirror").setExecutor(mirrorCommand);
        getCommand("githubmirror").setTabCompleter(mirrorCommand);

        // 注册监听器
        getServer().getPluginManager().registerEvents(new PluginListener(this, mirrorManager), this);

        // 初始化API供其他插件使用
        GitHubMirrorAPI.init(this);

        // 启动时自动测速
        if (configManager.isAutoTestOnEnable()) {
            getLogger().info("§a[GitHubMirror] 正在进行启动时镜像站测速...");
            mirrorManager.runSpeedTest(true);
        }

        // 启动定时测速任务
        if (configManager.isScheduledTestEnabled()) {
            startScheduledSpeedTest();
        }

        getLogger().info("§a[GitHubMirror] 插件已启用! 当前镜像: " + mirrorManager.getCurrentMirrorName());
    }

    @Override
    public void onDisable() {
        if (mirrorManager != null) {
            mirrorManager.shutdown();
        }
        // 清理静态引用，避免其他插件在卸载后继续使用失效的管理器
        GitHubMirrorAPI.shutdown();
        HttpClientInterceptors.shutdown();
        instance = null;
        getLogger().info("§c[GitHubMirror] 插件已卸载!");
    }

    private void startScheduledSpeedTest() {
        long intervalHours = configManager.getTestIntervalHours();
        if (intervalHours > 0) {
            long intervalTicks = intervalHours * 60 * 60 * 20L; // 转换为ticks
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (mirrorManager != null) {
                        getLogger().info("§a[GitHubMirror] 定时任务触发镜像站测速...");
                        mirrorManager.runSpeedTest(false);
                    }
                }
            }.runTaskTimerAsynchronously(this, intervalTicks, intervalTicks);
        }
    }

    public static GitHubMirrorPlugin getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MirrorManager getMirrorManager() {
        return mirrorManager;
    }

    public void reloadPluginConfig() {
        reloadConfig();
        configManager.loadConfig();
        mirrorManager.reloadMirrors();
    }

    private void registerURLStreamHandlerFactory() {
        // install() 内部会先捕获内置处理器再注册工厂，避免无限递归
        boolean ok = GitHubURLStreamHandlerFactory.install(this);
        if (ok) {
            getLogger().info("§a[GitHubMirror] URL 拦截器已注册，将自动重定向 GitHub 请求");
        }
    }
}
package com.githubmirror.listener;

import com.githubmirror.GitHubMirrorPlugin;
import com.githubmirror.mirror.MirrorManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.PluginDisableEvent;

public class PluginListener implements Listener {

    private final GitHubMirrorPlugin plugin;
    private final MirrorManager mirrorManager;

    public PluginListener(GitHubMirrorPlugin plugin, MirrorManager mirrorManager) {
        this.plugin = plugin;
        this.mirrorManager = mirrorManager;
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        String pluginName = event.getPlugin().getName();
        
        // 如果是一些已知的需要GitHub连接的插件启动，可以记录日志
        if (isGitHubDependentPlugin(pluginName)) {
            plugin.getLogger().info(String.format("§a[GitHubMirror] 检测到依赖GitHub的插件启动: %s，当前镜像: %s", 
                pluginName, mirrorManager.getCurrentMirrorName()));
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        // 插件卸载时的清理工作（如果需要）
    }

    private boolean isGitHubDependentPlugin(String pluginName) {
        String lowerName = pluginName.toLowerCase();
        return lowerName.contains("github") || 
               lowerName.contains("update") || 
               lowerName.contains("updater") ||
               lowerName.contains("spigot") ||
               lowerName.contains("bukkit") ||
               lowerName.contains("paper") ||
               lowerName.contains("via") ||
               lowerName.contains("protocol") ||
               lowerName.contains("placeholder") ||
               lowerName.contains("luckperms") ||
               lowerName.contains("worldedit") ||
               lowerName.contains("worldguard") ||
               lowerName.contains("essentials") ||
               lowerName.contains("citizens") ||
               lowerName.contains("mythicmobs") ||
               lowerName.contains("jobs") ||
               lowerName.contains("mcmmo") ||
               lowerName.contains("auction") ||
               lowerName.contains("shop") ||
               lowerName.contains("economy") ||
               lowerName.contains("permission") ||
               lowerName.contains("chat") ||
               lowerName.contains("discord");
    }
}
package com.githubmirror.command;

import com.githubmirror.GitHubMirrorPlugin;
import com.githubmirror.config.ConfigManager;
import com.githubmirror.mirror.MirrorManager;
import com.githubmirror.mirror.MirrorSite;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class MirrorCommand implements CommandExecutor, TabCompleter {

    private final GitHubMirrorPlugin plugin;
    private final MirrorManager mirrorManager;
    private final ConfigManager configManager;

    public MirrorCommand(GitHubMirrorPlugin plugin, MirrorManager mirrorManager, ConfigManager configManager) {
        this.plugin = plugin;
        this.mirrorManager = mirrorManager;
        this.configManager = configManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "test":
            case "speedtest":
                handleTest(sender);
                break;
            case "status":
            case "info":
                handleStatus(sender);
                break;
            case "set":
                handleSet(sender, args);
                break;
            case "reset":
                handleReset(sender);
                break;
            case "list":
                handleList(sender);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "url":
                handleUrl(sender, args);
                break;
            case "help":
            default:
                sendHelp(sender);
                break;
        }
        return true;
    }

    private void handleTest(CommandSender sender) {
        sender.sendMessage(ChatColor.GREEN + "[GitHubMirror] 正在测速所有镜像站，请稍候...");
        mirrorManager.runSpeedTest(true);
    }

    private void handleStatus(CommandSender sender) {
        MirrorSite current = mirrorManager.getCurrentMirror();
        sender.sendMessage(ChatColor.GOLD + "===== GitHubMirror 状态 =====");
        sender.sendMessage(ChatColor.YELLOW + "当前镜像: " + ChatColor.WHITE + (current != null ? current.getName() : "未设置"));
        if (current != null) {
            sender.sendMessage(ChatColor.YELLOW + "主站地址: " + ChatColor.WHITE + current.getUrl());
            sender.sendMessage(ChatColor.YELLOW + "API地址: " + ChatColor.WHITE + current.getApiUrl());
            sender.sendMessage(ChatColor.YELLOW + "Raw地址: " + ChatColor.WHITE + current.getRawUrl());
            sender.sendMessage(ChatColor.YELLOW + "平均延迟: " + ChatColor.WHITE + 
                (current.getAverageLatency() == Long.MAX_VALUE ? "未测速" : current.getAverageLatency() + "ms"));
            sender.sendMessage(ChatColor.YELLOW + "成功/失败: " + ChatColor.WHITE + 
                current.getSuccessCount() + "/" + current.getFailureCount());
            sender.sendMessage(ChatColor.YELLOW + "可用状态: " + ChatColor.WHITE + (current.isAvailable() ? "可用" : "不可用"));
        }
        sender.sendMessage(ChatColor.YELLOW + "自动切换: " + ChatColor.WHITE + (configManager.isAutoSwitch() ? "开启" : "关闭"));
        sender.sendMessage(ChatColor.GOLD + "=============================");
    }

    private void handleSet(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /githubmirror set <镜像站名称>");
            return;
        }
        String name = args[1];
        mirrorManager.setMirror(name);
        sender.sendMessage(ChatColor.GREEN + "[GitHubMirror] 已设置镜像站: " + name);
    }

    private void handleReset(CommandSender sender) {
        mirrorManager.resetToDefault();
        sender.sendMessage(ChatColor.GREEN + "[GitHubMirror] 已重置为默认镜像站");
    }

    private void handleList(CommandSender sender) {
        List<MirrorSite> mirrors = mirrorManager.getAllMirrors();
        sender.sendMessage(ChatColor.GOLD + "===== 可用镜像站列表 =====");
        for (int i = 0; i < mirrors.size(); i++) {
            MirrorSite mirror = mirrors.get(i);
            String prefix = (mirror == mirrorManager.getCurrentMirror()) ? ChatColor.GREEN + "► " : ChatColor.GRAY + "  ";
            String status = mirror.isAvailable() ? ChatColor.GREEN + "●" : ChatColor.RED + "●";
            String latency = mirror.getAverageLatency() == Long.MAX_VALUE ? "未测速" : mirror.getAverageLatency() + "ms";
            
            sender.sendMessage(String.format("%s%s %s %s%s %s(%s) %s[优先级:%d]", 
                prefix, status, ChatColor.WHITE + mirror.getName(), 
                mirror.isEnabled() ? ChatColor.GREEN : ChatColor.RED, 
                mirror.isEnabled() ? "启用" : "禁用",
                ChatColor.YELLOW, latency, ChatColor.GRAY, mirror.getPriority()));
        }
        sender.sendMessage(ChatColor.GOLD + "===========================");
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadPluginConfig();
        sender.sendMessage(ChatColor.GREEN + "[GitHubMirror] 配置已重载");
    }

    private void handleUrl(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /githubmirror url <原始GitHub链接>");
            return;
        }
        String originalUrl = args[1];
        String convertedUrl = mirrorManager.convertUrl(originalUrl);
        sender.sendMessage(ChatColor.YELLOW + "原始链接: " + ChatColor.WHITE + originalUrl);
        sender.sendMessage(ChatColor.GREEN + "转换后: " + ChatColor.WHITE + convertedUrl);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "===== GitHubMirror 帮助 =====");
        sender.sendMessage(ChatColor.YELLOW + "/githubmirror test " + ChatColor.WHITE + "- 测速所有镜像站");
        sender.sendMessage(ChatColor.YELLOW + "/githubmirror status " + ChatColor.WHITE + "- 查看当前镜像站状态");
        sender.sendMessage(ChatColor.YELLOW + "/githubmirror list " + ChatColor.WHITE + "- 列出所有镜像站");
        sender.sendMessage(ChatColor.YELLOW + "/githubmirror set <名称> " + ChatColor.WHITE + "- 手动设置镜像站");
        sender.sendMessage(ChatColor.YELLOW + "/githubmirror reset " + ChatColor.WHITE + "- 重置为默认镜像站");
        sender.sendMessage(ChatColor.YELLOW + "/githubmirror url <链接> " + ChatColor.WHITE + "- 转换GitHub链接为镜像链接");
        sender.sendMessage(ChatColor.YELLOW + "/githubmirror reload " + ChatColor.WHITE + "- 重载配置");
        sender.sendMessage(ChatColor.GOLD + "=============================");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            List<String> subCommands = Arrays.asList("test", "status", "set", "reset", "list", "reload", "url", "help");
            return subCommands.stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            List<String> mirrorNames = mirrorManager.getAllMirrors().stream()
                .map(MirrorSite::getName)
                .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                .collect(Collectors.toList());
            return mirrorNames;
        }
        
        return completions;
    }
}
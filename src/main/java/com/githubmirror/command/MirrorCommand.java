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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class MirrorCommand implements CommandExecutor, TabCompleter {

    private static final String PERM_ADMIN = "githubmirror.admin";
    private static final String PERM_USE = "githubmirror.use";

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

        // url / help 面向所有玩家；管理类子命令需要 admin 权限
        // (plugin.yml 不再给整个命令设置 admin 门禁)
        if ("url".equals(subCommand)) {
            if (!requirePermission(sender, PERM_USE)) return true;
            handleUrl(sender, args);
            return true;
        }

        if (!requirePermission(sender, PERM_ADMIN)) return true;

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
            case "help":
            default:
                sendHelp(sender);
                break;
        }
        return true;
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(ChatColor.RED + "[GitHubMirror] 你没有权限执行此操作 (" + permission + ")");
        return false;
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
            sender.sendMessage(ChatColor.YELLOW + "镜像类型: " + ChatColor.WHITE + (current.isPrefix() ? "prefix (前缀代理)" : "replace (域名替换)"));
            sender.sendMessage(ChatColor.YELLOW + "主站地址: " + ChatColor.WHITE + display(current.getUrl()));
            sender.sendMessage(ChatColor.YELLOW + "API地址: " + ChatColor.WHITE + display(current.getApiUrl()));
            sender.sendMessage(ChatColor.YELLOW + "Raw地址: " + ChatColor.WHITE + display(current.getRawUrl()));
            sender.sendMessage(ChatColor.YELLOW + "平均延迟: " + ChatColor.WHITE +
                (current.getAverageLatency() == Long.MAX_VALUE ? "未测速" : current.getAverageLatency() + "ms"));
            sender.sendMessage(ChatColor.YELLOW + "成功/失败: " + ChatColor.WHITE +
                current.getSuccessCount() + "/" + current.getFailureCount());
            sender.sendMessage(ChatColor.YELLOW + "可用状态: " + ChatColor.WHITE + (current.isAvailable() ? "可用" : (current.isNeverTested() ? "未测速" : "不可用")));
        }
        sender.sendMessage(ChatColor.YELLOW + "自动切换: " + ChatColor.WHITE + (configManager.isAutoSwitch() ? "开启" : "关闭"));
        sender.sendMessage(ChatColor.GOLD + "=============================");
    }

    private static String display(String value) {
        return (value == null || value.trim().isEmpty()) ? "不支持" : value;
    }

    private void handleSet(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /githubmirror set <镜像站名称>");
            return;
        }
        String name = args[1];
        if (mirrorManager.setMirror(name)) {
            sender.sendMessage(ChatColor.GREEN + "[GitHubMirror] 已设置镜像站: " + name);
        } else {
            sender.sendMessage(ChatColor.RED + "[GitHubMirror] 未找到镜像站: " + name
                + ChatColor.GRAY + " (使用 /githubmirror list 查看可用列表)");
        }
    }

    private void handleReset(CommandSender sender) {
        if (mirrorManager.getCurrentMirror() == null) {
            sender.sendMessage(ChatColor.RED + "[GitHubMirror] 当前没有可用镜像站，请检查配置");
            return;
        }
        mirrorManager.resetToDefault();
        sender.sendMessage(ChatColor.GREEN + "[GitHubMirror] 已重置为默认镜像站: "
            + mirrorManager.getCurrentMirrorName());
    }

    private void handleList(CommandSender sender) {
        List<MirrorSite> mirrors = mirrorManager.getAllMirrors();
        sender.sendMessage(ChatColor.GOLD + "===== 镜像站列表 =====");
        for (MirrorSite mirror : mirrors) {
            String prefix = (mirror == mirrorManager.getCurrentMirror()) ? ChatColor.GREEN + "► " : ChatColor.GRAY + "  ";
            String status = mirror.isAvailable() ? ChatColor.GREEN + "●"
                : (mirror.isNeverTested() ? ChatColor.YELLOW + "●" : ChatColor.RED + "●");
            String latency = mirror.getAverageLatency() == Long.MAX_VALUE ? "未测速" : mirror.getAverageLatency() + "ms";

            sender.sendMessage(String.format("%s%s %s %s%s %s[%s]",
                prefix, status, ChatColor.WHITE + mirror.getName(),
                ChatColor.YELLOW, latency, ChatColor.GRAY, mirror.getType()));
        }
        sender.sendMessage(ChatColor.GOLD + "===========================");
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadPluginConfig();
        sender.sendMessage(ChatColor.GREEN + "[GitHubMirror] 配置已重载，当前镜像: "
            + mirrorManager.getCurrentMirrorName());
    }

    private void handleUrl(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /githubmirror url <原始GitHub链接>");
            return;
        }
        String originalUrl = args[1];
        String convertedUrl = mirrorManager.convertUrl(originalUrl);
        sender.sendMessage(ChatColor.YELLOW + "原始链接: " + ChatColor.WHITE + originalUrl);
        if (convertedUrl.equals(originalUrl)) {
            sender.sendMessage(ChatColor.GRAY + "未匹配到可加速的 GitHub 域名，保持原链接。");
        } else {
            sender.sendMessage(ChatColor.GREEN + "转换后: " + ChatColor.WHITE + convertedUrl);
        }
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
        sender.sendMessage(ChatColor.GRAY + "url 命令所有玩家可用，其余需要管理权限");
        sender.sendMessage(ChatColor.GOLD + "=============================");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> subCommands = Arrays.asList(
                "test", "speedtest", "status", "info", "set", "reset", "list", "reload", "url", "help");
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

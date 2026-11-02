package com.githubmirror.mirror;

public class MirrorSite {

    /** 前缀代理模式: https://gh-proxy.com/https://github.com/... */
    public static final String TYPE_PREFIX = "prefix";
    /** 域名替换模式: https://github.com/user/repo -> https://bgithub.xyz/user/repo */
    public static final String TYPE_REPLACE = "replace";

    private String name;
    private String type;   // "prefix"(默认) 或 "replace"
    private String url;    // prefix: 代理基础地址; replace: github.com 的替换域名
    private String apiUrl; // 仅 replace 模式: api.github.com 替换域名(空=不支持)
    private String rawUrl; // 仅 replace 模式: *.githubusercontent.com 替换域名(空=不支持)
    private int priority;

    /** 默认启用：配置中省略 enabled 字段时按启用处理（Gson 反序列化保留字段初始值） */
    private boolean enabled = true;

    // 以下统计字段会被测速线程写入、其他线程读取，声明为 volatile 保证可见性
    private volatile long lastTestTime;
    private volatile long averageLatency;
    private volatile int successCount;
    private volatile int failureCount;
    private volatile boolean available;

    public MirrorSite() {
        this.lastTestTime = 0;
        this.averageLatency = Long.MAX_VALUE;
        this.successCount = 0;
        this.failureCount = 0;
        this.available = false;
        this.enabled = true;
    }

    public boolean isPrefix() {
        return type == null || type.trim().isEmpty()
                || TYPE_PREFIX.equalsIgnoreCase(type.trim());
    }

    public String getType() {
        return isPrefix() ? TYPE_PREFIX : TYPE_REPLACE;
    }

    public void setType(String type) {
        this.type = type;
    }

    private static String normalize(String base) {
        if (base == null || base.trim().isEmpty()) return "";
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    /**
     * 将任意 GitHub URL 重写为该镜像的地址。
     *
     * @return 重写后的 URL；若该镜像不支持此资源类型则返回 null（调用方应回退原地址）
     */
    public String rewrite(String originalUrl) {
        if (originalUrl == null || originalUrl.isEmpty()) return null;

        if (isPrefix()) {
            String base = normalize(url);
            if (base.isEmpty()) return null;
            // 前缀代理: base + "/" + 完整原始URL (gh-proxy 等标准用法)
            return base + "/" + originalUrl;
        }

        // 域名替换模式
        String u = normalize(url);
        String api = normalize(apiUrl);
        String raw = normalize(rawUrl);

        for (String scheme : new String[]{"https://", "http://"}) {
            if (originalUrl.regionMatches(true, 0, scheme + "api.github.com", 0,
                    scheme.length() + "api.github.com".length())) {
                if (api.isEmpty()) return null;
                return api + originalUrl.substring(scheme.length() + "api.github.com".length());
            }
            if (originalUrl.regionMatches(true, 0, scheme + "raw.githubusercontent.com", 0,
                    scheme.length() + "raw.githubusercontent.com".length())) {
                if (raw.isEmpty()) return null;
                return raw + originalUrl.substring(scheme.length() + "raw.githubusercontent.com".length());
            }
            if (originalUrl.contains(".githubusercontent.com/") && !raw.isEmpty()) {
                int idx = originalUrl.indexOf(".githubusercontent.com/");
                return raw + originalUrl.substring(idx + ".githubusercontent.com".length());
            }
            if (originalUrl.regionMatches(true, 0, scheme + "github.com", 0,
                    scheme.length() + "github.com".length())
                    && (originalUrl.length() == scheme.length() + "github.com".length()
                        || originalUrl.charAt(scheme.length() + "github.com".length()) == '/')) {
                if (u.isEmpty()) return null;
                return u + originalUrl.substring(scheme.length() + "github.com".length());
            }
        }
        return null; // 不匹配任何已知 GitHub 域名，不重写
    }

    /**
     * 构造测速探测 URL。
     * <p>
     * replace 镜像若无独立 raw 域名（浏览型镜像，无法代理 raw 文件），
     * 降级为探测镜像主页，避免这类镜像永远无法通过测速。
     *
     * @return 探测地址；镜像完全未配置时返回 null
     */
    public String getTestUrl(String originalTestFile) {
        if (isPrefix()) {
            String base = normalize(url);
            return base.isEmpty() ? null : base + "/" + originalTestFile;
        }
        String rewritten = rewrite(originalTestFile);
        if (rewritten != null) {
            return rewritten;
        }
        String base = normalize(url);
        return base.isEmpty() ? null : base + "/";
    }

    /**
     * 是否从未测速过（冷启动期间按配置优先级先启用该镜像）
     */
    public boolean isNeverTested() {
        return lastTestTime == 0;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
    public String getRawUrl() { return rawUrl; }
    public void setRawUrl(String rawUrl) { this.rawUrl = rawUrl; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getLastTestTime() { return lastTestTime; }
    public void setLastTestTime(long lastTestTime) { this.lastTestTime = lastTestTime; }
    public long getAverageLatency() { return averageLatency; }
    public void setAverageLatency(long averageLatency) { this.averageLatency = averageLatency; }
    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }
    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }

    public void recordSuccess(long latency) {
        successCount++;
        failureCount = 0;
        available = true;
        if (averageLatency == Long.MAX_VALUE) {
            averageLatency = latency;
        } else {
            averageLatency = (averageLatency * (successCount - 1) + latency) / successCount;
        }
    }

    public void recordFailure() {
        failureCount++;
        if (failureCount >= 3) {
            available = false;
        }
    }

    @Override
    public String toString() {
        return String.format("MirrorSite{name='%s', type=%s, url='%s', priority=%d, latency=%dms, available=%s}",
                name, getType(), url, priority,
                averageLatency == Long.MAX_VALUE ? -1 : averageLatency, available);
    }
}

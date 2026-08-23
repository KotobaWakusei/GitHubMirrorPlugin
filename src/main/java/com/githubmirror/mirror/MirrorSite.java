package com.githubmirror.mirror;

public class MirrorSite {

    private String name;
    private String url;
    private String apiUrl;
    private String rawUrl;
    private int priority;
    private boolean enabled;

    private long lastTestTime;
    private long averageLatency;
    private int successCount;
    private int failureCount;
    private boolean available;

    public MirrorSite() {
        this.lastTestTime = 0;
        this.averageLatency = Long.MAX_VALUE;
        this.successCount = 0;
        this.failureCount = 0;
        this.available = false;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getRawUrl() {
        return rawUrl;
    }

    public void setRawUrl(String rawUrl) {
        this.rawUrl = rawUrl;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getLastTestTime() {
        return lastTestTime;
    }

    public void setLastTestTime(long lastTestTime) {
        this.lastTestTime = lastTestTime;
    }

    public long getAverageLatency() {
        return averageLatency;
    }

    public void setAverageLatency(long averageLatency) {
        this.averageLatency = averageLatency;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(int successCount) {
        this.successCount = successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

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

    public String getTestUrl(String testFile) {
        if (testFile.startsWith("https://raw.githubusercontent.com/")) {
            String path = testFile.substring("https://raw.githubusercontent.com/".length());
            return rawUrl + "/" + path;
        }
        return rawUrl + "/" + testFile;
    }

    @Override
    public String toString() {
        return String.format("MirrorSite{name='%s', url='%s', priority=%d, enabled=%s, latency=%dms, available=%s}",
                name, url, priority, enabled, averageLatency == Long.MAX_VALUE ? -1 : averageLatency, available);
    }
}
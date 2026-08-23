package com.githubmirror.interceptor;

import com.githubmirror.GitHubMirrorPlugin;
import com.githubmirror.mirror.MirrorManager;
import com.githubmirror.mirror.MirrorSite;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.security.Permission;
import java.util.List;
import java.util.Map;

public class GitHubURLStreamHandlerFactory implements URLStreamHandlerFactory {

    private final GitHubMirrorPlugin plugin;
    private static volatile MirrorManager mirrorManager;

    public GitHubURLStreamHandlerFactory(GitHubMirrorPlugin plugin) {
        this.plugin = plugin;
    }

    public static void setMirrorManager(MirrorManager manager) {
        mirrorManager = manager;
    }

    @Override
    public URLStreamHandler createURLStreamHandler(String protocol) {
        if (!("http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol))) {
            return null;
        }
        return new GitHubRedirectingStreamHandler(plugin);
    }

    public static class GitHubRedirectingStreamHandler extends URLStreamHandler {

        private final GitHubMirrorPlugin plugin;

        public GitHubRedirectingStreamHandler(GitHubMirrorPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        protected URLConnection openConnection(URL url) throws IOException {
            return openConnection(url, null);
        }

        @Override
        protected URLConnection openConnection(URL url, Proxy proxy) throws IOException {
            String host = url.getHost();
            if (shouldRedirect(host)) {
                String mirroredUrl = getMirroredUrl(url);
                if (mirroredUrl != null && !mirroredUrl.equals(url.toString())) {
                    if (plugin.getConfigManager().isVerbose()) {
                        plugin.getLogger().info("§b[GitHubMirror] 重定向: " + url + " -> " + mirroredUrl);
                    }
                    URL newUrl = new URL(mirroredUrl);
                    return createConnection(newUrl, proxy);
                }
            }
            return createConnection(url, proxy);
        }

        private URLConnection createConnection(URL url, Proxy proxy) throws IOException {
            if (proxy != null) {
                return url.openConnection(proxy);
            }
            return url.openConnection();
        }

        private boolean shouldRedirect(String host) {
            if (host == null) return false;
            String lowerHost = host.toLowerCase();
            return lowerHost.equals("github.com") ||
                   lowerHost.equals("api.github.com") ||
                   lowerHost.equals("raw.githubusercontent.com") ||
                   lowerHost.equals("objects.githubusercontent.com") ||
                   lowerHost.equals("avatars.githubusercontent.com") ||
                   lowerHost.equals("camo.githubusercontent.com") ||
                   lowerHost.equals("user-images.githubusercontent.com") ||
                   lowerHost.equals("opengraph.githubassets.com") ||
                   lowerHost.endsWith(".github.com") ||
                   lowerHost.endsWith(".githubusercontent.com");
        }

        private String getMirroredUrl(URL originalUrl) {
            if (mirrorManager == null) return null;
            
            MirrorSite currentMirror = mirrorManager.getCurrentMirror();
            if (currentMirror == null || !currentMirror.isAvailable()) {
                return null;
            }

            String original = originalUrl.toString();
            
            // GitHub 主站
            if (original.startsWith("https://github.com/") || original.startsWith("http://github.com/")) {
                String path = original.substring(original.indexOf("github.com/") + "github.com/".length());
                return currentMirror.getUrl() + "/" + path;
            }
            
            // GitHub API
            if (original.startsWith("https://api.github.com/") || original.startsWith("http://api.github.com/")) {
                String path = original.substring(original.indexOf("api.github.com/") + "api.github.com/".length());
                return currentMirror.getApiUrl() + "/" + path;
            }
            
            // Raw 文件
            if (original.startsWith("https://raw.githubusercontent.com/") || original.startsWith("http://raw.githubusercontent.com/")) {
                String path = original.substring(original.indexOf("raw.githubusercontent.com/") + "raw.githubusercontent.com/".length());
                return currentMirror.getRawUrl() + "/" + path;
            }
            
            // Objects (Releases 附件等)
            if (original.startsWith("https://objects.githubusercontent.com/") || original.startsWith("http://objects.githubusercontent.com/")) {
                String path = original.substring(original.indexOf("objects.githubusercontent.com/") + "objects.githubusercontent.com/".length());
                return currentMirror.getRawUrl() + "/" + path;
            }
            
            // 其他 githubusercontent 子域名
            if (original.contains("githubusercontent.com/")) {
                String path = original.substring(original.indexOf("githubusercontent.com/") + "githubusercontent.com/".length());
                return currentMirror.getRawUrl() + "/" + path;
            }

            // 头像等
            if (original.contains("avatars.githubusercontent.com/")) {
                String path = original.substring(original.indexOf("avatars.githubusercontent.com/") + "avatars.githubusercontent.com/".length());
                return currentMirror.getRawUrl() + "/" + path;
            }

            return null;
        }

        @Override
        protected int getDefaultPort() {
            return -1;
        }

        @Override
        protected String toExternalForm(URL url) {
            return url.toString();
        }

        @Override
        protected boolean equals(URL url1, URL url2) {
            return url1.equals(url2);
        }

        @Override
        protected int hashCode(URL url) {
            return url.hashCode();
        }

        @Override
        protected boolean sameFile(URL url1, URL url2) {
            return url1.sameFile(url2);
        }

        @Override
        protected InetAddress getHostAddress(URL url) {
            try {
                return InetAddress.getByName(url.getHost());
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * 为 OkHttp、HttpClient 等库提供的拦截器工具类
     * 其他插件可以调用这些方法来获取重定向后的 URL
     */
    public static class HttpInterceptor {
        
        public static String intercept(String originalUrl) {
            if (mirrorManager == null) return originalUrl;
            
            MirrorSite currentMirror = mirrorManager.getCurrentMirror();
            if (currentMirror == null || !currentMirror.isAvailable()) {
                return originalUrl;
            }

            if (originalUrl == null) return null;
            
            // GitHub 主站
            if (originalUrl.startsWith("https://github.com/") || originalUrl.startsWith("http://github.com/")) {
                String path = originalUrl.substring(originalUrl.indexOf("github.com/") + "github.com/".length());
                return currentMirror.getUrl() + "/" + path;
            }
            
            // GitHub API
            if (originalUrl.startsWith("https://api.github.com/") || originalUrl.startsWith("http://api.github.com/")) {
                String path = originalUrl.substring(originalUrl.indexOf("api.github.com/") + "api.github.com/".length());
                return currentMirror.getApiUrl() + "/" + path;
            }
            
            // Raw 文件
            if (originalUrl.startsWith("https://raw.githubusercontent.com/") || originalUrl.startsWith("http://raw.githubusercontent.com/")) {
                String path = originalUrl.substring(originalUrl.indexOf("raw.githubusercontent.com/") + "raw.githubusercontent.com/".length());
                return currentMirror.getRawUrl() + "/" + path;
            }
            
            // Objects
            if (originalUrl.startsWith("https://objects.githubusercontent.com/") || originalUrl.startsWith("http://objects.githubusercontent.com/")) {
                String path = originalUrl.substring(originalUrl.indexOf("objects.githubusercontent.com/") + "objects.githubusercontent.com/".length());
                return currentMirror.getRawUrl() + "/" + path;
            }
            
            // 其他 githubusercontent 子域名
            if (originalUrl.contains("githubusercontent.com/")) {
                String path = originalUrl.substring(originalUrl.indexOf("githubusercontent.com/") + "githubusercontent.com/".length());
                return currentMirror.getRawUrl() + "/" + path;
            }

            // 头像
            if (originalUrl.contains("avatars.githubusercontent.com/")) {
                String path = originalUrl.substring(originalUrl.indexOf("avatars.githubusercontent.com/") + "avatars.githubusercontent.com/".length());
                return currentMirror.getRawUrl() + "/" + path;
            }

            return originalUrl;
        }

        /**
         * 创建一个自动重定向的 HttpURLConnection
         */
        public static HttpURLConnection openConnection(String urlString) throws IOException {
            String intercepted = intercept(urlString);
            URL url = new URL(intercepted);
            return (HttpURLConnection) url.openConnection();
        }

        /**
         * 创建一个自动重定向的 HttpURLConnection (带代理)
         */
        public static HttpURLConnection openConnection(String urlString, Proxy proxy) throws IOException {
            String intercepted = intercept(urlString);
            URL url = new URL(intercepted);
            return (HttpURLConnection) url.openConnection(proxy);
        }
    }
}
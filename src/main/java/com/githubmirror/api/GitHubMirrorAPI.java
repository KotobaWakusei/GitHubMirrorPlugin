package com.githubmirror.api;

import com.githubmirror.GitHubMirrorPlugin;
import com.githubmirror.interceptor.HttpClientInterceptors;
import com.githubmirror.mirror.MirrorManager;
import com.githubmirror.mirror.MirrorSite;

import java.net.Proxy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * GitHubMirrorPlugin API
 * 其他插件可以通过此API获取最快的GitHub镜像站地址
 */
public class GitHubMirrorAPI {

    private static GitHubMirrorPlugin plugin;
    private static MirrorManager mirrorManager;

    /**
     * 初始化API (由主插件调用)
     */
    public static void init(GitHubMirrorPlugin pluginInstance) {
        plugin = pluginInstance;
        mirrorManager = pluginInstance.getMirrorManager();
    }

    /**
     * 获取当前最快的镜像站主站地址
     * <p>
     * 注意：prefix 前缀代理镜像没有独立主站域名，此时返回 GitHub 官方地址；
     * 如需完整的 URL 转换请优先使用 {@link #convertUrl(String)}。
     *
     * @return 镜像站主站URL，如 https://hub.fastgit.org；无镜像时返回 https://github.com
     */
    public static String getMirrorUrl() {
        return mirrorManager != null && mirrorManager.getMirrorUrl("web") != null
                ? mirrorManager.getMirrorUrl("web") : "https://github.com";
    }

    /**
     * 获取当前最快的镜像站API地址
     * <p>
     * 注意：prefix 前缀代理镜像没有独立 API 域名，此时回退为 GitHub 官方 API 地址；
     * 如需完整的 URL 转换请优先使用 {@link #convertUrl(String)}。
     *
     * @return 镜像站API URL，如 https://api.fastgit.org；不可用时返回 https://api.github.com
     */
    public static String getApiUrl() {
        return mirrorManager != null && mirrorManager.getMirrorUrl("api") != null
                ? mirrorManager.getMirrorUrl("api") : "https://api.github.com";
    }

    /**
     * 获取当前最快的镜像站Raw文件地址
     * <p>
     * 注意：prefix 前缀代理镜像没有独立 Raw 域名，此时回退为 GitHub 官方 Raw 地址；
     * 如需完整的 URL 转换请优先使用 {@link #convertUrl(String)}。
     *
     * @return 镜像站Raw URL，如 https://raw.fastgit.org；不可用时返回 https://raw.githubusercontent.com
     */
    public static String getRawUrl() {
        return mirrorManager != null && mirrorManager.getMirrorUrl("raw") != null
                ? mirrorManager.getMirrorUrl("raw") : "https://raw.githubusercontent.com";
    }

    /**
     * 将原始GitHub链接转换为镜像站链接
     * <p>
     * 同时支持 prefix 前缀代理与 replace 域名替换两种镜像模式；
     * 未匹配到已知 GitHub 域名时返回原地址。
     *
     * @param originalUrl 原始GitHub链接
     * @return 转换后的镜像站链接
     */
    public static String convertUrl(String originalUrl) {
        return mirrorManager != null ? mirrorManager.convertUrl(originalUrl) : originalUrl;
    }

    /**
     * 获取当前选中的镜像站名称
     * @return 镜像站名称
     */
    public static String getCurrentMirrorName() {
        return mirrorManager != null ? mirrorManager.getCurrentMirrorName() : "未设置";
    }

    /**
     * 获取当前镜像站对象
     * @return 当前镜像站
     */
    public static MirrorSite getCurrentMirror() {
        return mirrorManager != null ? mirrorManager.getCurrentMirror() : null;
    }

    /**
     * 获取所有可用的镜像站列表（按速度排序）
     * @return 镜像站列表
     */
    public static List<MirrorSite> getAvailableMirrors() {
        return mirrorManager != null ? mirrorManager.getAvailableMirrors() : List.of();
    }

    /**
     * 获取所有镜像站列表
     * @return 所有镜像站
     */
    public static List<MirrorSite> getAllMirrors() {
        return mirrorManager != null ? mirrorManager.getAllMirrors() : List.of();
    }

    /**
     * 手动设置镜像站
     * @param name 镜像站名称
     * @return 是否设置成功
     */
    public static boolean setMirror(String name) {
        if (mirrorManager != null) {
            for (MirrorSite mirror : mirrorManager.getAllMirrors()) {
                if (mirror.getName().equalsIgnoreCase(name)) {
                    mirrorManager.setMirror(name);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 触发一次测速
     * @param broadcast 是否广播结果给OP
     */
    public static void runSpeedTest(boolean broadcast) {
        if (mirrorManager != null) {
            mirrorManager.runSpeedTest(broadcast);
        }
    }

    /**
     * 检查插件是否已加载并可用
     * @return 是否可用
     */
    public static boolean isAvailable() {
        return plugin != null && plugin.isEnabled() && mirrorManager != null;
    }

    /**
     * 卸载 API (由主插件在 onDisable 时调用)
     * 清理静态引用，避免其他插件在插件卸载后使用失效的管理器
     */
    public static void shutdown() {
        plugin = null;
        mirrorManager = null;
    }

    /**
     * 获取插件实例
     * @return 插件实例
     */
    public static GitHubMirrorPlugin getPlugin() {
        return plugin;
    }

    // ==================== HTTP 客户端拦截器 ====================

    /**
     * 拦截并重定向 URL (供其他插件直接调用)
     * @param originalUrl 原始 URL
     * @return 重定向后的 URL
     */
    public static String interceptUrl(String originalUrl) {
        return HttpClientInterceptors.intercept(originalUrl);
    }

    /**
     * 创建自动重定向的 Java 11+ HttpClient
     * @return 配置好重定向的 HttpClient
     */
    public static HttpClient createRedirectingHttpClient() {
        return HttpClientInterceptors.createRedirectingHttpClient();
    }

    /**
     * 发送自动重定向的同步请求
     * @param client HttpClient 实例
     * @param request 原始请求
     * @param handler 响应处理器
     * @return 响应
     */
    public static <T> HttpResponse<T> sendIntercepted(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler) 
            throws Exception {
        return HttpClientInterceptors.sendIntercepted(client, request, handler);
    }

    /**
     * 发送自动重定向的异步请求
     * @param client HttpClient 实例
     * @param request 原始请求
     * @param handler 响应处理器
     * @return CompletableFuture
     */
    public static <T> CompletableFuture<HttpResponse<T>> sendInterceptedAsync(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return HttpClientInterceptors.sendInterceptedAsync(client, request, handler);
    }

    /**
     * 创建 OkHttp 拦截器 (通过反射，无硬依赖)
     * @return OkHttp Interceptor 实例，或 null (如果 OkHttp 不在类路径)
     */
    public static Object createOkHttpInterceptor() {
        return HttpClientInterceptors.createOkHttpInterceptor();
    }

    /**
     * 包装 Apache HttpClient (4.x)，在执行前把所有 GitHub 请求重写到当前镜像 (通过反射，无硬依赖)
     * <p>
     * 注意：Apache 的 HttpRequestInterceptor 在目标主机路由确定后执行，无法改变连接主机，
     * 因此本插件提供客户端代理包装而非请求拦截器。
     * 使用示例:
     * <pre>
     * org.apache.http.impl.client.CloseableHttpClient raw = HttpClientBuilder.create().build();
     * org.apache.http.client.HttpClient client =
     *     (org.apache.http.client.HttpClient) GitHubMirrorAPI.wrapApacheHttpClient(raw);
     * </pre>
     *
     * @param httpClient Apache HttpClient (4.x) 实例
     * @return 代理后的 client，或 null (如果 Apache HttpClient 不在类路径)
     */
    public static Object wrapApacheHttpClient(Object httpClient) {
        return HttpClientInterceptors.wrapApacheHttpClient(httpClient);
    }

    /**
     * 创建自动重定向的 URL 对象
     * @param urlString 原始 URL 字符串
     * @return 重定向后的 URL
     */
    public static java.net.URL createRedirectingURL(String urlString) throws java.net.MalformedURLException {
        return HttpClientInterceptors.createRedirectingURL(urlString);
    }

    /**
     * 打开自动重定向的 HTTP 连接
     * @param urlString 原始 URL 字符串
     * @return HttpURLConnection
     */
    public static java.net.HttpURLConnection openConnection(String urlString) throws java.io.IOException {
        return HttpClientInterceptors.openConnection(urlString);
    }

    /**
     * 打开自动重定向的 HTTP 连接 (带代理)
     * @param urlString 原始 URL 字符串
     * @param proxy 代理
     * @return HttpURLConnection
     */
    public static java.net.HttpURLConnection openConnection(String urlString, Proxy proxy) throws java.io.IOException {
        return HttpClientInterceptors.openConnection(urlString, proxy);
    }
}
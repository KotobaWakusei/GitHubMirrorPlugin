package com.githubmirror.interceptor;

import com.githubmirror.GitHubMirrorPlugin;
import com.githubmirror.mirror.MirrorManager;
import com.githubmirror.mirror.MirrorSite;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP 客户端拦截器工具类
 * 支持: Java 11+ HttpClient, OkHttp, Apache HttpClient
 * 其他插件可调用这些静态方法自动重定向 GitHub 请求
 */
public class HttpClientInterceptors {

    private static volatile MirrorManager mirrorManager;
    private static volatile GitHubMirrorPlugin plugin;

    public static void init(GitHubMirrorPlugin pluginInstance, MirrorManager manager) {
        plugin = pluginInstance;
        mirrorManager = manager;
    }

    /**
     * 拦截并重定向 URL
     */
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

    // ==================== Java 11+ HttpClient ====================
    
    /**
     * 创建自动重定向的 HttpClient
     */
    public static HttpClient createRedirectingHttpClient() {
        return HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * 创建带拦截器的新 HttpRequest (基于原始请求)
     */
    public static HttpRequest interceptRequest(HttpRequest originalRequest) {
        try {
            // 获取原始 URI
            URI originalUri = originalRequest.uri();
            if (originalUri != null) {
                String intercepted = intercept(originalUri.toString());
                if (!intercepted.equals(originalUri.toString())) {
                    if (plugin != null && plugin.getConfigManager().isVerbose()) {
                        plugin.getLogger().info("§b[GitHubMirror] HttpClient 重定向: " + originalUri + " -> " + intercepted);
                    }
                    // 创建新的请求构建器，复制原始请求的所有属性
                    HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(intercepted))
                        .method(originalRequest.method(), originalRequest.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
                    
                    // 复制 headers
                    originalRequest.headers().map().forEach((key, values) -> {
                        for (String value : values) {
                            builder.header(key, value);
                        }
                    });
                    
                    // 复制超时
                    originalRequest.timeout().ifPresent(builder::timeout);
                    
                    return builder.build();
                }
            }
        } catch (Exception ignored) {
            // 忽略错误，使用原始请求
        }
        return originalRequest;
    }

    /**
     * 发送自动重定向的请求 (同步)
     */
    public static <T> HttpResponse<T> sendIntercepted(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler) 
            throws IOException, InterruptedException {
        HttpRequest interceptedRequest = interceptRequest(request);
        return client.send(interceptedRequest, handler);
    }

    /**
     * 发送自动重定向的请求 (异步)
     */
    public static <T> CompletableFuture<HttpResponse<T>> sendInterceptedAsync(
            HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        HttpRequest interceptedRequest = interceptRequest(request);
        return client.sendAsync(interceptedRequest, handler);
    }

    // ==================== OkHttp 支持 (通过反射, 避免硬依赖) ====================
    
    /**
     * 创建 OkHttp 拦截器 (需在插件中添加 okhttp 依赖)
     * 使用示例:
     * OkHttpClient client = new OkHttpClient.Builder()
     *     .addInterceptor(HttpClientInterceptors.createOkHttpInterceptor())
     *     .build();
     */
    public static Object createOkHttpInterceptor() {
        try {
            Class<?> interceptorClass = Class.forName("okhttp3.Interceptor");
            Class<?> chainClass = Class.forName("okhttp3.Interceptor$Chain");
            Class<?> requestClass = Class.forName("okhttp3.Request");
            Class<?> httpUrlClass = Class.forName("okhttp3.HttpUrl");
            
            return java.lang.reflect.Proxy.newProxyInstance(
                interceptorClass.getClassLoader(),
                new Class[]{interceptorClass},
                (proxy, method, args) -> {
                    if (!"intercept".equals(method.getName())) {
                        return method.invoke(proxy, args);
                    }
                    
                    Object chain = args[0];
                    // 获取原始请求
                    Method requestMethod = chainClass.getMethod("request");
                    Object originalRequest = requestMethod.invoke(chain);
                    
                    // 获取 URL
                    Method urlMethod = requestClass.getMethod("url");
                    Object httpUrl = urlMethod.invoke(originalRequest);
                    Method urlToString = httpUrlClass.getMethod("toString");
                    String originalUrl = (String) urlToString.invoke(httpUrl);
                    
                    // 拦截重定向
                    String interceptedUrl = intercept(originalUrl);
                    
                    if (!interceptedUrl.equals(originalUrl)) {
                        if (plugin != null && plugin.getConfigManager().isVerbose()) {
                            plugin.getLogger().info("§b[GitHubMirror] OkHttp 重定向: " + originalUrl + " -> " + interceptedUrl);
                        }
                        // 构建新请求
                        Method newBuilderMethod = requestClass.getMethod("newBuilder");
                        Object builder = newBuilderMethod.invoke(originalRequest);
                        Method urlBuilderMethod = builder.getClass().getMethod("url", String.class);
                        urlBuilderMethod.invoke(builder, interceptedUrl);
                        Method buildMethod = builder.getClass().getMethod("build");
                        Object newRequest = buildMethod.invoke(builder);
                        
                        // 继续链
                        Method proceedMethod = chainClass.getMethod("proceed", requestClass);
                        return proceedMethod.invoke(chain, newRequest);
                    }
                    
                    // 继续原始请求
                    Method proceedMethod = chainClass.getMethod("proceed", requestClass);
                    return proceedMethod.invoke(chain, originalRequest);
                }
            );
        } catch (ClassNotFoundException e) {
            // OkHttp 不在类路径中
            return null;
        } catch (Exception e) {
            if (plugin != null) {
                plugin.getLogger().warning("§c[GitHubMirror] 创建 OkHttp 拦截器失败: " + e.getMessage());
            }
            return null;
        }
    }

    // ==================== Apache HttpClient 支持 (通过反射) ====================
    
    /**
     * 创建 Apache HttpClient 请求拦截器
     * 使用示例:
     * HttpClientBuilder.create()
     *     .addInterceptorFirst(HttpClientInterceptors.createApacheHttpClientInterceptor())
     *     .build();
     */
    public static Object createApacheHttpClientInterceptor() {
        try {
            Class<?> interceptorClass = Class.forName("org.apache.http.HttpRequestInterceptor");
            
            return java.lang.reflect.Proxy.newProxyInstance(
                interceptorClass.getClassLoader(),
                new Class[]{interceptorClass},
                (proxy, method, args) -> {
                    if (!"process".equals(method.getName())) {
                        return method.invoke(proxy, args);
                    }
                    
                    Object request = args[0];
                    Class<?> requestClass = request.getClass();
                    
                    // 获取请求行
                    Method getRequestLineMethod = requestClass.getMethod("getRequestLine");
                    Object requestLine = getRequestLineMethod.invoke(request);
                    
                    // 获取 URI
                    Method getUriMethod = requestLine.getClass().getMethod("getUri");
                    String originalUri = (String) getUriMethod.invoke(requestLine);
                    
                    // 拦截重定向
                    String interceptedUri = intercept(originalUri);
                    
                    if (!interceptedUri.equals(originalUri)) {
                        if (plugin != null && plugin.getConfigManager().isVerbose()) {
                            plugin.getLogger().info("§b[GitHubMirror] Apache HttpClient 重定向: " + originalUri + " -> " + interceptedUri);
                        }
                        // 设置新的 URI
                        Method setRequestLineMethod = requestClass.getMethod("setRequestLine", String.class);
                        setRequestLineMethod.invoke(request, interceptedUri);
                    }
                    
                    return null; // void 返回
                }
            );
        } catch (ClassNotFoundException e) {
            // Apache HttpClient 不在类路径中
            return null;
        } catch (Exception e) {
            if (plugin != null) {
                plugin.getLogger().warning("§c[GitHubMirror] 创建 Apache HttpClient 拦截器失败: " + e.getMessage());
            }
            return null;
        }
    }

    // ==================== 通用工具方法 ====================
    
    /**
     * 创建自动重定向的 URL 对象
     */
    public static java.net.URL createRedirectingURL(String urlString) throws java.net.MalformedURLException {
        return new java.net.URL(intercept(urlString));
    }

    /**
     * 打开自动重定向的连接
     */
    public static java.net.HttpURLConnection openConnection(String urlString) throws IOException {
        return (java.net.HttpURLConnection) createRedirectingURL(urlString).openConnection();
    }

    /**
     * 打开自动重定向的连接 (带代理)
     */
    public static java.net.HttpURLConnection openConnection(String urlString, Proxy proxy) throws IOException {
        return (java.net.HttpURLConnection) createRedirectingURL(urlString).openConnection(proxy);
    }
}
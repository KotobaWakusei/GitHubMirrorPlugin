package com.githubmirror.interceptor;

import com.githubmirror.GitHubMirrorPlugin;
import com.githubmirror.mirror.MirrorManager;
import com.githubmirror.mirror.MirrorSite;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP 客户端拦截器工具类
 * 支持: Java 11+ HttpClient, OkHttp, Apache HttpClient 4.x
 * 其他插件可调用这些静态方法自动重定向 GitHub 请求
 */
public class HttpClientInterceptors {

    private static volatile MirrorManager mirrorManager;
    private static volatile GitHubMirrorPlugin plugin;

    public static void init(GitHubMirrorPlugin pluginInstance, MirrorManager manager) {
        plugin = pluginInstance;
        mirrorManager = manager;
    }

    /** 插件卸载时清理静态引用，避免其他插件持有失效的管理器 */
    public static void shutdown() {
        plugin = null;
        mirrorManager = null;
    }

    /**
     * 拦截并重定向 URL (支持 prefix 前缀代理 与 replace 域名替换 两种镜像模式)
     * <p>
     * 可用镜像立即生效；尚未完成首次测速的镜像（冷启动）按配置优先级直接启用；
     * 连续测速失败的镜像回退为直连原始 GitHub。
     */
    public static String intercept(String originalUrl) {
        if (originalUrl == null) return null;
        MirrorManager manager = mirrorManager;
        if (manager == null) return originalUrl;

        MirrorSite mirror = manager.getCurrentMirror();
        if (mirror == null) return originalUrl;
        if (!mirror.isAvailable() && !mirror.isNeverTested()) {
            return originalUrl;
        }

        String rewritten = mirror.rewrite(originalUrl);
        return rewritten != null ? rewritten : originalUrl;
    }

    static void logRedirect(String source, String originalUrl, String redirectedUrl) {
        GitHubMirrorPlugin p = plugin;
        if (p != null && p.getConfigManager().isVerbose()) {
            p.getLogger().info("§b[GitHubMirror] " + source + " 重定向: " + originalUrl + " -> " + redirectedUrl);
        }
    }

    private static void warn(String message) {
        GitHubMirrorPlugin p = plugin;
        if (p != null) {
            p.getLogger().warning("§c[GitHubMirror] " + message);
        }
    }

    // ==================== Java 11+ HttpClient ====================

    /**
     * 创建 HttpClient (配合 sendIntercepted / sendInterceptedAsync 使用)
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
                    logRedirect("HttpClient", originalUri.toString(), intercepted);
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
        } catch (Exception e) {
            warn("HttpClient 请求拦截失败: " + e);
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
     *     .addInterceptor((okhttp3.Interceptor) HttpClientInterceptors.createOkHttpInterceptor())
     *     .build();
     */
    public static Object createOkHttpInterceptor() {
        try {
            Class<?> interceptorClass = Class.forName("okhttp3.Interceptor");
            Class<?> chainClass = Class.forName("okhttp3.Interceptor$Chain");
            Class<?> requestClass = Class.forName("okhttp3.Request");

            return java.lang.reflect.Proxy.newProxyInstance(
                interceptorClass.getClassLoader(),
                new Class[]{interceptorClass},
                (proxy, method, args) -> {
                    // Object 基础方法不能委托回代理对象（会再次进入本 handler 导致无限递归）
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(proxy, method, args);
                    }
                    if (!"intercept".equals(method.getName())) {
                        return null; // Interceptor 接口无其他方法，返回默认值
                    }

                    Object chain = args[0];
                    // 获取原始请求
                    Method requestMethod = chainClass.getMethod("request");
                    Object originalRequest = invokeTarget(chain, requestMethod);

                    // 获取 URL (HttpUrl.toString() 是 Object 方法，无需反射)
                    Method urlMethod = requestClass.getMethod("url");
                    Object httpUrl = invokeTarget(originalRequest, urlMethod);
                    String originalUrl = httpUrl.toString();

                    // 拦截重定向
                    String interceptedUrl = intercept(originalUrl);

                    if (!interceptedUrl.equals(originalUrl)) {
                        logRedirect("OkHttp", originalUrl, interceptedUrl);
                        // 构建新请求
                        Method newBuilderMethod = requestClass.getMethod("newBuilder");
                        Object builder = invokeTarget(originalRequest, newBuilderMethod);
                        Method urlBuilderMethod = builder.getClass().getMethod("url", String.class);
                        invokeTarget(builder, urlBuilderMethod, interceptedUrl);
                        Method buildMethod = builder.getClass().getMethod("build");
                        Object newRequest = invokeTarget(builder, buildMethod);

                        // 继续链
                        Method proceedMethod = chainClass.getMethod("proceed", requestClass);
                        return invokeTarget(chain, proceedMethod, newRequest);
                    }

                    // 继续原始请求
                    Method proceedMethod = chainClass.getMethod("proceed", requestClass);
                    return invokeTarget(chain, proceedMethod, originalRequest);
                }
            );
        } catch (ClassNotFoundException e) {
            // OkHttp 不在类路径中
            return null;
        } catch (Exception e) {
            warn("创建 OkHttp 拦截器失败: " + e);
            return null;
        }
    }

    // ==================== Apache HttpClient 4.x 支持 (通过反射) ====================

    /**
     * 包装 Apache HttpClient (4.x)，在执行前把所有 GitHub 请求重写到当前镜像。
     * <p>
     * 注意：Apache 的 HttpRequestInterceptor 在目标主机路由确定之后才执行，无法改变
     * 连接主机，因此这里提供「客户端代理包装」而非请求拦截器。
     * 使用示例:
     * <pre>
     * org.apache.http.impl.client.CloseableHttpClient raw = HttpClientBuilder.create().build();
     * org.apache.http.client.HttpClient client =
     *     (org.apache.http.client.HttpClient) HttpClientInterceptors.wrapApacheHttpClient(raw);
     * </pre>
     *
     * @param httpClient Apache HttpClient 实例 (4.x)
     * @return 代理后的 client；若 Apache HttpClient 不在类路径则返回 null
     */
    public static Object wrapApacheHttpClient(Object httpClient) {
        if (httpClient == null) return null;
        try {
            Class<?> httpClientClass = Class.forName("org.apache.http.client.HttpClient");
            Class<?> httpUriRequestClass = Class.forName("org.apache.http.HttpUriRequest");
            Class<?> httpHostClass = Class.forName("org.apache.http.HttpHost");
            Class<?> httpRequestClass = Class.forName("org.apache.http.HttpRequest");

            return java.lang.reflect.Proxy.newProxyInstance(
                httpClientClass.getClassLoader(),
                new Class[]{httpClientClass},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(proxy, method, args);
                    }
                    if (!"execute".equals(method.getName()) || args == null || args.length == 0) {
                        return invokeTarget(httpClient, method, args);
                    }
                    try {
                        rewriteApacheExecuteArgs(args, httpUriRequestClass, httpHostClass, httpRequestClass);
                    } catch (Throwable t) {
                        warn("Apache HttpClient 重定向失败: " + t);
                    }
                    return invokeTarget(httpClient, method, args);
                }
            );
        } catch (ClassNotFoundException e) {
            // Apache HttpClient 不在类路径中
            return null;
        } catch (Exception e) {
            warn("创建 Apache HttpClient 代理失败: " + e);
            return null;
        }
    }

    /** 改写 execute(...) 的参数：支持 execute(HttpUriRequest[, ctx]) 与 execute(HttpHost, HttpRequest[, ctx]) */
    private static void rewriteApacheExecuteArgs(Object[] args,
                                                 Class<?> httpUriRequestClass,
                                                 Class<?> httpHostClass,
                                                 Class<?> httpRequestClass) throws Exception {
        // 形式一: execute(HttpUriRequest, ...)
        if (httpUriRequestClass.isInstance(args[0])) {
            Object request = args[0];
            Object uri = httpUriRequestClass.getMethod("getURI").invoke(request);
            if (uri == null) return;
            String original = uri.toString();
            String mirrored = intercept(original);
            if (mirrored == null || mirrored.equals(original)) return;
            logRedirect("Apache HttpClient", original, mirrored);
            args[0] = rebuildApacheRequest(request, mirrored);
            return;
        }

        // 形式二: execute(HttpHost, HttpRequest, ...)
        if (args.length >= 2 && httpHostClass.isInstance(args[0]) && httpRequestClass.isInstance(args[1])) {
            Object host = args[0];
            Object request = args[1];

            String hostName = (String) httpHostClass.getMethod("getHostName").invoke(host);
            int port = (Integer) httpHostClass.getMethod("getPort").invoke(host);
            String schemeName = (String) httpHostClass.getMethod("getSchemeName").invoke(host);

            Object requestLine = httpRequestClass.getMethod("getRequestLine").invoke(request);
            String path = (String) requestLine.getClass().getMethod("getUri").invoke(requestLine);

            URI original;
            try {
                original = URI.create(path.startsWith("http")
                        ? path
                        : schemeName + "://" + hostName + (port != -1 ? ":" + port : "") + path);
            } catch (IllegalArgumentException e) {
                return;
            }
            String mirrored = intercept(original.toString());
            if (mirrored == null || mirrored.equals(original.toString())) return;
            logRedirect("Apache HttpClient", original.toString(), mirrored);

            URI target = URI.create(mirrored);
            args[0] = httpHostClass.getConstructor(String.class, int.class, String.class)
                    .newInstance(target.getHost(), target.getPort(), target.getScheme());

            String newPathAndQuery = target.getRawPath() == null ? "/" : target.getRawPath();
            if (target.getRawQuery() != null) newPathAndQuery += "?" + target.getRawQuery();
            args[1] = rebuildApacheRequest(request, newPathAndQuery);
        }
    }

    /**
     * 重建 Apache 请求并设置新 URI。
     * 优先用 RequestBuilder.copy (4.3+) 保留方法/头/实体；旧版本回退到 setURI 原地替换。
     */
    private static Object rebuildApacheRequest(Object request, String newUri) throws Exception {
        try {
            Class<?> builderClass = Class.forName("org.apache.http.client.methods.RequestBuilder");
            Class<?> httpRequestClass = Class.forName("org.apache.http.HttpRequest");
            Object builder = builderClass.getMethod("copy", httpRequestClass).invoke(null, request);
            builderClass.getMethod("setUri", String.class).invoke(builder, newUri);
            return builderClass.getMethod("build").invoke(builder);
        } catch (ClassNotFoundException notAvailable) {
            // RequestBuilder 不可用（4.3 以下）：回退到 setURI 原地替换
            Method setUri = request.getClass().getMethod("setURI", URI.class);
            setUri.invoke(request, URI.create(newUri));
            return request;
        }
    }

    // ==================== 通用工具方法 ====================

    /** 处理动态代理的 Object 基础方法，避免委托回代理对象造成无限递归 */
    private static Object handleObjectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "toString": return "GitHubMirrorProxyInterceptor";
            case "hashCode": return System.identityHashCode(proxy);
            case "equals":   return proxy == args[0];
            default:         return null;
        }
    }

    /**
     * 反射调用目标对象：还原被代理的真实异常（InvocationTargetException），
     * 避免调用方收到 UndeclaredThrowableException。
     */
    private static Object invokeTarget(Object target, Method method, Object... args) throws IOException {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IOException) throw (IOException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IOException(cause);
        } catch (IllegalAccessException e) {
            throw new IOException("[GitHubMirror] 反射调用失败: " + method, e);
        }
    }

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

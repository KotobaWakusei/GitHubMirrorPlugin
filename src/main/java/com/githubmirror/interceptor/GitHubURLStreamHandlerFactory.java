package com.githubmirror.interceptor;

import com.githubmirror.GitHubMirrorPlugin;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;

/**
 * 全局 URL 拦截器。
 *
 * 关键设计：
 * 1. 安装本工厂之前，必须先通过反射捕获 JVM 内置的 http/https 处理器，
 *    之后所有连接一律委托给捕获的默认处理器。
 * 2. 绝不在本类内调用 url.openConnection()——否则会再次进入自身导致无限递归
 *    （StackOverflowError）。
 * 3. 绝不覆写 equals/hashCode/sameFile：URL.equals()/URL.hashCode() 内部会回调
 *    handler 的对应方法，若覆写成 "委托回 URL 自身" 会形成无限递归（爆栈）。
 * 4. 测速等内部流量可通过 {@link #setBypassed(boolean)} 临时绕过全局重定向，
 *    避免用当前镜像去测当前镜像。
 *
 * 需要 JVM 参数: --add-opens java.base/java.net=ALL-UNNAMED
 */
public class GitHubURLStreamHandlerFactory implements URLStreamHandlerFactory {

    private final GitHubMirrorPlugin plugin;
    private final URLStreamHandler httpDefault;
    private final URLStreamHandler httpsDefault;

    // 反射缓存的默认处理器方法（setAccessible 已在 install() 中完成）
    private static Method openConnMethod;
    private static Method openConnProxyMethod;

    /** 当前线程是否绕过全局重定向（供插件自身测速使用） */
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // 重入保护：防止任何未知路径导致的无限递归
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final int MAX_DEPTH = 8;

    private GitHubURLStreamHandlerFactory(GitHubMirrorPlugin plugin,
                                          URLStreamHandler httpDefault,
                                          URLStreamHandler httpsDefault) {
        this.plugin = plugin;
        this.httpDefault = httpDefault;
        this.httpsDefault = httpsDefault;
    }

    /**
     * 安装全局拦截器。
     * 必须在任何网络请求发生之前调用；内部会先捕获内置处理器再注册工厂。
     *
     * @return 是否安装成功（失败通常因为缺少 --add-opens 参数）
     */
    public static boolean install(GitHubMirrorPlugin plugin) {
        try {
            // 第 1 步：在工厂注册前捕获内置的默认协议处理器
            Method getHandler = URL.class.getDeclaredMethod("getURLStreamHandler", String.class);
            getHandler.setAccessible(true);
            URLStreamHandler httpDefault = (URLStreamHandler) getHandler.invoke(null, "http");
            URLStreamHandler httpsDefault = (URLStreamHandler) getHandler.invoke(null, "https");

            if (httpDefault == null || httpsDefault == null) {
                plugin.getLogger().warning("§c[GitHubMirror] 无法捕获内置 HTTP/HTTPS 处理器");
                return false;
            }

            // 第 2 步：准备委托用的反射方法
            openConnMethod = URLStreamHandler.class.getDeclaredMethod("openConnection", URL.class);
            openConnMethod.setAccessible(true);

            openConnProxyMethod = URLStreamHandler.class.getDeclaredMethod("openConnection", URL.class, Proxy.class);
            openConnProxyMethod.setAccessible(true);

            // 第 3 步：注册全局工厂（每个 JVM 只能一次）
            URL.setURLStreamHandlerFactory(
                    new GitHubURLStreamHandlerFactory(plugin, httpDefault, httpsDefault));
            return true;
        } catch (LinkageError e) {
            plugin.getLogger().warning("§c[GitHubMirror] URLStreamHandlerFactory 已被占用: " + e.getMessage());
            return false;
        } catch (Throwable t) {
            plugin.getLogger().warning("§c[GitHubMirror] 安装 URL 拦截器失败(缺少 --add-opens java.base/java.net=ALL-UNNAMED ?): "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    /** 当前线程是否绕过全局重定向 */
    public static boolean isBypassed() {
        return BYPASS.get();
    }

    /**
     * 设置当前线程是否绕过全局重定向。
     * 传 false 时清除标记（而非写入 false），避免线程池线程的 ThreadLocal 残留。
     */
    public static void setBypassed(boolean bypassed) {
        if (bypassed) {
            BYPASS.set(true);
        } else {
            BYPASS.remove();
        }
    }

    @Override
    public URLStreamHandler createURLStreamHandler(String protocol) {
        if ("http".equalsIgnoreCase(protocol)) {
            return new RedirectingHandler(httpDefault, false);
        }
        if ("https".equalsIgnoreCase(protocol)) {
            return new RedirectingHandler(httpsDefault, true);
        }
        return null; // 其他协议交给 JVM 默认查找
    }

    public class RedirectingHandler extends URLStreamHandler {

        private final URLStreamHandler delegate;
        private final boolean isHttps;

        RedirectingHandler(URLStreamHandler delegate, boolean isHttps) {
            this.delegate = delegate;
            this.isHttps = isHttps;
        }

        @Override
        protected URLConnection openConnection(URL url) throws IOException {
            return handle(url, null);
        }

        @Override
        protected URLConnection openConnection(URL url, Proxy proxy) throws IOException {
            return handle(url, proxy);
        }

        private URLConnection handle(URL url, Proxy proxy) throws IOException {
            int depth = DEPTH.get();
            if (depth >= MAX_DEPTH) {
                DEPTH.remove();
                throw new IOException("[GitHubMirror] 重定向深度超限，中止请求: " + url);
            }

            URL target = url;
            try {
                // 仅在第一层做重定向决策，避免链式重定向/递归；测速线程直接绕过
                if (depth == 0 && !BYPASS.get() && shouldRedirect(url.getHost())) {
                    String mirrored = HttpClientInterceptors.intercept(url.toString());
                    if (mirrored != null && !mirrored.equals(url.toString())) {
                        if (plugin.getConfigManager().isVerbose()) {
                            plugin.getLogger().info("§b[GitHubMirror] 重定向: " + url + " -> " + mirrored);
                        }
                        target = new URL(mirrored);
                    }
                }

                DEPTH.set(depth + 1);
                try {
                    if (proxy != null && openConnProxyMethod != null) {
                        return (URLConnection) openConnProxyMethod.invoke(delegate, target, proxy);
                    }
                    return (URLConnection) openConnMethod.invoke(delegate, target);
                } finally {
                    DEPTH.set(depth);
                }
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                }
                throw new IOException("[GitHubMirror] 委托默认处理器失败", cause);
            } catch (IllegalAccessException e) {
                throw new IOException("[GitHubMirror] 反射访问被拒绝(检查 --add-opens java.base/java.net=ALL-UNNAMED)", e);
            } catch (java.net.MalformedURLException e) {
                throw new IOException("[GitHubMirror] 重定向目标非法: " + target, e);
            }
        }

        @Override
        protected int getDefaultPort() {
            return isHttps ? 443 : 80;
        }

        // 注意：不要覆写 equals/hashCode/sameFile。
        // URL.equals()/hashCode() 会回调 handler 的这些方法，覆写成 "委托回 URL" 会无限递归。
        // 使用 URLStreamHandler 的默认实现即可。
    }

    private static boolean shouldRedirect(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        return h.equals("github.com")
                || h.equals("api.github.com")
                || h.equals("raw.githubusercontent.com")
                || h.equals("objects.githubusercontent.com")
                || h.equals("avatars.githubusercontent.com")
                || h.equals("camo.githubusercontent.com")
                || h.equals("user-images.githubusercontent.com")
                || h.equals("opengraph.githubassets.com")
                || h.equals("codeload.github.com")
                || h.equals("gist.github.com")
                || h.endsWith(".github.com")
                || h.endsWith(".githubusercontent.com");
    }
}

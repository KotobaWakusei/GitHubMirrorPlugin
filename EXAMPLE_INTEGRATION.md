# 其他插件集成示例

## 方案对比

| 集成方式 | 适用场景 | 优点 | 缺点 |
|---------|---------|------|------|
| **API 手动转换** | 简单的 URL 替换 | 无依赖、完全可控 | 需修改代码 |
| **Java HttpClient 拦截器** | 使用 JDK 11+ HttpClient | 标准库、零依赖 | 仅限 HttpClient |
| **OkHttp 拦截器** | 使用 OkHttp | 无侵入、自动生效 | 需添加 okhttp 依赖 |
| **Apache HttpClient 拦截器** | 使用 HttpClient 4.x/5.x | 成熟稳定 | 需添加依赖 |
| **全局 URL 拦截** | 无法修改第三方库代码 | 完全透明、零代码改动 | 需 JVM 参数 `--add-opens` |

---

## 场景 1: 插件更新检查器 (API 手动转换)

```java
public class UpdateChecker {
    
    public void checkForUpdates(String repoOwner, String repoName, Consumer<String> callback) {
        if (!GitHubMirrorAPI.isAvailable()) {
            checkOriginalGitHub(repoOwner, repoName, callback);
            return;
        }
        
        String apiUrl = GitHubMirrorAPI.getApiUrl() + "/repos/" + repoOwner + "/" + repoName + "/releases/latest";
        
        // 使用镜像站 API 请求
        new AsyncHttpRequest(apiUrl).onSuccess(response -> {
            String downloadUrl = parseDownloadUrl(response);
            // 转换下载链接
            String mirroredUrl = GitHubMirrorAPI.convertUrl(downloadUrl);
            callback.accept(mirroredUrl);
        }).onFailure(e -> {
            checkOriginalGitHub(repoOwner, repoName, callback);
        }).execute();
    }
}
```

## 场景 2: 从 GitHub Raw 下载配置文件 (Java HttpClient)

```java
public class ConfigDownloader {
    
    public void downloadConfig(String rawUrl, File targetFile, Runnable onComplete) {
        // 创建自动重定向的客户端
        HttpClient client = GitHubMirrorAPI.createRedirectingHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(rawUrl))  // 原始 GitHub 链接
            .GET()
            .build();
        
        // 异步下载 (自动重定向到镜像站)
        GitHubMirrorAPI.sendInterceptedAsync(client, request, HttpResponse.BodyHandlers.ofFile(targetFile.toPath()))
            .thenAccept(response -> {
                if (response.statusCode() == 200) {
                    onComplete.run();
                }
            })
            .exceptionally(e -> {
                getLogger().warning("下载失败: " + e.getMessage());
                return null;
            });
    }
}
```

## 场景 3: OkHttp 客户端 (零侵入拦截)

```java
// 在插件初始化时配置一次
OkHttpClient httpClient = new OkHttpClient.Builder()
    .addInterceptor((okhttp3.Interceptor) GitHubMirrorAPI.createOkHttpInterceptor())
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build();

// 之后所有请求自动重定向，无需修改业务代码
Request request = new Request.Builder()
    .url("https://github.com/user/repo/releases/latest")  // 自动变为镜像站
    .build();

httpClient.newCall(request).enqueue(new Callback() {
    @Override
    public void onFailure(Call call, IOException e) { /* 处理失败 */ }
    
    @Override
    public void onResponse(Call call, Response response) { /* 处理响应 */ }
});
```

## 场景 4: Apache HttpClient 5.x

```java
CloseableHttpClient httpClient = HttpClientBuilder.create()
    .addInterceptorFirst((HttpRequestInterceptor) GitHubMirrorAPI.createApacheHttpClientInterceptor())
    .setDefaultRequestConfig(RequestConfig.custom()
        .setConnectTimeout(10000)
        .setResponseTimeout(30000)
        .build())
    .build();

// 使用原始 GitHub URL，自动重定向
HttpGet request = new HttpGet("https://api.github.com/repos/user/repo");
try (CloseableHttpResponse response = httpClient.execute(request)) {
    // 处理响应
}
```

## 场景 5: Maven/Gradle 依赖下载代理

```java
public class DependencyResolver {
    
    public String resolveDependencyUrl(String groupId, String artifactId, String version) {
        // 原始 GitHub Packages URL
        String original = String.format(
            "https://maven.pkg.github.com/%s/%s/%s/%s-%s.jar",
            groupId, artifactId, artifactId, artifactId, version
        );
        
        // 自动转换为镜像站
        if (GitHubMirrorAPI.isAvailable()) {
            return GitHubMirrorAPI.convertUrl(original);
        }
        return original;
    }
}
```

## 场景 6: 在 plugin.yml 中声明软依赖

```yaml
# 你的插件 plugin.yml
name: YourPlugin
version: 1.0.0
main: com.yourplugin.YourPlugin
api-version: 1.20
softdepend: [GitHubMirrorPlugin]  # 软依赖，不强制要求
```

```java
// 在你的插件主类中
public class YourPlugin extends JavaPlugin {
    
    private boolean mirrorAvailable = false;
    
    @Override
    public void onEnable() {
        // 检查 GitHubMirrorPlugin 是否加载
        Plugin mirrorPlugin = getServer().getPluginManager().getPlugin("GitHubMirrorPlugin");
        if (mirrorPlugin != null && mirrorPlugin.isEnabled()) {
            mirrorAvailable = true;
            getLogger().info("检测到 GitHubMirrorPlugin，将使用镜像站加速 GitHub 访问");
        }
    }
    
    // 统一的 URL 获取方法
    public String getGitHubUrl(String originalUrl) {
        if (mirrorAvailable) {
            return GitHubMirrorAPI.convertUrl(originalUrl);
        }
        return originalUrl;
    }
    
    // 获取预配置的 HttpClient
    public HttpClient getHttpClient() {
        if (mirrorAvailable) {
            return GitHubMirrorAPI.createRedirectingHttpClient();
        }
        return HttpClient.newHttpClient();
    }
}
```

## 场景 7: 定时任务中下载资源

```java
public class ScheduledTask extends BukkitRunnable {
    
    @Override
    public void run() {
        if (!GitHubMirrorAPI.isAvailable()) {
            return;
        }
        
        // 获取当前最快镜像
        MirrorSite mirror = GitHubMirrorAPI.getCurrentMirror();
        if (mirror == null || !mirror.isAvailable()) {
            // 触发重新测速
            GitHubMirrorAPI.runSpeedTest(false);
            return;
        }
        
        // 使用 API 自动转换
        String downloadUrl = GitHubMirrorAPI.convertUrl(
            "https://github.com/YourProject/releases/latest/download/plugin.jar"
        );
        
        // 下载逻辑...
        downloadFile(downloadUrl);
    }
}
```

## 场景 8: 多线程并发下载

```java
public class ParallelDownloader {
    
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final HttpClient httpClient;
    
    public ParallelDownloader() {
        this.httpClient = GitHubMirrorAPI.isAvailable() 
            ? GitHubMirrorAPI.createRedirectingHttpClient()
            : HttpClient.newHttpClient();
    }
    
    public void downloadMultiple(List<String> urls, Consumer<Map<String, File>> callback) {
        Map<String, File> results = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(urls.size());
        
        for (String url : urls) {
            executor.submit(() -> {
                try {
                    // 自动重定向
                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();
                    
                    File tempFile = File.createTempFile("download-", ".tmp");
                    HttpResponse<Path> response = GitHubMirrorAPI.sendIntercepted(
                        httpClient, request, HttpResponse.BodyHandlers.ofFile(tempFile.toPath())
                    );
                    
                    if (response.statusCode() == 200) {
                        results.put(url, tempFile);
                    }
                } catch (Exception e) {
                    getLogger().warning("下载失败: " + url + " - " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        
        new Thread(() -> {
            try {
                latch.await();
                callback.accept(results);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
}
```

## 场景 9: 图片/头像加载 (自动重定向 avatars.githubusercontent.com)

```java
public class AvatarLoader {
    
    public void loadAvatar(String userId, Consumer<BufferedImage> callback) {
        // GitHub 头像 URL
        String avatarUrl = "https://avatars.githubusercontent.com/u/" + userId + "?v=4";
        
        // 自动重定向到镜像站
        String mirroredUrl = GitHubMirrorAPI.convertUrl(avatarUrl);
        
        // 异步加载图片
        CompletableFuture.supplyAsync(() -> {
            try {
                URL url = new URL(mirroredUrl);
                return ImageIO.read(url.openStream());
            } catch (IOException e) {
                return null;
            }
        }).thenAccept(callback);
    }
}
```

## 最佳实践

1. **始终检查可用性** - 使用 `GitHubMirrorAPI.isAvailable()` 
2. **提供降级方案** - 镜像站不可用时回退到原始 GitHub
3. **复用 HttpClient** - 创建一次复用，不要重复创建
4. **监听测速完成** - 可注册监听器在测速完成后更新本地缓存
5. **处理异常** - 网络请求必须包含超时和重试逻辑
6. **优先使用拦截器** - OkHttp/Apache HttpClient 拦截器比手动转换更省事

## 版本兼容性

| GitHubMirrorPlugin | API 版本 | 支持的 MC 版本 | Java 版本 |
|-------------------|----------|----------------|-----------|
| 1.0.0             | 1.0      | 1.18+          | 17+       |

使用 Maven Central 或本地安装：
```bash
# 本地安装到 Maven 仓库
mvn install:install-file \
  -Dfile=GitHubMirrorPlugin-1.0.0.jar \
  -DgroupId=com.githubmirror \
  -DartifactId=GitHubMirrorPlugin \
  -Dversion=1.0.0 \
  -Dpackaging=jar
```

```xml
<dependency>
    <groupId>com.githubmirror</groupId>
    <artifactId>GitHubMirrorPlugin</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```
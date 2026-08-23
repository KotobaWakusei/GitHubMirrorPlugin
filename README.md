# GitHubMirrorPlugin

解决国内 Minecraft 服务器难以连接 GitHub 的插件，通过自动测速并选择最快的镜像站，**全局自动重定向所有 GitHub 请求**。

## ✨ 核心功能

| 功能 | 说明 |
|------|------|
| 🌐 **全局 URL 拦截** | 自动重定向 `new URL(...).openConnection()` 等所有 HTTP 请求 |
| 🚀 **自动测速** | 启动时/定时/手动测速所有配置的镜像站 |
| 🎯 **智能切换** | 自动选择延迟最低的可用镜像站 |
| 📦 **9 个内置镜像** | FastGit、Gitee、cnpmjs、ghproxy、云盘加速等 |
| 🔌 **多层拦截支持** | URLStreamHandler / Java HttpClient / OkHttp / Apache HttpClient |
| 🔧 **灵活配置** | 支持代理、自定义镜像、测速参数调整 |

## 📋 内置镜像站

| 名称 | 主站 | API | Raw | 优先级 |
|------|------|-----|-----|--------|
| GitHub官方 | github.com | api.github.com | raw.githubusercontent.com | 10 |
| **FastGit (推荐)** | hub.fastgit.org | api.fastgit.org | raw.fastgit.org | **1** |
| Gitee镜像 | gitee.com | gitee.com/api/v5 | gitee.com | 2 |
| cnpmjs镜像 | github.com.cnpmjs.org | api.github.com.cnpmjs.org | raw.githubusercontent.com.cnpmjs.org | 3 |
| ghproxy镜像 | mirror.ghproxy.com | mirror.ghproxy.com | mirror.ghproxy.com | 4 |
| 云盘加速 | gh.ddlc.top | gh.ddlc.top | gh.ddlc.top | 5 |
| 静态加速 | gh.api.99988866.xyz | gh.api.99988866.xyz | gh.api.99988866.xyz | 6 |
| 备用1 | gh-proxy.com | gh-proxy.com | gh-proxy.com | 7 |
| 备用2 | gh.llkk.cc | gh.llkk.cc | gh.llkk.cc | 8 |

## 🚀 快速开始

### 1. 安装插件
```bash
# 将 JAR 放入 plugins 目录
cp GitHubMirrorPlugin-1.0.0.jar /your/server/plugins/
```

### 2. **重要**: 添加 JVM 启动参数 (启用全局拦截)
在服务器启动脚本中添加 `--add-opens java.base/java.net=ALL-UNNAMED`：

```bash
# start.sh 示例
java --add-opens java.base/java.net=ALL-UNNAMED \
     -Xms2G -Xmx4G \
     -jar paper-1.20.4.jar --nogui
```

> ⚠️ **必须添加此参数**，否则无法注册 `URLStreamHandlerFactory`，全局拦截不会生效。
> 不添加参数时，插件仍可通过 API 和 HTTP 客户端拦截器工作。

### 3. 重启服务器
插件会自动：
- 生成配置文件 `plugins/GitHubMirrorPlugin/config.yml`
- 启动时测速所有镜像站
- 自动选择最快镜像
- 注册全局 URL 拦截器

## 🎮 命令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/githubmirror test` | 手动触发测速 | `githubmirror.admin` |
| `/githubmirror status` | 查看当前镜像状态 | `githubmirror.admin` |
| `/githubmirror list` | 列出所有镜像站 | `githubmirror.admin` |
| `/githubmirror set <名称>` | 手动设置镜像站 | `githubmirror.admin` |
| `/githubmirror reset` | 重置为默认镜像站 | `githubmirror.admin` |
| `/githubmirror url <链接>` | 转换 GitHub 链接为镜像链接 | `githubmirror.use` |
| `/githubmirror reload` | 重载配置 | `githubmirror.admin` |

**别名**: `/ghm`, `/ghmirror`

## ⚙️ 配置说明

主要配置项 (`config.yml`)：

```yaml
mirrors:           # 镜像站列表，可自行增删修改
speedtest:         # 测速参数
  timeout: 5000    # 超时毫秒数
  retries: 2       # 重试次数
  concurrent: 3    # 并发测速数
  interval: 6      # 自动测速间隔(小时)，0关闭
currentMirror: ""  # 当前选中镜像(自动更新)
autoSwitch: true   # 是否自动切换最快镜像
verbose: true      # 详细日志(显示重定向信息)
proxy:             # 代理设置(如需)
  enabled: false
  host: ""
  port: 0
events:            # 事件触发配置
  onEnable: true
  scheduled: true
  broadcastToOps: true
```

## 🔌 开发者 API

### 1. 通用 URL 转换 (所有场景适用)
```java
if (GitHubMirrorAPI.isAvailable()) {
    // 自动转换任意 GitHub 链接
    String original = "https://github.com/user/repo/releases/download/v1.0/file.jar";
    String mirrored = GitHubMirrorAPI.convertUrl(original);
    // 结果: https://hub.fastgit.org/user/repo/releases/download/v1.0/file.jar
}
```

### 2. Java 11+ HttpClient 自动重定向
```java
HttpClient client = GitHubMirrorAPI.createRedirectingHttpClient();
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("https://api.github.com/repos/user/repo"))
    .build();

// 同步请求 (自动重定向)
HttpResponse<String> response = GitHubMirrorAPI.sendIntercepted(client, request, 
    HttpResponse.BodyHandlers.ofString());

// 异步请求 (自动重定向)
CompletableFuture<HttpResponse<String>> future = GitHubMirrorAPI.sendInterceptedAsync(
    client, request, HttpResponse.BodyHandlers.ofString());
```

### 3. OkHttp 拦截器 (无硬依赖，通过反射)
```java
// 在你的插件中添加 okhttp 依赖后
OkHttpClient client = new OkHttpClient.Builder()
    .addInterceptor((okhttp3.Interceptor) GitHubMirrorAPI.createOkHttpInterceptor())
    .build();

// 所有请求自动重定向
Request request = new Request.Builder()
    .url("https://raw.githubusercontent.com/user/repo/main/config.yml")
    .build();
```

### 4. Apache HttpClient 拦截器
```java
CloseableHttpClient client = HttpClientBuilder.create()
    .addInterceptorFirst((HttpRequestInterceptor) GitHubMirrorAPI.createApacheHttpClientInterceptor())
    .build();
```

### 5. 低层级连接工具
```java
// 创建自动重定向的 URL
URL url = GitHubMirrorAPI.createRedirectingURL("https://github.com/user/repo");

// 打开自动重定向的连接
HttpURLConnection conn = GitHubMirrorAPI.openConnection("https://api.github.com/repos/user/repo");
// 或带代理
HttpURLConnection conn = GitHubMirrorAPI.openConnection(url, proxy);
```

### 6. 获取镜像信息
```java
String mirrorUrl = GitHubMirrorAPI.getMirrorUrl();      // https://hub.fastgit.org
String apiUrl = GitHubMirrorAPI.getApiUrl();            // https://api.fastgit.org
String rawUrl = GitHubMirrorAPI.getRawUrl();            // https://raw.fastgit.org
String name = GitHubMirrorAPI.getCurrentMirrorName();   // FastGit (推荐)
MirrorSite mirror = GitHubMirrorAPI.getCurrentMirror(); // 详细信息
List<MirrorSite> mirrors = GitHubMirrorAPI.getAvailableMirrors(); // 可用列表
```

### Maven 依赖 (需将插件 jar 加入本地仓库)
```xml
<dependency>
    <groupId>com.githubmirror</groupId>
    <artifactId>GitHubMirrorPlugin</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

在 `plugin.yml` 中声明软依赖：
```yaml
softdepend: [GitHubMirrorPlugin]
```

## 🌐 支持的拦截域名

全局拦截器会自动重定向以下域名的所有请求：

- `github.com` - 主站、Releases、Git 操作
- `api.github.com` - REST API、GraphQL
- `raw.githubusercontent.com` - Raw 文件内容
- `objects.githubusercontent.com` - Release 附件、LFS 对象
- `avatars.githubusercontent.com` - 用户头像
- `camo.githubusercontent.com` - 图片代理
- `user-images.githubusercontent.com` - 用户上传图片
- `opengraph.githubassets.com` - Open Graph 图片
- `*.github.com` - 所有子域名
- `*.githubusercontent.com` - 所有 Raw 子域名

## 🔧 进阶用法

### 自定义镜像站
编辑 `config.yml` 的 `mirrors` 列表：
```yaml
mirrors:
  - name: "我的私有镜像"
    url: "https://my-mirror.example.com"
    apiUrl: "https://my-mirror.example.com/api"
    rawUrl: "https://my-mirror.example.com/raw"
    priority: 0        # 最高优先级
    enabled: true
```

### 配置代理 (服务器无法直连国外时)
```yaml
proxy:
  enabled: true
  host: "127.0.0.1"
  port: 7890
  username: "user"
  password: "pass"
```

### 禁用详细日志
```yaml
verbose: false  # 不输出重定向日志
```

## 📋 权限节点

- `githubmirror.admin` - 管理权限 (默认 OP)
- `githubmirror.use` - 使用权限 (默认 true)

## ❓ 常见问题

**Q: 全局拦截不生效？**
A: 检查启动脚本是否添加了 `--add-opens java.base/java.net=ALL-UNNAMED`。查看日志是否有 "URL 拦截器已注册"。

**Q: 测速全部失败？**
A: 检查服务器网络，或配置代理。查看控制台错误信息。

**Q: 其他插件如何集成？**
A: 使用 `GitHubMirrorAPI` 转换 URL，或添加 HTTP 客户端拦截器。

**Q: 支持哪些服务端？**
A: Paper/Spigot/Purpur/Folia 等基于 Bukkit API 的 1.18+ 服务端。Java 17+ 需要添加 JVM 参数。

**Q: 会不会影响性能？**
A: 拦截器仅在匹配 GitHub 域名时重定向，其他请求零开销。测速在异步线程进行。

## 📝 更新日志

### v1.0.0
- 全局 URLStreamHandlerFactory 拦截
- Java HttpClient / OkHttp / Apache HttpClient 拦截器支持
- 9 个内置国内镜像站
- 自动测速、智能切换、定时任务
- 完整命令行界面和开发者 API

## 📄 许可证

MIT License

## 🤝 贡献

欢迎提交 Issue 和 PR！
# 开发环境

## 依赖

- JDK 21；JAVA_HOME 指向 JDK，IDEA 的项目 SDK 和 Maven Runner 也使用21。
- MySQL 8，应用账号可以访问 zlowallpaper。
- FFmpeg/FFprobe：放入 PATH，或通过 FFMPEG_PATH、FFPROBE_PATH 配置绝对路径。
- 私有 COS 或 OSS 桶及对应凭据。
- Redis：可用 Docker Compose、已有实例，或 Windows 本机启动脚本。缓存不是认证依赖。

仓库包含 Maven Wrapper，无需安装系统 Maven。Maven 会下载 pom.xml 声明的框架和SDK；媒体工具和数据库不由 Maven 安装。

## 配置

将根目录 .env.example 复制为 .env；已有配置不要覆盖。默认 local profile 从**工作目录**导入该文件，所以命令行与IDEA工作目录均应为仓库根目录。

| 配置 | 含义 |
| --- | --- |
| MYSQL_PASSWORD | wallpaper_app 数据库账号密码 |
| AUTH_JWT_SECRET | 至少32随机字节的Base64编码 |
| STORAGE_PROVIDER | 新上传选择COS或OSS；示例默认COS |
| COS_BUCKET / COS_REGION / COS_SECRET_ID / COS_SECRET_KEY | COS桶、地域与访问凭据 |
| OSS_* | 使用OSS时填写对应配置 |
| REDIS_HOST / REDIS_PORT / REDIS_PASSWORD | 缓存连接 |
| COVER_CACHE_ENABLED | 是否读写Redis，示例默认true |
| COVER_CACHE_TTL_SECONDS | 封面缓存TTL，默认21600秒 |
| SERVER_PORT | 默认8080 |
| WALLPAPER_PUBLIC_BASE_URL | 返回封面地址时使用的API根地址 |
| WALLPAPER_CORS_ALLOWED_ORIGINS | 允许的浏览器来源 |
| WALLPAPER_STORAGE_ROOT | 本地遗留文件和清理任务使用的根目录 |

MYSQL_ROOT_PASSWORD 只供本机管理工具使用，应用运行不需要root。真实.env不提交；修改文件中的密码不会自动改变数据库账号密码。

application.yml 中的 cover-max-edge=1920、cover-jpeg-quality=2 控制封面处理；q是编码参数，不是画质百分比。接口前缀固定/api/v1。

## 运行

先按 [数据库说明](../database/README.md) 初始化，配置云存储和密钥。根目录执行：

```powershell
.\mvnw.cmd spring-boot:run
```

IDEA运行 ZloWallpaperApplication；启动后请求 GET http://127.0.0.1:8080/api/v1/health。健康接口只验证MySQL。若8080被占用，先停止已有后端，或同步调整服务端口和前端API地址。

## Redis

有Docker时执行 docker compose up -d redis。Windows便携实例可执行 scripts/start-redis.ps1，用 -RedisDirectory 指定程序位置；它只启动Redis，不启动后端。

便携启动脚本使用 scripts/redis-local.conf：仅监听127.0.0.1、256mb上限、allkeys-lru、不持久化。修改文件后需重启，对照运行实例的 CONFIG GET maxmemory，而不是只看配置文件。

当前 compose.yaml 使用单独的Redis配置和数据卷，并不会读取redis-local.conf；选用哪一种启动方式，就配置对应实例，不能把它们视为同一个配置文件。

scripts/start-backend.ps1 是本机便捷启动器，默认包含开发机JDK/缓存目录；其他电脑优先使用Wrapper或IDEA，可通过 -JavaHome 覆盖JDK目录。scripts/invoke-local-mysql.ps1 也是本机辅助工具，不是生产部署步骤。

## 当前运行边界

默认仅监听回环地址，适合本机联调。正式部署还需独立配置入口地址、HTTPS、凭据、数据库备份和存储访问权限。仓库不包含素材、云密钥或可直接使用的商城账号。

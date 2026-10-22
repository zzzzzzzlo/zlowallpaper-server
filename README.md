# ZloWallpaper Server

壁纸商城的 Spring Boot 后端，提供账号认证、商品管理、站内壁纸币购买、已购查询和受保护的原文件下载。网页、Qt 桌面客户端与管理员工作台通过同一套 HTTP 接口访问。

## 已实现的功能

- 注册、登录、刷新令牌、登出及个人资料/余额查询；BCrypt 密码哈希、JWT 访问令牌，MySQL 保存会话。
- 分类管理、商城分页/搜索/详情，管理员通过一次 multipart 请求创建或修改商品，支持草稿、上架与停售。
- 创建订单、免费领取和壁纸币付款；扣余额、订单状态和权益在同一事务提交，重复付款不重复扣币。
- 我的已购分页及短时下载授权；已购用户仍能读取自己购买的停售商品。
- 腾讯云 COS 私有存储，兼容已有阿里云 OSS 文件；长时间上传与数据库短事务分开，失败或替换文件由清理任务回收。
- Redis 缓存封面字节，同一 JVM 内合并相同封面的并发回源请求；每次仍查询商品可见性或权益。
- 上传封面生成长边最多 1920 像素、JPEG `q:v=2` 的静态图片，壁纸原文件保持原样。

充值、修改用户名/密码、取消订单、退款和外部支付尚未开放后端接口。站内币付款会实际扣减余额，不依赖测试付款开关。

## 技术栈

| 技术 | 用途 |
| --- | --- |
| Java 21、Spring Boot 3.5.16 | HTTP、配置、参数校验和事务 |
| MySQL 8、MyBatis-Plus 3.5.17 | 数据存储、Mapper XML、分页与行锁 |
| Spring Security Crypto、JJWT | BCrypt 和 JWT |
| Spring Data Redis、Lettuce | 封面字节缓存 |
| 腾讯云 COS SDK、阿里云 OSS SDK | 私有文件上传、读取、签名和删除 |
| FFprobe、FFmpeg | 媒体检查、首帧解码和封面压缩 |
| JUnit 5、Mockito、Spring Boot Test、H2 | 自动化测试 |

未引入 Spring Security Web 过滤链；身份校验由项目的 `AuthFilter` 完成。Redis 不保存会话或购买权限。

## 本地运行

准备 JDK 21、MySQL 8、FFmpeg/FFprobe 和可访问的私有 COS 或 OSS 存储桶。Redis 用于封面加速，关闭或不可用时仍可回源。

1. 按 [数据库初始化说明](database/README.md) 初始化空库并准备应用账号。
2. 将 `.env.example` 复制为 `.env`，填写数据库密码、JWT 密钥及云存储配置。已有配置无需覆盖。
3. 可选：执行 `docker compose up -d redis`，或连接自己的 Redis 实例。
4. 在仓库根目录运行：

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

Linux/macOS 使用 `./mvnw spring-boot:run`。IDEA 使用 JDK 21，运行 `ZloWallpaperApplication`，工作目录设为仓库根目录。

默认地址为 `http://127.0.0.1:8080/api/v1`。`GET /health` 只检查 MySQL，不代表 Redis、云存储和媒体工具全部就绪。配置细节见 [开发环境](docs/development-environment.md)。默认仅监听回环地址，面向本地开发。

## 构建与测试

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
```

默认测试使用隔离的 H2 数据库和云存储替身，不访问真实商城库或云端文件。真实媒体工具及 MySQL 专项须显式开启，见 [测试说明](docs/integration-testing.md)。

## 代码与文档入口

```text
src/main/java/com/zlo/wallpaper/
  auth/          账号、令牌、会话和用户查询
  catalog/       分类、商品、封面、上传和存储适配
  order/         下单、余额付款和订单查询
  entitlement/   购买权益数据访问
  download/      原文件下载授权
  common/        响应、异常、请求上下文和过滤器
  config/        分页、缓存、存储和媒体工具配置
src/main/resources/mapper/   Mapper XML
src/test/                    正式自动化测试
database/                    初始化和增量 SQL
docs/                        当前设计与接口说明
scripts/                     本地启动和数据库辅助命令
```

建议先看 [文档索引](docs/README.md)：

- [接口调用链与技术说明](docs/api-call-chains.md)：公共组件、25 个接口的顺序、SQL 和事务边界。
- [OpenAPI](docs/api/openapi.yaml)：导入 Apifox 的请求和响应契约。
- [数据模型](docs/data-model.md)、[封面缓存](docs/cover-cache-development.md)、[封面压缩](docs/cover-compression-development.md)。

完整业务顺序尽量保留在入口 Service 方法中。并发变化要求的第二次检查标明“并发复查”，便于沿一条业务链阅读。

本仓库只包含后端。[前端仓库](https://github.com/zzzzzzzlo/zlowallpaper) 包含网页、客户端和工作台。本机凭据、截图、素材及一次性实验产物不提交。

# ZloWallpaper：已实现接口调用链与技术说明

核对日期：2026-10-16。以本机当前 Java、Mapper XML、配置和打包依赖为依据。

本文覆盖 **25 个已经实现的后端 HTTP 接口**，统一前缀为 `/api/v1`，下文路径省略此前缀。先说明公共调用类，再按接口列出实际执行顺序、读写的数据和使用的技术。`A → B` 表示直接调用；链路中的分支和事务边界会单独写出。

源码根目录：`E:\zlowallpaper_sever`。公共层在 `src/main/java/com/zlo/wallpaper` 下；数据库 SQL 主要在 `src/main/resources/mapper`。本文中的源码链接均指向本项目文件。

## 一、先看公共调用类

### 1.1 每个请求先经过什么

```text
客户端 HTTP 请求
  → 内嵌 Tomcat / Servlet 容器
  → RequestIdFilter：生成本次请求编号
  → WebConfig 注册的 CorsFilter：处理浏览器跨域
  → AuthFilter：公开接口放行；私有接口认证；管理接口再检查 ADMIN
      → AuthServiceImpl.authenticate
          → JwtUtil.parseAccessToken
          → SessionMapper.selectById
          → UserMapper.selectById
      → BaseContext.setCurrentId
  → Spring MVC DispatcherServlet：找到 Controller，绑定/校验 DTO
  → Controller → Service 接口实际对应的 ServiceImpl → Mapper / OSS
  → Controller 组织成功响应，Jackson 序列化 JSON
  → AuthFilter finally 清理 BaseContext
```

上面是正常放行的路径。CORS 预检可能提前结束；认证失败也在过滤器中结束，不会进入 Controller。公开接口不会因为附带一个令牌就自动执行私有认证。Spring MVC 对 GET 路由提供的 HEAD 支持、CORS 的 OPTIONS 不另算业务接口。

### 1.2 过滤、身份、统一响应与异常

| 公共类 / 方法 | 做什么 | 谁调用、在哪里生效 |
| --- | --- | --- |
| [RequestIdFilter][request-id]：`doFilterInternal`、`current` | 给请求生成 UUID，写入 request attribute 和 `X-Request-Id`；后续组件读取同一个编号 | Servlet 过滤器链首先执行；Controller、认证错误和异常处理读取编号 |
| [WebConfig][web-config]：`corsFilter` | 注册 Spring `CorsFilter`；允许配置的工作台来源、HTTP 方法、Authorization/Content-Type，暴露 X-Request-Id | 浏览器访问 API 时生效；它负责跨域，不负责登录或管理员身份 |
| [AuthFilter][auth-filter]：`doFilterInternal`、`isPublic`、`writeError` | 解析 Bearer；认证私有请求；`/admin/**` 要求当前角色为 ADMIN；直接写统一认证错误 JSON | 每个请求进入 MVC 前执行；401/403 不需要每个 Controller 再写一遍 |
| [AuthServiceImpl][auth-service]：`authenticate` | 验 JWT 后还查 sessions 与 users，检查会话归属、撤销/过期、账号状态，使用数据库中的当前角色 | `AuthFilter`；不是只看 JWT 签名就放行 |
| [BaseContext][base-context] | 用 `ThreadLocal<Long>` 保存本次请求可信用户 ID；`setCurrentId/getCurrentId/removeCurrentId` | 过滤器设置和清理；订单、当前用户、已购、上传、下载等 Service 读取；不是会话数据库或永久缓存 |
| [ApiResponse][api-response]：`success`、`failure` | JSON 外壳统一为 `code/message/data/requestId`；成功 code=OK，业务错误 data=null | Controller 与错误出口；它不决定数据库事务，也不把所有 HTTP 状态改为 200 |
| [BusinessException][business-exception] | 携带 HTTP 状态、业务 code 和可读消息 | Service/公共工具抛出；进入 MVC 后交给全局处理器，认证阶段由 AuthFilter 捕获 |
| [GlobalExceptionHandler][global-errors] | `@RestControllerAdvice` 统一处理业务、绑定、JSON、请求格式、数据库不可用、未知错误 | Controller/Service 抛出的异常出口；不会自动拦住发生在 MVC 之前的过滤器异常 |
| [PageVO][page-vo] | 分页结果 `{items,page,pageSize,total}` | 公开和管理员商品列表；不是分页插件本身 |

常见统一状态：参数或绑定错误 400、未登录/会话失效 401、权限不足 403、资源不存在 404、HTTP 方法错误 405、业务冲突 409、文件过大 413、请求或文件类型不支持 415、媒体内容不合格 422、上传忙 429、未知错误 500、依赖暂不可用 503。各接口还会使用自己的错误 code。

封面接口成功时返回的是**图片字节**，不是 `ApiResponse` JSON；失败时仍可能返回统一错误 JSON。调用方应同时看 HTTP 状态和 Content-Type。

### 1.3 认证、参数处理与基础配置

| 公共类 / 组件 | 作用与调用方 |
| --- | --- |
| [JwtUtil][jwt-util] | 使用 JJWT 签发/解析 HS256 accessToken。JWT 的 sub 是用户 ID，sid 是会话 ID；校验 issuer、kind、算法和期限。AuthServiceImpl 调用 |
| [TokenGenerator][token-generator] | `SecureRandom` 生成 32 随机字节的 refreshToken，Base64URL 编码；`hash` 用 SHA-256。数据库只存刷新令牌哈希，登录/刷新/登出调用 |
| [PasswordConfig][password-config] | 提供 `Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()` 与 UTC `Clock`；启用定时调度和认证配置绑定。密码哈希使用 PBKDF2，令牌哈希使用 SHA-256，两者用途不同 |
| [AuthProperties][auth-properties] | 读取 `wallpaper.auth`。当前 accessToken 配置 691200 秒，即 8 天；会话组上限 30 天；实际签发还受会话剩余期限限制 |
| [CredentialsDTO][credentials-dto]、[StrictStringDeserializer][strict-string] | 注册/登录 DTO 共用用户名和密码规则；Jackson 严格要求字符串。`@Valid` 触发 Jakarta Bean Validation；不是数据库查询 |
| [CatalogIds][catalog-ids] | `validatePathId` 检查外部 ID 格式，`findDatabaseId` 把字符串转为可用数据库 Long；它不查询数据库。商品、分类、订单、封面、下载复用 |
| [CategoryNames][category-names]、[CategoryFieldDeserializers][category-deserializers] | 分类名称规范化，以及分类 JSON 字段严格解析；供分类 DTO / Service 使用 |
| [MybatisPlusConfig][pagination-config] | 注册 `MybatisPlusInterceptor → PaginationInnerInterceptor(DbType.MYSQL)`；最大每页 50，overflow=false。MyBatis 运行 Mapper SQL 时插件补分页/COUNT |
| Spring IoC、`@Service/@Component/@Mapper/@Autowired` | Spring 创建并注入业务对象；MyBatis 为 Mapper 接口生成代理。Controller 依赖 Service 接口，运行时进入它的 Impl；业务代码不需要逐层 new |
| Spring 事务代理 | 跨 Spring Bean 调用 `@Transactional` 方法时进入事务代理。调用结束提交，运行时异常通常回滚；刷新会话有明确的 `noRollbackFor` 例外 |

普通 JSON 由 `@RequestBody` + Jackson 绑定；查询参数和 multipart 资料由 `@ModelAttribute` 绑定。商品 multipart 的 `configureBinding` 是 Spring 在绑定阶段调用的 `@InitBinder` 回调，之后接口方法才调用 `checkForm`；不是 Service 手动再去执行一套绑定。

阅读模型类时可按下面的方向理解：DTO 是传进来的资料；Entity 是数据库实体；Row 是关联查询需要的中间结果；VO 是传回前端的字段。`WallpaperRow/CategoryRow/AdminCategoryRow/CoverRow/OrderProductRow/DownloadFileRow` 都不是新表。`UploadedFile` 承接可信文件元数据，`StoredObject` 表示 OSS 对象位置，`WallpaperSaveResult` 在商品修改后带回当前商品和待回收旧文件；`OrderCreateResult` 带回订单与 created 标志，供 Controller 选 201/200。

### 1.4 文件业务的公共调用类

| 类 / 入口方法 | 职责 | 调用者与边界 |
| --- | --- | --- |
| [FileUploadServiceImpl][file-upload]：`uploadFile` | 单份文件的大小/扩展名、临时复制与 SHA-256、媒体解析、OSS 上传；返回可信 `UploadedFile` 元数据 | AdminWallpaperServiceImpl 的创建/修改；**没有独立上传 HTTP 接口**，不在这里保存商品 |
| [MediaInspector][media-inspector]：`inspect` | `checkSignature` 查真实文件头；调用 ffprobe 查格式/宽高/帧率，再用 ffmpeg 实际解码首帧；不转码 | FileUploadServiceImpl；外部进程通过 ProcessBuilder 参数列表启动，单次配置超时 20 秒 |
| [OssStorage][oss-storage] | `upload` 小文件直接上传、大文件分片；`readCover` 读封面；`createDownloadUrl` 签原文件临时地址；`deleteObject` 精确删除对象/存储版本 | 文件上传、CoverService、DownloadServiceImpl、清理服务；封装阿里云 OSS Java SDK |
| [WallpaperPersistenceService][wallpaper-persistence] | `create/update/publish` 在短数据库事务内写商品；重新核对权限、分类、商品文件快照 | AdminWallpaperServiceImpl 调用的另一 Spring Bean；长时间 OSS 上传发生在进入这些事务之前 |
| [ProductFileCleanupService][file-cleanup] | `trackUpload` 登记待清理对象；`completeUpload` 结束跟踪；`rollbackUpload` 回收失败上传；`retireFile` 回收替换前文件 | 商品 Service 编排；先用 `AdminWallpaperMapper.countFileReferences` 查引用，确认无人引用才删除 OSS 对象。失败保留本地 JSON 任务重试 |
| [CoverService][cover-service]：`read/readAdmin/readOwned → readFile` | 三种可见性分别查数据库后，共用封面读取；OSS 读字节，本地旧数据走受根目录限制的文件路径 | 公开/已购 CoverController 和管理员商品 Controller；成功返回 Resource、媒体类型与大小 |
| [AssetConfig][asset-config]、[OssProperties][oss-properties]、[UploadProperties][upload-properties] | 绑定 OSS 和媒体工具配置 | 当前属于商品文件配置；AssetConfig 名称保留，不表示仍有 assets 表或独立资产模块 |

上传使用 JVM 内的 `Semaphore(2)`，拿不到槽位立即返回 `429 UPLOAD_BUSY`；处理完在 finally 释放。它限制同一进程同时处理的**文件任务**，不是全系统最多两个商品请求，也不是 Redis 分布式锁。一次创建顺序处理封面、原文件。

文件清理是 **OSS 与数据库之间的补偿处理**。`@Transactional` 只能回滚数据库写入，不能把已上传的 OSS 字节一并回滚；因此保存失败时另行删除，删除失败时留任务重试。不能把它理解成跨 MySQL/OSS 的分布式事务。

## 二、当前使用的技术、第三方库与插件

版本来自 `pom.xml` 和当前 `target/zlowallpaper-server-0.0.1-SNAPSHOT.jar` 的 BOOT-INF/lib，不是对互联网“最新版”的判断。配置依据：[pom.xml][pom]、[application.yml][app-yaml]、[本机配置][local-yaml]。

| 技术 / 依赖 | 本项目版本或来源 | 实际使用位置 |
| --- | --- | --- |
| Java | 项目目标 Java 21 | 业务代码、NIO 文件、MessageDigest、SecureRandom、Semaphore、ProcessBuilder、UTC 时间 |
| Spring Boot | 3.5.16 | 自动配置、启动、依赖管理、内嵌服务器 |
| Spring MVC / spring-webmvc | 6.2.19 | 路由、Controller、JSON/multipart 参数绑定、Resource 图片响应 |
| Tomcat | 10.1.55 | Servlet 容器、HTTP 接收、multipart 基础处理 |
| Jackson databind | 2.21.4 | JSON DTO/VO、JWT JSON 适配、ffprobe 输出、清理任务 JSON |
| Jakarta Validation / Hibernate Validator | 实现 8.0.3.Final | `@Valid`、`@NotNull/@Pattern/@Min/@Max/@AssertTrue` 等参数约束 |
| Spring Security Crypto | 6.5.11 | PBKDF2 密码编码与比较；**未使用完整 Spring Security 登录过滤器链**，认证入口是自写 AuthFilter |
| JJWT | 0.13.0（api/impl/jackson） | JWT 签发和验签 |
| MyBatis | 3.5.19；mybatis-spring 3.0.5 | Mapper 动态代理、XML SQL、参数绑定、查询结果映射 |
| MyBatis-Plus | 3.5.17 | 分页 Page/IPage、MybatisPlusInterceptor 与 PaginationInnerInterceptor；未引入 PageHelper |
| JSqlParser | 5.2，经 MP 分页解析依赖引入 | 分页 COUNT SQL 解析与改写；不是业务 JOIN 的执行引擎 |
| MySQL / InnoDB | 本地关系数据库；驱动 Connector/J 9.7.0 | 六张业务表、事务、`FOR UPDATE` 行锁、唯一约束、外键；驱动版本不等于 MySQL 服务端版本 |
| HikariCP | 6.3.3 | 数据库连接池；local 配置最大 10 连接 |
| Spring Transaction / spring-tx | 6.2.19 | `@Transactional`，下单、付款、分类写操作、会话轮换、商品短事务 |
| 阿里云 OSS Java SDK | aliyun-sdk-oss 3.18.4，V1 风格 | OSS V4 签名、上传、分片、读封面、预签名下载、删除；不是 AWS SDK |
| ffprobe / ffmpeg | 外部可执行工具，路径由配置提供 | 上传时媒体探测与首帧解码；不是 Maven 媒体库，不自动转码 |
| Lombok | Maven 编译依赖，由 Boot 管理版本 | DTO、VO、Entity 的 getter/setter 等生成；不参与接口调用或运行时权限判断 |
| Spring Scheduling | Spring 内置，PasswordConfig 启用 | 会话清理、失败文件清理重试；不是 Quartz、XXL-JOB |
| SLF4J / Boot 日志体系 | Boot 管理 | requestId、依赖不可用、OSS 错误、清理重试等日志 |
| Spring Boot Maven Plugin | 与 Boot 父工程对应 | 打包可执行 JAR；属于构建插件，不是每个 HTTP 请求执行的组件 |
| Spring Boot Test / JUnit / MockMvc / Mockito | test 范围依赖 | Controller、Service、事务和错误分支测试，不是生产业务链 |
| H2 | test 范围依赖 | 隔离集成测试数据库；真实运行使用 MySQL |

**已引入但业务尚未使用：** `spring-boot-starter-data-redis`（打包中的 spring-data-redis 为 3.5.13），local 也有 Redis 地址配置，但目前业务代码没有 RedisTemplate/StringRedisTemplate/缓存注解的调用。登录、订单防重、扣币均不能描述为“用 Redis 实现”。健康接口的旧注释提到 Redis，实际方法只执行 MySQL `SELECT 1`。

**接口文档工具：** 当前维护静态 [OpenAPI YAML][openapi]，用 Apifox 导入测试；没有引入 springdoc/Swagger UI 自动生成库。Apifox 是外部调试工具，不在后端执行链中。

**前端消费者使用的技术：** Qt 客户端采用 C++17、Qt6 Widgets/Network/Concurrent，当前运行包 Qt 6.11.1（CMake 要求至少 6.8）。`ApiClient` 发 HTTP，`StoreService` 编排接口，信号/槽更新 UI；`QPropertyAnimation` 实现渐隐提示，`QSaveFile` 原子保存，Windows DPAPI 保护登录凭据，libmpv/FFmpeg 提供媒体能力。工作台采用原生 HTML/CSS/ES Modules，`api.js` 封装请求，fetch 与 multipart XMLHttpRequest 分别处理普通调用和上传进度；Node 提供本地静态服务，Playwright/Node Test/Prettier 属于开发测试工具。它们不是后端 Service 依赖。

## 三、25 个接口索引

“公开”指不要求本系统 accessToken；刷新和登出仍要求请求体携带 refreshToken。所有管理员入口统一由 AuthFilter 检查 ADMIN。

| 编号 | 方法与路径 | 权限 | 功能 |
| --- | --- | --- | --- |
| [01](#api-01) | POST `/auth/register` | 公开 | 注册普通用户 |
| [02](#api-02) | POST `/auth/login` | 公开 | 密码登录并建立会话 |
| [03](#api-03) | POST `/auth/refresh` | 公开，验证 refreshToken | 轮换登录令牌 |
| [04](#api-04) | POST `/auth/logout` | 公开，提交 refreshToken | 撤销本次登录组 |
| [05](#api-05) | GET `/me` | 登录 | 当前用户和余额 |
| [06](#api-06) | GET `/categories` | 公开 | 启用分类 |
| [07](#api-07) | GET `/wallpapers` | 公开 | 上架商品分页 |
| [08](#api-08) | GET `/wallpapers/{id}` | 公开 | 上架商品详情 |
| [09](#api-09) | GET `/catalog/covers/{id}` | 公开 | 上架商品封面 |
| [10](#api-10) | GET `/admin/categories` | 管理员 | 完整分类及引用数 |
| [11](#api-11) | POST `/admin/categories` | 管理员 | 新增分类 |
| [12](#api-12) | PATCH `/admin/categories/{id}` | 管理员 | 修改分类 |
| [13](#api-13) | DELETE `/admin/categories/{id}` | 管理员 | 删除无商品引用分类 |
| [14](#api-14) | GET `/admin/wallpapers` | 管理员 | 所有发布状态商品分页 |
| [15](#api-15) | POST `/admin/wallpapers` | 管理员 | 一次提交资料与两份文件 |
| [16](#api-16) | PATCH `/admin/wallpapers/{id}` | 管理员 | 修改资料和可选替换文件 |
| [17](#api-17) | PUT `/admin/wallpapers/{id}/publication` | 管理员 | 上架/下架 |
| [18](#api-18) | GET `/admin/wallpapers/{id}/cover` | 管理员 | 草稿/上架/停售封面 |
| [19](#api-19) | POST `/orders` | 登录 | 新建或复用订单 |
| [20](#api-20) | GET `/me/orders` | 登录 | 本账号订单列表 |
| [21](#api-21) | POST `/orders/{id}/pay` | 登录 | 壁纸币付款 |
| [22](#api-22) | GET `/me/wallpapers` | 登录 | 本账号已购壁纸，含停售 |
| [23](#api-23) | GET `/me/wallpapers/{id}/cover` | 登录且有权益 | 本账号已购封面 |
| [24](#api-24) | POST `/wallpapers/{id}/download-grants` | 登录且有权益 | 申请五分钟下载授权 |
| [25](#api-25) | GET `/health` | 公开 | MySQL 就绪检查 |

## 四、认证与个人信息

<a id="api-01"></a>
### 01. 注册：POST /auth/register

输入：JSON `RegisterDTO`，包含 username、password；201 返回 `RegisterVO {username}`，不会自动登录。

```text
AuthController.register
  → AuthService.register → AuthServiceImpl.register [数据库事务]
      → UserMapper.selectByUsername
      → PasswordEncoder.encode：PBKDF2
      → UserMapper.insert：生成用户 ID
  → ApiResponse.success(RegisterVO)
```

SQL：查询/插入 users。服务器固定 role=USER、status=ACTIVE；余额初始为 0，不接受客户端设置管理员或余额。用户名先查重，插入时再由唯一约束处理并发同名，映射为 409 USERNAME_EXISTS。密码不以明文入库。

技术：Spring MVC、Jackson、Validation、PBKDF2、MyBatis、Spring 事务、MySQL 唯一约束。源码：[Controller][auth-controller] / [实现][auth-service] / [用户 SQL][user-xml]。

<a id="api-02"></a>
### 02. 登录：POST /auth/login

输入：JSON `LoginDTO`，username、password；200 返回 `SessionVO {accessToken,refreshToken,expiresIn,user}`。

```text
AuthController.login
  → AuthService.login → AuthServiceImpl.login [数据库事务]
      → UserMapper.selectByUsername
      → PasswordEncoder.matches
      → TokenGenerator.generateRefreshToken
      → createSession：TokenGenerator.hash → SessionMapper.insert
      → sessionVO：JwtUtil.createAccessToken + UserVO
  → ApiResponse.success(SessionVO)
```

SQL：读取 users、插入 sessions。账号不存在时仍用预生成 dummyPasswordHash 做一次密码比较；不存在、密码错误、账号不可用统一返回 401 INVALID_CREDENTIALS。每次独立登录生成新的 familyId；没有“登录新设备自动踢掉其他组”的业务。sessions 只保存 refreshToken 哈希。

实际 accessToken 期限=min(配置 8 天，会话组剩余时间)。user 仅含字符串 id、username、字符串 balanceCents；已删除 displayName。

技术：PBKDF2、SecureRandom、SHA-256、JJWT、事务与自增主键回填。源码：[Controller][auth-controller] / [实现][auth-service] / [会话 SQL][session-xml]。

<a id="api-03"></a>
### 03. 刷新：POST /auth/refresh

输入：JSON `RefreshTokenDTO {refreshToken}`；200 返回新 SessionVO，失败通常为 401 SESSION_EXPIRED。

```text
AuthController.refresh
  → AuthService.refresh → AuthServiceImpl.refresh [事务，BusinessException 不回滚]
      → lockSession
          → TokenGenerator.hash
          → SessionMapper.selectByRefreshHash
          → SessionMapper.lockFamily：锁同组最早一条会话
          → SessionMapper.selectByRefreshHashForUpdate：并发复查最新会话
      → 检查过期、撤销、用户 ACTIVE、组剩余时间
      → TokenGenerator.generateRefreshToken
      → createSession：同 familyId、同固定截止时间
      → SessionMapper.markRotated：旧会话标 ROTATED 并关联新会话
      → sessionVO → 新 JWT 与 refreshToken
```

SQL：查询/锁定 sessions，读取 users，插入新 sessions，更新旧 sessions；发现旧 ROTATED 令牌重放时 `revokeFamily(...,"REPLAY",...)`，用户失效时 `revokeFamily(...,"DISABLED",...)`。

**并发重点：** 刷新与登出共用组锁，拿到锁后再 FOR UPDATE 读取令牌记录，避免普通快照读看见轮换前状态。`noRollbackFor=BusinessException.class` 使“撤销整组后抛 401”仍提交撤销，不能为了统一异常回滚而删掉。组剩余不足 60 秒拒绝续签；刷新不把 30 天截止时间无限后移。

技术：Spring 事务例外配置、MySQL 行锁、令牌轮换与重放检测、JJWT。源码：[实现][auth-service] / [会话 SQL][session-xml]。

<a id="api-04"></a>
### 04. 登出：POST /auth/logout

输入：JSON `{refreshToken}`；不要求有效 accessToken，200 返回 `data: {}`。

```text
AuthController.logout
  → AuthService.logout → AuthServiceImpl.logout [数据库事务]
      → lockSession：哈希查询 → lockFamily → 锁定复查
      → SessionMapper.revokeFamily(...,"LOGOUT",...)
  → ApiResponse.success(空对象)
```

撤销对应 family 下全部会话。重复登出或查不到令牌也返回成功空对象；不删除账号、订单或权益。被撤销组的 accessToken 下次经 authenticate 查询 sessions 时就会被拒绝，不必等 JWT 自身到期。

技术：令牌哈希、组行锁、事务、数据库会话撤销。源码：[实现][auth-service] / [会话 SQL][session-xml]。

<a id="api-05"></a>
### 05. 当前用户：GET /me

输入：Bearer；不接收决定查询对象的 userId。200 返回 `UserVO {id,username,balanceCents}`。

```text
AuthFilter：认证后设置 BaseContext
  → UserController.getCurrentUser
  → UserService.getCurrentUser → UserServiceImpl.getCurrentUser
      → BaseContext.getCurrentId
      → UserMapper.selectById
      → 检查当前用户 ACTIVE → UserVO
  → ApiResponse.success
```

只查询 users，无写事务或扣款操作。过滤器取的是请求认证时的身份，Service 再读取用于本次返回的用户资料；不返回密码哈希、内部角色/状态，也没有修改个人资料功能。

技术：自写认证过滤器、ThreadLocal、MyBatis、VO 字段裁剪。源码：[Controller][user-controller] / [实现][user-service] / [用户 SQL][user-xml]。

## 五、公开分类与商品浏览

<a id="api-06"></a>
### 06. 启用分类：GET /categories

输入：无；200 返回 `CategoryVO[]`，每项字符串 id、name。

```text
CategoryController.getCategories
  → CategoryService.listEnabled → CategoryServiceImpl.listEnabled
      → CategoryMapper.selectEnabled
      → CategoryRow 转 CategoryVO
  → ApiResponse.success
```

SQL 在 **CategoryMapper.java 的 @Select**：`categories WHERE enabled=1 ORDER BY sort_order ASC,id ASC`。这是当前少数沿用注解的简单查询，无事务写入。停用分类不会出现在分类选择列表，但不因此自动下架已发布商品。

技术：MyBatis 注解 SQL、查询模型到 VO 转换。源码：[Controller][category-controller] / [实现][category-service] / [Mapper][category-mapper]。

<a id="api-07"></a>
### 07. 商城分页：GET /wallpapers

输入：`WallpaperQueryDTO`，page 默认 1、pageSize 默认 12 且最大 50、keyword、categoryId；200 返回 `PageVO<WallpaperVO>`。

```text
WallpaperController.page
  → WallpaperService.page → WallpaperServiceImpl.page
      → new Page；处理关键词、分类 ID
      → WallpaperMapper.selectPublishedPage
          → MyBatis-Plus 分页拦截器：COUNT + 分页查询
      → toVO：生成公开封面 URL
  → ApiResponse.success(PageVO)
```

SQL：wallpapers w **JOIN categories c ON c.id=w.category_id**，为了附上分类名称；只取 PUBLISHED 且封面/原文件 READY、有对象键的商品。标题 LIKE，`!/%/_` 转义后把用户通配符当普通字符；排序 published_at DESC、id DESC。分类停用不额外过滤商品。

COUNT 是插件改写，JOIN 是 SQL 关系，执行计划由 MySQL 决定，这三件事要分开理解。`setOptimizeJoinOfCountSql(false)` 只关闭 COUNT 的 JOIN 自动移除选项，不是禁止数据库优化。后端超末页返回空列表；客户端当前会在总数下降时重新请求有效末页。接口不是跨多次翻页保持不变的商品快照。

技术：MyBatis-Plus 分页、JSqlParser、SQL JOIN/LIKE、DTO 参数校验。源码：[Controller][wallpaper-controller] / [实现][wallpaper-service] / [商品查询 SQL][wallpaper-xml]。

<a id="api-08"></a>
### 08. 商城详情：GET /wallpapers/{id}

输入：商品字符串 id；200 返回 WallpaperVO，404 PRODUCT_NOT_FOUND 表示不存在或当前不可公开展示。

```text
WallpaperController.detail
  → WallpaperService.detail → WallpaperServiceImpl.detail
      → CatalogIds.validatePathId / findDatabaseId
      → WallpaperMapper.selectPublishedById
      → toVO → 公开封面地址
  → ApiResponse.success(WallpaperVO)
```

SQL 与公开分页共用 PUBLISHED、原文件/封面 READY 条件，附加 w.id。返回当前文件 SHA、大小、宽高等公开元数据，**不返回原文件 OSS 地址或对象键**。无写事务。

技术：MyBatis 关联查询、VO 裁剪、UriComponentsBuilder 构造 URL。源码：[实现][wallpaper-service] / [SQL][wallpaper-xml]。

<a id="api-09"></a>
### 09. 公开封面：GET /catalog/covers/{id}

输入：商品 id；成功 200，图片 Content-Type 与实际字节长度，`X-Content-Type-Options: nosniff`、Cache-Control no-cache。

```text
CoverController.read
  → CoverService.read
      → CatalogIds
      → WallpaperMapper.selectPublicCover
      → readFile
          ├─ OSS：OssStorage.readCover → OSS SDK getObject → ByteArrayResource
          └─ LOCAL：校验受限路径/实际大小 → FileSystemResource
  → ResponseEntity<Resource> 输出图片字节
```

SQL：wallpapers 的当前封面字段，要求商品 PUBLISHED、封面 READY、有对象键。它不额外执行公开商品详情查询。封面上限 4 MiB，核对 MIME/字节数；不存在或不可见为 404 RESOURCE_NOT_FOUND，存储不可用为 503。读操作不持写事务。

技术：Spring Resource、OSS SDK、本地 NIO、HTTP 缓存头。源码：[Controller][cover-controller] / [封面服务][cover-service] / [SQL][wallpaper-xml]。**此路由已实现，但当前静态 OpenAPI 尚未单列该路径；本文按源码完整纳入。**

## 六、管理员分类管理

<a id="api-10"></a>
### 10. 完整分类列表：GET /admin/categories

输入：管理员 Bearer；200 返回 `AdminCategoryVO[]`，含 id、name、sortOrder、enabled、wallpaperCount。

```text
AdminCategoryController.getAdminCategories
  → CategoryService.getAdminCategories → CategoryServiceImpl.getAdminCategories
      → CategoryMapper.selectAdminCategories
      → toAdminVO
  → ApiResponse.success
```

SQL：categories c **LEFT JOIN wallpapers w ON w.category_id=c.id**，按分类 GROUP BY，COUNT(w.id) 统计引用数；LEFT JOIN 保留没有商品的分类，COUNT(w.id) 使其计数为 0。包含启用/停用分类，商品计数覆盖 DRAFT/PUBLISHED/WITHDRAWN；按 sort_order、id 升序。不把引用数冗余存入分类表。

技术：SQL LEFT JOIN、GROUP BY、聚合、MyBatis XML。源码：[Controller][admin-category-controller] / [实现][category-service] / [分类 SQL][category-xml]。

<a id="api-11"></a>
### 11. 新增分类：POST /admin/categories

输入：JSON `CategoryCreateDTO`，name 必填，sortOrder/enabled 可选；201 返回 AdminCategoryVO。

```text
AdminCategoryController.createCategory
  → CategoryService.createCategory → CategoryServiceImpl.createCategory [事务]
      → CategoryNames.normalize
      → checkNameAvailable → CategoryMapper.selectByName
      → CategoryMapper.insert
      → CategoryMapper.selectAdminById → toAdminVO
  → ApiResponse.success
```

SQL：查询名称、插入 categories、关联查询刚创建分类。省略 sortOrder 默认 0，enabled 默认 true。先查重提供清晰错误，并发插入仍靠数据库名称唯一约束，冲突转为 409 CATEGORY_NAME_EXISTS。名称按当前数据库排序规则判重，不用 Java 检查替代唯一键。

技术：Validation、自定义字段解析、Unicode 名称规范化、事务、唯一约束。源码：[实现][category-service] / [SQL][category-xml]。

<a id="api-12"></a>
### 12. 修改分类：PATCH /admin/categories/{id}

输入：`CategoryUpdateDTO`，仅提交需要改的 name/sortOrder/enabled；200 返回 AdminCategoryVO。

```text
AdminCategoryController.updateCategory
  → CategoryService.updateCategory → CategoryServiceImpl.updateCategory [事务]
      → getCategoryForUpdate → CatalogIds → CategoryMapper.selectByIdForUpdate
      → 如改名称：normalize → checkNameAvailable（排除本分类）
      → CategoryMapper.update：只更新非 null 字段
      → CategoryMapper.selectAdminById → toAdminVO
  → ApiResponse.success
```

行锁串行修改同一分类；不存在 404 CATEGORY_NOT_FOUND，重名 409。XML 的非 null 判断允许 false 和 0 真正写入，不会把它们当作未提交。停用分类不会联动下架商品。

技术：MySQL FOR UPDATE、Spring 事务、MyBatis 动态 `<set>/<if>`。源码：[实现][category-service] / [SQL][category-xml]。

<a id="api-13"></a>
### 13. 删除分类：DELETE /admin/categories/{id}

输入：分类 id；200 返回 `data: {}`，不是 204。

```text
AdminCategoryController.deleteCategory
  → CategoryService.deleteCategory → CategoryServiceImpl.deleteCategory [事务]
      → getCategoryForUpdate → CategoryMapper.selectByIdForUpdate
      → CategoryMapper.countWallpapers
      → 没有引用才 CategoryMapper.deleteById
  → ApiResponse.success(空对象)
```

分类被任意状态商品引用都不能删，409 CATEGORY_IN_USE；不存在 404。分类行锁与外键一起保护“检查引用后删除”的过程，捕获外键完整性异常也映射为分类被引用；不级联删除商品，不自动改为停用。

技术：事务、行锁、COUNT、外键约束。源码：[实现][category-service] / [SQL][category-xml]。

## 七、管理员商品与文件

<a id="api-14"></a>
### 14. 管理员商品分页：GET /admin/wallpapers

输入：`AdminWallpaperQueryDTO {page,pageSize}`，默认 1/12、最大每页 50；仅接收这两个查询参数。200 返回 PageVO<AdminWallpaperVO>。

```text
AdminWallpaperController.getWallpapers
  → AdminWallpaperService.getWallpapers → AdminWallpaperServiceImpl.getWallpapers
      → Page → AdminWallpaperMapper.selectPage → MP 分页拦截器
      → toVO：当前文件元数据 + 管理员封面地址
  → ApiResponse.success
```

SQL：wallpapers JOIN categories 取得分类名称，不按发布状态筛选，按商品 id DESC。XML resultMap 用 association 把商品行里的 original_*、cover_* 映射成两个 UploadedFile 中间对象；**这不代表另查了 assets 表**。VO 包含状态、两份文件名等，不返回原文件下载地址。

技术：MP 分页、XML resultMap/association、DTO、VO。源码：[Controller][admin-wallpaper-controller] / [实现][admin-wallpaper-service] / [管理商品 SQL][admin-wallpaper-xml]。

<a id="api-15"></a>
### 15. 创建商品：POST /admin/wallpapers

输入：**multipart/form-data**，`WallpaperMutationDTO`；title、categoryId、priceCents、originalFile、coverFile 必填，description/creator 可选。不是先传 assetId，再保存商品。201 返回 AdminWallpaperVO，新商品 DRAFT。

```text
Spring 绑定阶段：AdminWallpaperController.configureBinding
AdminWallpaperController.createWallpaper
  → checkForm：绑定错误、未知/重复字段
  → AdminWallpaperService.createWallpaper → AdminWallpaperServiceImpl.createWallpaper
      1. validateMutation(dto,true)：一次集中检查普通资料
      2. persistence.checkCategoryBeforeUpload → checkCategory(...,false)
          → CategoryMapper.selectById：普通读预检查
      3. uploader.uploadFile(coverFile,"COVER") → cleanup.trackUpload
      4. uploader.uploadFile(originalFile,"ORIGINAL") → cleanup.trackUpload
      5. persistence.create [独立 Spring Bean 的短事务]
          → checkAdministrator → UserMapper.selectById
          → checkCategory(...,true) → CategoryMapper.selectByIdForUpdate
          → 组装 Wallpaper：DRAFT、ZLC、两份当前文件元数据
          → AdminWallpaperMapper.insert
      6. 事务已提交：cleanup.completeUpload 两份文件 → toVO
  → ApiResponse.success，HTTP 201
异常分支：cleanup.rollbackUpload 已拿到元数据的文件 → 原异常继续抛出
```

第 3、4 步内部：FileUploadServiceImpl.uploadFile → Semaphore 槽位 → copyAndHash → MediaInspector.inspect → OssStorage.upload。OSS 小于等于 8 MiB 用 putObject，更大用每片最多 8 MiB 的顺序分片上传；不是前端直传 OSS，不把大文件整体读入内存。上传组件本身不插入商品。

**并发复查：** 上传前普通读分类，尽早拒绝无效分类；上传后在事务里 FOR UPDATE 读取并锁分类，保护检查之后到写入提交之间的分类状态。账号权限在请求开始已查，长上传后写库前再查一次当前管理员状态；此用户查询不加行锁，不能宣称它锁住了此后所有角色变化。

数据库只写 wallpapers；原文件/封面位置与元数据一起写商品。事务提交后才完成跟踪；失败时补偿清理。错误包括 CATEGORY_NOT_FOUND/CATEGORY_DISABLED、UNSUPPORTED_FILE_TYPE、INVALID_MEDIA、UPLOAD_BUSY、OSS 配置/权限故障等，不把失败当创建成功。

技术：multipart、WebDataBinder、字段白名单、严格整数绑定、Semaphore、流式 SHA-256、ffprobe/ffmpeg、OSS SDK 分片、短事务、行锁、补偿任务。源码：[Controller][admin-wallpaper-controller] / [编排][admin-wallpaper-service] / [事务写入][wallpaper-persistence] / [上传][file-upload] / [SQL][admin-wallpaper-xml]。

<a id="api-16"></a>
### 16. 修改商品：PATCH /admin/wallpapers/{id}

输入：multipart/form-data；只提交需要修改的资料，可选 originalFile/coverFile；200 返回当前 AdminWallpaperVO。未提交的字段/文件保留，不维护历史版本。

```text
configureBinding → AdminWallpaperController.updateWallpaper → checkForm
  → AdminWallpaperService.updateWallpaper → AdminWallpaperServiceImpl.updateWallpaper
      1. validateMutation(dto,false)
      2. AdminWallpaperMapper.selectById：读取上传前商品快照
      3. 如提交分类：checkCategoryBeforeUpload（允许保留原停用分类）
      4. 按需 uploadFile 新封面 / 新原文件，各自 trackUpload
      5. WallpaperPersistenceService.update [短事务]
          → checkAdministrator
          → AdminWallpaperMapper.selectByIdForUpdate：最新商品
          → sameFile：比较本次要替换的文件与上传前快照
          → 如改分类：锁分类并复查
          → 合并本次提交的字段和文件
          → 若仍是 PUBLISHED：checkReady
          → AdminWallpaperMapper.update → selectById
          → WallpaperSaveResult（当前商品 + 被替换文件）
      6. completeUpload 新文件 → retireFile 旧文件 → toVO
异常分支：rollbackUpload 本次新文件，不删除仍被商品引用的文件
```

**并发复查：** 上传前只读取快照，不长时间锁商品；上传后锁最新商品，只比较本次确实替换的文件（provider/bucket/key/versionId）。若其他请求已替换同一文件，返回 409 PRODUCT_CHANGED 并回收本次上传；普通资料在最新行上按提交字段合并，避免整份旧快照覆盖别人的修改。

旧文件在数据库提交后回收；`countFileReferences` 不是查权益，而是查商品是否仍引用该 OSS bucket/key。保存资料不自动上下架。没有新文件时不会重复上传 OSS；上架完整性检查仍保护保存后状态。

技术：按需 multipart、商品行锁、文件快照冲突、动态 UPDATE、事务后 OSS 回收。源码：[编排][admin-wallpaper-service] / [事务写入][wallpaper-persistence] / [清理][file-cleanup] / [SQL][admin-wallpaper-xml]。

<a id="api-17"></a>
### 17. 上下架：PUT /admin/wallpapers/{id}/publication

输入：JSON `WallpaperPublicationDTO {status}`，只接受 PUBLISHED 或 WITHDRAWN；200 返回 AdminWallpaperVO。

```text
AdminWallpaperController.setPublication
  → AdminWallpaperService.setPublication → AdminWallpaperServiceImpl.setPublication
      → WallpaperPersistenceService.publish [事务]
          → checkAdministrator
          → AdminWallpaperMapper.selectByIdForUpdate
          → 上架时 checkReady
          → 状态确实改变才 updatePublication
          → selectById
      → toVO
  → ApiResponse.success
```

上架检查两份文件的 READY、位置、元数据与商品类型是否一致，不重复解码整份媒体。上架状态改变时写 published_at；下架保留原 published_at，同状态重复请求不重写时间。不存在 404 PRODUCT_NOT_FOUND，不完整 409 PRODUCT_NOT_READY。下架不删除订单、权益、文件，既有待付款订单和已购读取遵循各自规则。

技术：事务、商品行锁、状态迁移、元数据完整性检查。源码：[事务实现][wallpaper-persistence] / [SQL][admin-wallpaper-xml]。

<a id="api-18"></a>
### 18. 管理员预览封面：GET /admin/wallpapers/{id}/cover

输入：管理员 Bearer、商品 id；成功返回图片字节，Cache-Control no-store。

```text
AdminWallpaperController.getCover
  → CoverService.readAdmin
      → WallpaperMapper.selectAdminCover
      → readFile → OssStorage.readCover / 本地 Resource
  → ResponseEntity<Resource>
```

不要求商品 PUBLISHED，因此可预览草稿与停售商品；仍要求本商品封面 READY、有对象键。AuthFilter 先检查管理员，不通过公开封面接口放宽权限。不写数据库。

技术：公共封面服务、权限过滤、Spring Resource、OSS。源码：[Controller][admin-wallpaper-controller] / [封面服务][cover-service] / [SQL][wallpaper-xml]。

## 八、订单与壁纸币

三个订单接口共用 **九字段 OrderVO**：字符串 id/userId/wallpaperId、title、整数 amountCents、字符串 balanceCents、currency、status、createdAt。balanceCents 是本次读取的**当前余额**，不是订单历史余额，不增加订单表字段；100 个最小单位等于 1 枚壁纸币。新订单币种 ZLC，历史订单可能保留 CNY。源码：[OrderVO][order-vo]。

<a id="api-19"></a>
### 19. 创建或复用订单：POST /orders

输入：JSON `OrderCreateDTO {wallpaperId}`，不提交 userId/价格/状态；不需要 Idempotency-Key。新建 201，复用 200；data 同为 OrderVO。

```text
OrderController.createOrder
  → OrderService.createOrder → OrderServiceImpl.createOrder [完整数据库事务]
      1. BaseContext 用户 ID；解析商品 ID
      2. OrderMapper.selectBuyerStatusForUpdate：锁本用户并复查 ACTIVE
      3. UserMapper.selectBalanceById：读当前余额
      4. OrderMapper.selectProductForUpdate：锁商品，读当前价格/状态
      5. EntitlementMapper.selectForUpdate：当前权益
          有权益 → selectOwnedOrderForUpdate 读来源 PAID 订单 → 返回复用
      6. 无权益 → OrderMapper.selectActiveOrderForUpdate
          有 PENDING → 返回复用；PAID 却无对应权益 → 冲突
      7. 确需新订单：检查 PUBLISHED，按商品生成标题/金额/币种快照
      8. OrderMapper.insertOrder
          价格 0 → PAID/FREE → grantEntitlement → EntitlementMapper.insert
          价格 >0 → PENDING，尚未扣款、尚无权益
      9. result → toOrderVO(order,balanceCents)
  → OrderCreateResult.created 决定 Controller 的 201/200
```

同一事务读写 users、wallpapers、orders、entitlements；用户与商品行锁持有到事务结束。已有权益/待付款订单优先于“是否能新购买”的判断，因此商品下架后可返回原结果；无旧结果的新购买必须上架。商品不存在或不可新增购买为 404 PRODUCT_NOT_FOUND。

**并发重点：** 相同用户先锁同一用户行，串行判定权益/待付款订单；同一商品的下单与管理端调价/下架也共享商品行锁。数据库还有有效订单唯一约束、用户商品权益唯一约束兜底。没有请求键表；这里只实现当前业务结果复用，不能描述成“保存每个请求的历史响应”。免费订单与权益一起提交。

Qt 调用：StoreService.createOrder → ApiClient → 本接口 → acceptOrderBalance → orderCreated；收费结果打开 StorePage.showOrderConfirmation，关闭窗口不会发删除/取消请求。

技术：事务、FOR UPDATE、价格快照、防重、唯一/外键约束。源码：[Controller][order-controller] / [实现][order-service] / [订单 SQL][order-xml] / [权益 SQL][entitlement-xml] / [余额 SQL][user-xml]。

<a id="api-20"></a>
### 20. 我的订单：GET /me/orders

输入：Bearer，无业务请求体；200 返回全部状态 OrderVO[]，无记录为 []。暂不分页，超过 1000 条返回 409 ORDER_LIST_LIMIT_EXCEEDED。

```text
OrderController.getMyOrders
  → OrderService.getMyOrders → OrderServiceImpl.getMyOrders
      → BaseContext.getCurrentId
      → OrderMapper.selectOrdersByUserId(userId,1001)
      → 检查上限
      → UserMapper.selectBalanceById：整批只查一次当前余额
      → for 循环 toOrderVO(order,balanceCents)
  → ApiResponse.success
```

SQL：orders 按 user_id 隔离、created_at DESC/id DESC；不限制状态，不 JOIN 商品/权益，使用订单标题/金额快照，不受商品改名/下架影响。余额另查 users；不对每一行再查一次用户。普通读，不加 FOR UPDATE、不另开业务写事务，查询后状态仍可能改变。

Qt 调用：StoreService.fetchOrders → ordersLoaded → 我的订单窗口；PENDING 可打开同一确认窗口继续付款。

技术：受身份约束的 SQL、快照字段、limit+1 检测完整性、避免 N+1 余额查询。源码：[实现][order-service] / [订单 SQL][order-xml] / [用户 SQL][user-xml]。

<a id="api-21"></a>
### 21. 壁纸币付款：POST /orders/{id}/pay

输入：Bearer 与订单 id，无业务 JSON 字段；200 返回 PAID OrderVO，包含当前扣款后余额。**这是实际扣站内虚拟币的接口，不是 test-pay，也不调用银行卡/Stripe 等外部渠道。**

```text
OrderController.payOrder
  → OrderService.payOrder → OrderServiceImpl.payOrder [完整数据库事务]
      1. 当前用户 ID + 订单 ID
      2. OrderMapper.selectBuyerStatusForUpdate：先锁用户并复查 ACTIVE
      3. OrderMapper.selectOwnedOrderForUpdate：锁本账号订单
      4. EntitlementMapper.selectForUpdate
          已 PAID 且对应权益正常 → 查当前余额 → 返回，不再扣款
          CANCELED/REFUNDED → ORDER_NOT_PAYABLE
          权益与订单不一致 → PURCHASE_STATE_CONFLICT
      5. UserMapper.deductBalance：余额足够才执行余额减订单金额
      6. OrderMapper.updatePaidStatus：PENDING → PAID，payment_method=COIN
      7. grantEntitlement → EntitlementMapper.insert
      8. UserMapper.selectBalanceById → toOrderVO
  → 事务提交后 Controller 返回成功
```

扣币 SQL 核心：`UPDATE users SET balance_cents=balance_cents-#{amountCents} ... WHERE id=#{userId} AND balance_cents>=#{amountCents}`。更新 0 行视为余额不足，409 INSUFFICIENT_BALANCE；任意后续写入失败一起回滚余额、订单与权益。金额以订单快照为准，不重新按当前商品价格收费。

**并发重点：** 下单和付款都先锁用户；同一订单后来的付款请求拿锁后重新读到 PAID，直接返回，不能二次扣币。不同订单竞争同一余额时也按用户行锁串行，余额条件 UPDATE 防止透支。不持全表锁，普通非锁定查询通常使用 InnoDB MVCC，不会因为同一用户在付款就都等待。

他人订单与不存在订单统一 404 ORDER_NOT_FOUND。商品后来调价或下架不改变既有待付款订单金额。免费历史单重复调用保留原方式，不追扣币。Qt payOrder → orderPaid 关闭对应确认窗口 → 刷新余额、权益和订单。

技术：事务原子性、行锁顺序、条件 UPDATE、重复付款幂等、唯一约束。源码：[实现][order-service] / [订单 SQL][order-xml] / [余额 SQL][user-xml] / [权益 SQL][entitlement-xml]。

## 九、已购与下载

<a id="api-22"></a>
### 22. 我的已购：GET /me/wallpapers

输入：Bearer；200 返回 WallpaperVO[]，含已购停售商品。最多完整返回 200 条，超出 409 OWNED_LIST_LIMIT_EXCEEDED，不静默截断。

```text
WallpaperController.getMyWallpapers
  → WallpaperService.getMyWallpapers → WallpaperServiceImpl.getMyWallpapers
      → BaseContext.getCurrentId
      → WallpaperMapper.selectOwnedWallpapers(userId,201)
      → 检查上限
      → toVO(row,本账号已购封面 URL)
  → ApiResponse.success
```

SQL 从 **entitlements e** 开始，`e.user_id=当前用户`；JOIN wallpapers w 按 e.wallpaper_id 取得当前商品，再 JOIN categories c 按 w.category_id 取分类名。按 e.granted_at/e.id 倒序；不按 PUBLISHED 或分类 enabled 过滤。没有权益的未上架商品仍不会出现在结果中。

权益表没有 status，存在记录即有效；返回商品当前资料和当前原文件元数据，不返回历史版本。已购封面 URL 为 `/me/wallpapers/{id}/cover`，客户端带 Bearer 获取；没有放宽公开商品查询。此查询保留在 catalog、复用 WallpaperMapper。

Qt 调用：顶部“我的已购” → StoreService.syncOwned → ownedChanged → 重新绘制已购列表；选中停售商品使用已购资料，不再请求公开详情。

技术：受权益条件限制的 JOIN、账号隔离、VO 复用。源码：[Controller][wallpaper-controller] / [实现][wallpaper-service] / [SQL][wallpaper-xml]。

<a id="api-23"></a>
### 23. 已购封面：GET /me/wallpapers/{id}/cover

输入：Bearer、商品 id；200 返回图片字节，Cache-Control no-store，未购或不存在为 404 RESOURCE_NOT_FOUND。

```text
CoverController.readOwned
  → CoverService.readOwned
      → BaseContext + CatalogIds
      → WallpaperMapper.selectOwnedCover(userId,id)
      → readFile → OssStorage.readCover / 本地 Resource
  → ResponseEntity<Resource>
```

SQL：entitlements JOIN wallpapers，条件限定当前用户与目标商品，要求封面 READY、有对象键，不限制发布状态。返回图片和大小，不返回任意文件下载入口。Qt 的 ApiClient.authorizeCoverRequest 仅向当前后端的已购封面入口附带本账号 Bearer，不把它发送给外部 OSS 地址。

技术：权益 SQL、带凭据图片请求、公共封面服务、HTTP no-store。源码：[Controller][cover-controller] / [实现][cover-service] / [SQL][wallpaper-xml]。

<a id="api-24"></a>
### 24. 下载授权：POST /wallpapers/{id}/download-grants

输入：Bearer、商品 id；200 返回 DownloadGrantVO：wallpaperId、url、expiresAt、fileExtension、字符串 sizeBytes、sha256，Cache-Control no-store。

```text
DownloadController.createDownloadGrant
  → DownloadService.createDownloadGrant → DownloadServiceImpl.createDownloadGrant
      → BaseContext + CatalogIds
      → DownloadMapper.selectOwnedDownloadFile(userId,wallpaperId)
      → 检查本账号权益对应原文件 READY、存储方式 OSS
      → expiresAt = 当前 UTC + 300 秒
      → OssStorage.createDownloadUrl(bucket,key,versionId,expiresAt)
          → OSS SDK generatePresignedUrl
      → DownloadGrantVO
  → ApiResponse.success
```

SQL：entitlements JOIN wallpapers，以当前 user_id 和 wallpaper_id 限定权益，一条查询同时取得当前 original_* 元数据。不限制 PUBLISHED，因此停售不剥夺已购下载。不写数据库，不持锁，不在签发过程中读取整份远程原文件。

无权益 403 NOT_OWNED；元数据未准备好 409 FILE_NOT_READY；不是 OSS 原文件时 503 STORAGE_NOT_SUPPORTED。预签名不是额外的应用数据库票据，签发时有权益才生成；**已经签发的地址不会因之后删除权益自动提前撤销**，最多受五分钟期限等限制。当前文件有 OSS versionId 时地址也带 versionId，因此 RAM 需要相应 GetObjectVersion 权限。

```text
后续客户端下载（不属于额外的后端 Controller 接口）
StoreService.requestDownload → downloadAuthorized
  → DownloadManager.start：直接 GET OSS URL，不带本系统 Bearer
  → 流式下载、大小/SHA-256 检查、原子保存与缩略图
  → WallpaperLibrary.addPrepared → changed
  → MainWindow.rebuildList：立即显示已下载壁纸
```

本地默认保存于运行目录 `loaded_wallpaper`，入库与应用桌面是两步；不会自动应用。商品换文件并回收旧对象后，旧地址可能失效，重新申请取得当前文件。对象存储 versionId 用于精确定位/删除，**不是已经移除的商品 resourceVersion 或历史资产管理**。

技术：权益 JOIN、OSS V4 预签名、UTC 过期时间、HTTPS 下载、SHA-256、Qt 信号槽/原子保存。源码：[Controller][download-controller] / [实现][download-service] / [SQL][download-xml] / [OSS][oss-storage]。

## 十、健康接口

<a id="api-25"></a>
### 25. 就绪检查：GET /health

输入：无；MySQL 可执行查询则 200 `data: {}`，不可用则 503 SERVICE_UNAVAILABLE。

```text
HealthController.health
  → JdbcTemplate.queryForObject("SELECT 1",Integer.class)
  → 结果为 1：ApiResponse.success(空对象)
  → 数据库异常/异常结果：unavailable → 503
```

这是直接用 JdbcTemplate 的小型基础设施查询，**没有额外的 HealthService 或 Mapper**。不返回数据库 URL、密码、连接异常详情；不检查 Redis、OSS 或 ffmpeg 是否可用。不能用它成功来证明所有存储与下载都正常。

技术：Spring JDBC、HikariCP、MySQL、统一响应。源码：[HealthController][health-controller]。

## 十一、不是 HTTP 接口，但会被自动调用的任务

| 入口 | 触发时机 | 实际链路与用途 |
| --- | --- | --- |
| AuthServiceImpl.initializeDummyPassword | Spring 注入完成后 `@PostConstruct` | PasswordEncoder.encode 生成占位密码哈希，登录不存在账号时仍做密码比较 |
| [SessionCleanupTask][session-cleanup].cleanExpiredSessions | `@Scheduled`，默认 UTC 每天 03:00 | 当前 UTC 减 retentionDays（7 天）→ SessionMapper.deleteExpired；清理固定过期时间已经越过保留线的会话，不是清空所有被撤销会话 |
| ProductFileCleanupService.retryPendingFiles | `@Scheduled`，默认初始与间隔各 300000ms | 扫 storage.root/.catalog-cleanup 的 JSON，每批最多 64 → 查文件引用 → 无引用才删除 OSS；失败任务保留，异常任务隔离 |
| OssStorage.close | Spring 销毁 Bean 时 `@PreDestroy` | 关闭已创建 OSS SDK 客户端 |

## 十二、读代码时需要分清的几件事

### 12.1 数据库存的是什么

| 表 | 当前职责 | 接口使用 |
| --- | --- | --- |
| users | 不变的自增用户 ID、用户名、密码哈希、角色/状态、余额等 | 注册登录、认证、个人资料、管理员复查、下单付款 |
| sessions | 登录组、刷新令牌哈希、轮换与撤销 | 登录、认证、刷新、登出、清理 |
| categories | 分类名称、排序、启停等 | 公开分类、分类 CRUD、商品关联 |
| wallpapers | 商品资料、发布状态，以及当前两份文件的位置/元数据 | 商品管理、公开浏览、封面、已购、下载 |
| orders | 用户/商品归属、下单标题金额币种快照、付款状态/时间等 | 下单、列表、付款 |
| entitlements | 当前有效用户商品权益及来源订单 | 购买防重、已购/封面/下载授权 |

当前没有 assets、order_create_requests 表；无商品 resourceVersion、用户 displayName、权益 status。商品的 created_by/uploaded_by 也引用用户 ID，不是用户名。OrderVO 的 balanceCents 从 users 查询，不是 orders 新列。

### 12.2 必须保留的并发保护

| 场景 | 第一次检查 / 读取 | 第二次检查 / 约束 | 保护什么 |
| --- | --- | --- | --- |
| 注册/分类名称 | Service 普通查重 | 数据库唯一约束 | 两个请求都查到“没有”后不能同时插入同名 |
| refresh/logout | 按刷新哈希普通查记录，定位 family | 组行锁，再锁定读令牌 | 轮换、登出、旧令牌重放不使用旧快照 |
| 商品创建分类 | 上传前普通查询 | 上传后事务内锁定读分类 | 上传期间分类被停用/删除 |
| 商品文件替换 | 上传前商品快照 | 上传后锁最新商品并比较文件标识 | 同一文件被其他请求替换后不能被旧请求覆盖 |
| 商品写入管理员 | 请求进入时 AuthFilter | 长上传后写库前再次读用户 | 拒绝已降级/停用的账号；后一次用户读取不持角色行锁 |
| 下单防重 | 不依赖前端展示的“未购买” | 用户行锁内读权益/有效订单，唯一约束 | 并发下单复用已有结果，不创建重复有效订单 |
| 付款 | 前端展示的余额只供确认 | 用户/订单锁内复查 PAID、条件扣币、事务 | 同单不二次扣币，不同订单不透支，三项写入一起回滚 |
| 文件回收 | 保存失败或文件已被替换 | 删除前再次查商品引用 | 避免误删仍被已提交商品引用的对象 |

普通 InnoDB SELECT 与 FOR UPDATE 不能混为一谈。普通浏览通常读取可见快照；有行锁也不表示所有查询都被阻塞。锁、事务能保护上面明确的状态，不能让“客户端看见列表 → 管理员下架 → 用户继续点击”的多次 HTTP 请求成为一个永远不变的快照。

### 12.3 当前没有实现的接口

- 取消订单：只有 Qt `StoreService.cancelOrder` 和窗口按钮预留，不发送 HTTP，不改订单。
- 充值、修改用户名、修改密码：只有前端接入位置，没有可用后端路由。
- 退款：权益 Mapper 有按来源订单删除的方法，但没有退款 Controller/Service 完整流程；不能据此说退款业务已实现。
- 外部支付、支付回调、购物车、多商品订单：未实现。现有付款是站内壁纸币。
- 商品 DELETE：当前管理员商品四个业务接口是列表、创建、修改、上下架，没有商品物理删除 HTTP 接口。
- 独立上传、test-pay：已经移除，不属于本次 25 个接口；FileUploadService 是内部能力。
- Redis 缓存/分布式锁：依赖已引入，当前业务没有使用。

### 12.4 文档核对范围

本次逐项对照 Controller 映射、实际 Service 调用、Mapper/SQL、配置与打包依赖，不只复制历史 JavaDoc。特别按实际代码纠正理解：健康检查不检查 Redis，公共封面路由虽未单列 OpenAPI 但已经存在，订单返回九字段，当前不保留历史资产。

本文是调用链学习文档，不替代请求/响应的完整 schema；具体字段校验继续看 DTO、VO 与 [OpenAPI][openapi]。接口清点验证记录见 [核对记录](E:/zlowallpaper_sever/docs/api-call-chains-validation.md)。

## 源码链接

[pom]: E:/zlowallpaper_sever/pom.xml
[app-yaml]: E:/zlowallpaper_sever/src/main/resources/application.yml
[local-yaml]: E:/zlowallpaper_sever/src/main/resources/application-local.yml
[openapi]: E:/zlowallpaper_sever/docs/frintend-docks/openapi.yaml
[request-id]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/common/RequestIdFilter.java
[web-config]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/config/WebConfig.java
[auth-filter]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/filter/AuthFilter.java
[base-context]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/common/BaseContext.java
[api-response]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/common/ApiResponse.java
[business-exception]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/common/BusinessException.java
[global-errors]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/common/GlobalExceptionHandler.java
[page-vo]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/common/vo/PageVO.java
[jwt-util]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/util/JwtUtil.java
[token-generator]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/util/TokenGenerator.java
[password-config]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/config/PasswordConfig.java
[auth-properties]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/config/AuthProperties.java
[credentials-dto]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/dto/CredentialsDTO.java
[strict-string]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/dto/StrictStringDeserializer.java
[catalog-ids]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/service/CatalogIds.java
[category-names]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/util/CategoryNames.java
[category-deserializers]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/dto/CategoryFieldDeserializers.java
[pagination-config]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/config/MybatisPlusConfig.java
[file-upload]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/service/impl/FileUploadServiceImpl.java
[media-inspector]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/media/MediaInspector.java
[oss-storage]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/storage/OssStorage.java
[wallpaper-persistence]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/service/WallpaperPersistenceService.java
[file-cleanup]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/service/ProductFileCleanupService.java
[cover-service]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/service/CoverService.java
[asset-config]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/config/AssetConfig.java
[oss-properties]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/config/OssProperties.java
[upload-properties]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/config/UploadProperties.java
[auth-controller]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/controller/AuthController.java
[auth-service]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/service/impl/AuthServiceImpl.java
[user-controller]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/controller/UserController.java
[user-service]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/service/impl/UserServiceImpl.java
[user-xml]: E:/zlowallpaper_sever/src/main/resources/mapper/auth/UserMapper.xml
[session-xml]: E:/zlowallpaper_sever/src/main/resources/mapper/auth/SessionMapper.xml
[category-controller]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/controller/CategoryController.java
[category-service]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/service/impl/CategoryServiceImpl.java
[category-mapper]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/mapper/CategoryMapper.java
[category-xml]: E:/zlowallpaper_sever/src/main/resources/mapper/catalog/CategoryMapper.xml
[wallpaper-controller]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/controller/WallpaperController.java
[wallpaper-service]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/service/impl/WallpaperServiceImpl.java
[wallpaper-xml]: E:/zlowallpaper_sever/src/main/resources/mapper/catalog/WallpaperMapper.xml
[cover-controller]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/controller/CoverController.java
[admin-category-controller]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/controller/AdminCategoryController.java
[admin-wallpaper-controller]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/controller/AdminWallpaperController.java
[admin-wallpaper-service]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/service/impl/AdminWallpaperServiceImpl.java
[admin-wallpaper-xml]: E:/zlowallpaper_sever/src/main/resources/mapper/catalog/AdminWallpaperMapper.xml
[order-controller]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/order/controller/OrderController.java
[order-service]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/order/service/impl/OrderServiceImpl.java
[order-xml]: E:/zlowallpaper_sever/src/main/resources/mapper/order/OrderMapper.xml
[entitlement-xml]: E:/zlowallpaper_sever/src/main/resources/mapper/entitlement/EntitlementMapper.xml
[order-vo]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/order/vo/OrderVO.java
[download-controller]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/download/controller/DownloadController.java
[download-service]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/download/service/impl/DownloadServiceImpl.java
[download-xml]: E:/zlowallpaper_sever/src/main/resources/mapper/download/DownloadMapper.xml
[health-controller]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/common/HealthController.java
[session-cleanup]: E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/task/SessionCleanupTask.java

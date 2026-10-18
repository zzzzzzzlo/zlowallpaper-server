# ZloWallpaper Spring Boot 后端

2026-10-17：我的已购已改为MyBatis-Plus分页，默认每页12条、最多50条，支持搜索/分类及商品精确筛选，取消200张列表总量限制；Qt客户端同步适配。新协议和调用链见 [已购分页说明](docs/owned-pagination.md)。

当前阶段：auth、catalog公开查询/分类与商品管理、内部云存储上传、订单创建/查询、壁纸币付款、我的壁纸和短时下载授权已实现。文件信息直接保存商品行，不保留独立assets表或商品历史版本。工作台保存一次提交资料与文件；两端界面已适配壁纸币、个人信息/余额、已购封面身份读取及停售详情，详见[前端统一修复说明](docs/frontend-reconciliation.md)。新增链路见[已购与下载开发说明](docs/download-development.md)。

2026-10-16：本机现有42份资源已迁移腾讯云COS，SHA-256及大小逐份核对一致，新上传默认COS。HTTP协议及商品、订单、权益不变；旧OSS文件保留回退。配置与迁移命令见 [COS接入与迁移](docs/cos-migration.md)。

两次调整清单及当前字段见 [商品结构修改说明](docs/catalog-refactor-changes.md)。

## 工具链

| 项目 | 配置 |
| --- | --- |
| Java | 21；本机 Temurin 21.0.12.1，D:\Codex\software\jdk-21.0.12.1+1 |
| Spring Boot | 3.5.16 |
| Maven | Wrapper 锁定3.9.16，并配置分发包SHA-256校验 |
| MyBatis-Plus | 3.5.17，Spring Boot 3 starter |
| 本机依赖缓存 | D:\Codex\software\maven-repository |
| MySQL | 127.0.0.1:3306，zlowallpaper，wallpaper_app |
| 后端地址 | http://127.0.0.1:8080/api/v1 |

现有 JDK 25 保留，没有修改系统全局 JAVA_HOME。Java21与Maven下载包已经按官方校验和验证。

## 启动

项目根目录已有 .env，应用在 local 配置中显式读取其中 MYSQL_PASSWORD 和 AUTH_JWT_SECRET。工作目录必须为项目根目录；其他环境通过进程环境变量传入。JWT密钥须为至少32个随机字节的Base64编码；真实凭证不写入源码。

在 PowerShell 中执行：

```powershell
Set-Location E:\zlowallpaper_sever
.\scripts\start-backend.ps1
```

脚本在当前终端运行服务，Ctrl+C停止。它仅为启动过程选用本机JDK21、Maven缓存目录和local环境，不修改系统变量。自定义端口使用 `-Port 8081`，自定义JDK目录使用 `-JavaHome <目录>`。

浏览器或Apifox请求 `GET http://127.0.0.1:8080/api/v1/health`。MySQL就绪时：

```json
{"code":"OK","message":"","data":{},"requestId":"由服务器生成"}
```

健康接口通过应用数据源执行SELECT 1；数据库不可用返回HTTP503、SERVICE_UNAVAILABLE、data:null，不返回连接详情。当前Redis尚未纳入健康检查；后续业务依赖Redis时补充其就绪策略。

## IDEA 打开方式

1. 打开根目录pom.xml，选择作为Maven项目加载；已经打开此文件夹时，可右键pom.xml选择“添加为Maven项目”。
2. Project Structure → SDKs 添加 `D:\Codex\software\jdk-21.0.12.1+1`；Project SDK选择该JDK21。Maven Importing / Runner也使用JDK21。
3. 本机 Maven home选择 `D:\Codex\software\apache-maven-3.9.16`，Local repository选择 `D:\Codex\software\maven-repository`。命令行仍可使用项目Wrapper。
4. 运行 `src/main/java/com/zlo/wallpaper/ZloWallpaperApplication.java` 的main方法；工作目录保持 `E:\zlowallpaper_sever`。未指定环境时默认local。

现有IDEA文件夹配置此前指向JDK25；没有手动伪造IDEA全局SDK注册信息。选择新JDK后由IDEA保存正确设置。

## 新增文件的用途

| 文件/目录 | 用途 |
| --- | --- |
| pom.xml | 项目信息、依赖、Java版本、打包插件 |
| mvnw、mvnw.cmd、.mvn/wrapper | 官方Maven Wrapper；统一构建版本，无须系统PATH里有mvn |
| src/main/java | Java源码；com.zlo.wallpaper为根包 |
| ZloWallpaperApplication.java | 应用入口与组件扫描起点 |
| common/ApiResponse.java | 与现有接口一致的响应外壳 |
| common/RequestIdFilter.java | 为请求生成requestId，写入请求属性与X-Request-Id响应头 |
| common/HealthController.java | 基础MySQL就绪检查，无商城业务 |
| config/WebConfig.java | 允许指定管理端来源的CORS，支持预检，不携带cookie凭证 |
| catalog/controller、service、mapper、dto、entity、vo、model、util | 分类/商品公开查询、管理员分类管理、请求与返回对象及联表统计 |
| src/main/resources/mapper/catalog | 商品联表、分页和详情 SQL |
| config/MybatisPlusConfig.java | MyBatis-Plus 分页拦截器，单页上限50 |
| auth/controller、service、mapper、dto、vo、entity | 五个认证接口及账号、会话实体 |
| auth/filter/AuthFilter.java、common/BaseContext.java | 统一身份/管理员校验与当前请求用户ID |
| auth/util、config/AuthProperties.java、config/PasswordConfig.java | JWT、随机令牌、密码组件和有效期配置 |
| auth/task/SessionCleanupTask.java | 定期清理已过期且超过保留期的会话轮换链 |
| database/002-create-sessions.sql | 在原有七张表基础上新增sessions，已经在本机执行 |
| common/GlobalExceptionHandler.java | 参数和业务异常转换为统一错误响应 |
| 其他业务模块的package-info.java | 说明模块职责，后续添加实现 |
| src/main/resources/application.yml | 端口、API前缀、通用配置、MyBatis映射等 |
| src/main/resources/application-local.yml | 本机数据库、Redis连接参数、显式.env导入和预留开发配置 |
| src/test | 自动测试；使用test环境，不读取商城密码或改商城表 |
| target | 构建输出与测试报告，已被Git忽略 |

文件存储目录用于临时文件、清理队列及遗留本地封面；管理员素材上传和原文件授权下载已接入私有COS，兼容OSS旧位置。服务仅绑定127.0.0.1，本阶段用于本机开发。

数据库初始化设置为never；不会在启动时重复执行database中的建表SQL，也不会重建现有六张业务表。启动不会插入管理员、分类或商品。

## 商品管理本次完成

管理员商品列表、一次multipart创建、部分修改、上下架四接口已实现，另有管理员封面预览。新建默认草稿，编辑保持发布状态；文件检查与OSS传输在短数据库事务之外，失败或替换对象有引用保护及持久清理重试。

工作台已接入新协议，选文件不上传，点击保存才提交。无需新建表；沿用已执行的004迁移后的43字段wallpapers。旧独立上传入口已删除。开发与代码导读见 [catalog-development3](docs/catalog-development3.md)，剩余事项见 [接口核对](docs/frontend-backend-compatibility.md)。重启后端、刷新工作台后生效。

## 商品模块查询接口

| 方法 / 路径（接在 /api/v1 后） | 功能 |
| --- | --- |
| GET /categories | 返回全部启用分类的 id/name |
| GET /wallpapers | page/pageSize/keyword/categoryId 查询已上架商品 |
| GET /wallpapers/{id} | 已上架商品详情，草稿和下架商品返回404 |

列表默认第1页、每页12条，上限50条。keyword不传、为空或只含空白时不按标题筛选；categoryId不传或为空时不按分类筛选。排序为published_at降序、id降序。分类停用不下架商品。返回字段严格按 `docs/frintend-docks/openapi.yaml`，ID/字节数为字符串；sha256 为当前原文件哈希，不返回资源版本。

商品coverUrl指向只读辅助入口 `GET /api/v1/catalog/covers/{id}`（id 是商品 ID），仅允许读取已上架商品行中的READY封面，不能用于读取原文件或草稿封面。支持LOCAL和OSS，最大4 MiB；LOCAL文件位于wallpaper.storage.root下，OSS从商品封面字段中的私有Bucket读取。缺少文件返回404，云存储不可用返回503。

详细代码导读、Apifox请求与测试说明见 [catalog开发说明](docs/catalog-development.md)。

管理员分类管理已实现：GET/POST `/admin/categories`、PATCH/DELETE `/admin/categories/{id}`，要求当前ADMIN角色。支持分类新增、改名、排序、启停和无商品引用时删除；引用数统计所有商品状态，停用不下架已有商品。逐文件导读、SQL示例和验收见 [catalog第二阶段开发说明](E:/zlowallpaper_sever/docs/catalog-development2.md)。

旧独立 POST `/admin/uploads` 已删除（2026-10-15），不再保留410提示路由。文件随商品创建/修改的multipart请求一起提交；内部 FileUploadService、MediaInspector、OssStorage 继续负责媒体检查和OSS上传，失败文件由清理服务回收。封面上限4 MiB，原文件10 GiB。

## 构建与测试

认证接口：POST `/auth/register`、`/auth/login`、`/auth/refresh`、`/auth/logout`，GET `/me`。注册后需要登录，私有请求使用Bearer访问令牌。登录状态保存在MySQL，刷新令牌轮换和登出会使旧凭证失效；auth不依赖Redis。请求示例、会话字段、事务导读见 [auth开发与Apifox联调说明](docs/auth-development.md)。

```powershell
Set-Location E:\zlowallpaper_sever
$env:JAVA_HOME = 'D:\Codex\software\jdk-21.0.12.1+1'
$env:MAVEN_USER_HOME = 'D:\Codex\software\maven-user-home'
.\mvnw.cmd -B -ntp -Dmaven.repo.local=D:/Codex/software/maven-repository verify
```

生成 `target/zlowallpaper-server-0.0.1-SNAPSHOT.jar`。默认153项测试：21项auth、34项catalog公开查询、58项管理员分类、15项内部上传、2项旧上传停用、12项媒体签名、8项OSS存储和3项健康/CORS检查，默认不读取或修改本机商城库，也不访问真实OSS。另有显式开启的MySQL验证及13项真实媒体工具验证，命令见对应模块说明。

## 后续开发

六个业务包为auth、catalog、order、payment、entitlement、download。catalog 包含公开查询、分类及商品管理、媒体检查与 OSS 存储。POST /orders 已实现，免费订单与权益事务提交，收费订单保存PENDING；详见 [创建订单开发说明](docs/order-development.md)。数据库已执行007余额迁移，当前六张表；初始化新库须按001、002、003、004、006、007顺序执行，已有库只补缺少的增量迁移。壁纸币付款 POST /orders/{id}/pay 已实现，同一事务扣余额、更新PAID/COIN并新增权益，重复付款不重复扣币；不再使用测试开关，详见 [壁纸币付款开发说明](docs/order-payment-development.md)。GET /me/orders订单列表已实现，返回本账号订单快照，详见 [订单查询开发说明](docs/order-query-development.md)。GET /me/wallpapers已购查询与5分钟OSS下载授权已实现，详见[已购与下载开发说明](docs/download-development.md)；两端前端已完成本轮适配与真实联调。

Redis已加入连接依赖和本机参数，但尚未验证服务可用性。会话采用MySQL sessions表；上传使用私有OSS；创建订单无需请求键，通过当前权益、待付款订单、行锁和唯一约束处理重复购买；权益仅保存有效记录。商品保存失败及替换文件已有持久清理任务；壁纸币付款通过账号锁及SQL余额条件避免重复扣款和透支，将来异步媒体处理后续设计。

设计文档位于docs；数据库SQL位于database；当前前端接口依据为docs/frintend-docks/openapi.yaml。

官方参考：[Spring Boot 3.5要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[MyBatis-Plus安装](https://baomidou.com/getting-started/install/)、[Spring配置导入](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)、[Maven Wrapper](https://maven.apache.org/tools/wrapper/)。

用户余额users.balance_cents默认0；100单位=1壁纸币，当前商品币种ZLC。GET /me和登录/刷新用户资料新增字符串balanceCents；设置测试余额见database/dev-set-coin-balance.sql（仅供手动开发测试，未自动赠币）。OpenAPI已同步；两端界面与付款客户端改造按用户安排后续统一处理。

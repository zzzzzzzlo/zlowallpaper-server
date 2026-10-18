# ZloWallpaper 整体架构说明

2026-10-13 后续实施更新：商品列表、一次multipart创建/修改、上下架和管理员封面已完成；旧资产模型、独立上传和待开发描述仅作历史记录。当前实现与失败清理以 [catalog-development3](E:/zlowallpaper_sever/docs/catalog-development3.md) 为准。

2026-10-13 第二次调整：assets 表已并入 wallpapers，文件信息直接保存商品行；独立上传已停用，创建商品将一次提交资料、原文件及封面。商品写接口尚未实现。以 [两次修改清单](E:/zlowallpaper_sever/docs/catalog-refactor-changes.md) 为当前设计。

2026-10-13 更新：上传代码已并入 catalog，商品只关联当前原文件和封面，取消商品资源历史表及 resourceVersion。商品返回当前原文件 sha256，客户端按文件哈希识别下载。完整调整见 [商品文件管理说明](E:/zlowallpaper_sever/docs/catalog-resource-development.md)。

生成日期：2026-10-09。

交互架构图：`E:\Codex\files\zlowallpaper-architecture\zlowallpaper-architecture.html`。浏览器直接打开即可查看，支持搜索、缩放、节点与源码定位、明暗主题及导出；没有开启动画。

可编辑规格保存在本目录 `zlowallpaper-architecture.json`。本次使用已安装的 Archify 技能生成图，不改变前后端代码或数据库。

原七模块展开图：`E:\Codex\files\zlowallpaper-architecture\backend-modules.html`，保留为历史设计快照。当前六个业务模块及接口归属见本目录 `BACKEND-MODULES.md`。

## 依据与实现状态

以下依据记录生成图时的状态（2026-10-09），不表示当前工作区状态。原图前端依据锁定到提交 `605c30e35fec8872345c3aa92b7ad9bd4af62a68`，避免原源码链接随 main 变化失效：

- 桌面客户端：[frontend/](https://github.com/zzzzzzzlo/zlowallpaper/tree/605c30e35fec8872345c3aa92b7ad9bd4af62a68/frontend)，Qt 6 / C++17 / CMake。
- 浏览器管理端：[admin/](https://github.com/zzzzzzzlo/zlowallpaper/tree/605c30e35fec8872345c3aa92b7ad9bd4af62a68/admin)，HTML / CSS / JavaScript；Node.js 只提供本机静态页面服务。
- 接口依据：[OpenAPI](https://github.com/zzzzzzzlo/zlowallpaper/blob/605c30e35fec8872345c3aa92b7ad9bd4af62a68/frontend/docs/openapi.yaml) 与 [后端交接文档](https://github.com/zzzzzzzlo/zlowallpaper/blob/605c30e35fec8872345c3aa92b7ad9bd4af62a68/frontend/docs/BACKEND-HANDOFF.md)。
- 后端依据：当前 `E:\zlowallpaper_sever` 工作区已确认的模块设计和 `database/001-create-business-tables.sql`。

图中“已实现”记录当时的前端状态。当前已实现 auth、分类管理、公开商品查询及私有 OSS 上传；sessions 已落库，历史资源表已移除。商品写接口及交易待实现，完整状态见根 README。

## 整体组织

两个前端共享一套 `/api/v1` 接口和同一份业务数据。Spring Boot 后端采用模块化单体：一个应用进程内分模块组织 Controller、Service、Mapper，避免把模块名称误读为独立部署的微服务。

| 模块 | 负责什么 | 主要业务表 |
| --- | --- | --- |
| auth | 注册、登录、刷新、登出、当前用户、用户/管理员权限 | users、sessions |
| catalog | 分类、公开商品、详情、管理员草稿编辑和上下架、文件上传与当前文件 | categories、wallpapers |
| order | 服务端定价、成交快照、创建及查询订单、重复有效订单处理 | orders |
| payment | 首期免费领取及开发环境测试付款，支付状态转换 | orders，事务内联动 entitlements |
| entitlement | 用户已购列表、权益授予/撤销、来源订单 | entitlements |
| download | 校验用户权益，按当前文件签发限时下载授权 | entitlements、wallpapers |

公共代码处理统一响应、异常、参数校验、requestId 和跨域配置；它不是额外业务模块。管理员接口落在 auth 与 catalog 中。注册用户与管理员共享 users 表，权限来自服务端；商城用户不会拿到 MySQL root 或 wallpaper_app 的数据库凭证。

## 桌面客户端

源码实际关系为 `StorePage → StoreService → ApiClient → HTTP API`。StoreService 管理账号状态、商品、订单和已购同步；ApiClient 使用 Qt Network 发送 JSON/Bearer 请求。accessToken 在内存中，选择记住登录时 refreshToken 通过 CredentialVault 用 Windows DPAPI 加密保存。

拿到下载授权后，StoreService 将商品、授权和账号标识交给 DownloadManager。DownloadManager 按限时 URL 获取实际文件，不向存储地址转发账号 Authorization；验证期限、字节数及 SHA-256 后原子保存，后台生成缩略图，再写入 WallpaperLibrary。商品/文件哈希/账号用于去重和隔离。

本地库记录由 library.json 保存，实际原文件位于客户端程序旁的 wallpapers 目录。MainWindow 将本地条目交给 WallpaperEngine；WallpaperSurface 使用图片/GIF能力及 libmpv 播放，DesktopHost 挂载到 Windows WorkerW。Application 另外协调多显示器、Explorer 恢复、休眠及 Dock/透明任务栏辅助进程。图中“Windows 桌面与扩展”概括这些本机能力，并不表示 WallpaperEngine 直接负责管理 Dock。

下载后的本地文件可离线播放。后端购买权益与客户端本地文件记录是两个独立概念；退款或登出不会远程删除已下载文件。

## 浏览器管理端

本机 server.mjs 在 `127.0.0.1:4173` 提供静态 HTML/CSS/JS。页面通过 fetch 访问 Spring Boot，静态服务不是业务后端，也没有代替后端连接 MySQL。

登录流程为 `/auth/login → GET /admin/categories 验权 → GET /categories`。当前 User DTO 没有 role 字段，因此管理端使用已实现的管理接口的 200/403 判断是否允许进入；后端始终负责管理员鉴权。

发布流程为分别上传 COVER / ORIGINAL → 两份资产 READY → 创建 DRAFT → 编辑资料 → PUT publication 上架/停售。服务端生成媒体字段及文件哈希。当前契约没有 DELETE 商品接口。

Web 管理端的 accessToken 和 refreshToken 都仅保存在内存；localStorage 只保留 API 地址和模式。后端需允许管理端的具体来源及 OPTIONS 预检，成功和错误响应都需带正确 CORS 头。Qt 桌面网络客户端不受浏览器 CORS 限制。

## 数据与文件

MySQL 保存账号、分类、商品、文件元数据、订单与购买权益。数据库不保存壁纸二进制文件，也不把短期签名 URL 作为稳定文件地址。

assets 记录稳定 object_key、bucket、大小、哈希及媒体信息；wallpapers 直接关联当前原文件和封面。已接入私有 OSS；未部署 CDN、消息队列或独立 Worker。原导出图及 JSON 为最初设计快照，其 asset/resources 节点已过时。

免费下单已实现，PAID订单与购买权益在同一数据库事务中提交。停售只改商品发布状态，既有订单、资源和有效权益保留。创建订单查询权益与待付款订单，并使用行锁及唯一有效订单约束；测试付款与下载授权仍待实现。详见 [创建订单开发说明](../order-development.md)。

## 必须在开发时解决的待定项

1. **异步资产状态查询。** 首期同步检查成功返回 READY，暂不需要轮询；将来增加后台转码时再评审状态查询。
2. **购买防重。** 创建请求表已移除；通过权益、待付款订单、行锁与唯一约束防重，取消/退款后允许新购买。
3. **文件清理与下载。** OSS 上传、封面访问及媒体检查已实现；孤立文件清理、商品保存补偿和购买下载授权待开发。
4. **真实联调。** Qt DemoServer、管理端 mock-api 与测试 HTTP fixture 是演示/测试设施，其内存用户、订单和权益不等同于本项目 MySQL 数据，也不表示真实后端已经通过验收。
5. **真实支付。** 首期只有免费领取和测试付款；生产付款、支付回调和退款接入以后再设计。

确定性的交付记录及浏览器证据保存在 HTML 同目录；其结果与视觉检查分别记录，便于后续更新架构图时复核。

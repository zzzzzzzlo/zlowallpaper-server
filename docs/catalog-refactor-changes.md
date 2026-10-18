# 商品与文件结构：两次修改清单

日期：2026-10-13。两次结构调整时商品管理未开发；本次后续已完成四个商品管理接口，见 [catalog-development3](catalog-development3.md)。下文保留结构迁移说明。

## 当前接口完成情况

| 接口 | 状态 |
| --- | --- |
| `GET /categories` | 已实现 |
| `GET /wallpapers` | 已实现，查询已适配最新商品表 |
| `GET /wallpapers/{id}` | 已实现，查询已适配最新商品表 |
| 四个管理员分类管理接口 | 已实现 |
| `GET /admin/wallpapers` | 已实现 |
| `POST /admin/wallpapers` | 已实现；一次multipart接收资料和两份文件 |
| `PATCH /admin/wallpapers/{id}` | 已实现；变化资料和可选替换文件 |
| `PUT /admin/wallpapers/{id}/publication` | 已实现 |
| 旧 `POST /admin/uploads` | 2026-10-15已删除，不再提供停用提示路由；管理员请求返回404 |

路径相对于 `/api/v1`。工作台已移除独立上传，选择时仅暂存与预览，保存使用一次multipart提交。剩余差异见 [两端接口核对清单](frontend-backend-compatibility.md)。

## 第一次：合并业务包，取消商品历史版本

| 改动 | 具体内容 |
| --- | --- |
| Java 包 | 删除独立 `asset` 包，把上传、媒体检查、存储代码和测试移动到 `catalog` |
| SQL | [003-current-wallpaper-file.sql](../database/003-current-wallpaper-file.sql) 新增 `wallpapers.original_asset_id`，迁移当前原文件关联，再删除历史关联表 |
| 删除结构 | 删除 `wallpaper_resources` 表、`wallpapers.current_resource_version` 字段，以及相关外键、检查约束和索引 |
| 当时的文件关系 | 商品使用 `cover_asset_id`、`original_asset_id` 关联 `assets`，每种文件只关联当前一份 |
| 接口 | 商品和 DownloadGrant 不再包含 `resourceVersion`，商品返回当前文件 SHA-256 |
| 两端前端 | 工作台去掉版本显示与模拟版本递增；Qt 下载和本地库改用文件哈希识别，旧本地文件继续保留 |

这一步只取消商品历史资源版本，数据库迁移文件仍保留，用于记录结构变更。桌面客户端已经重新编译、测试，并更新本机安装的程序。

## 第二次：文件元数据直接并入商品表

| 改动 | 具体内容 |
| --- | --- |
| SQL | [004-inline-wallpaper-files.sql](../database/004-inline-wallpaper-files.sql) 新增两组文件字段，迁移已关联文件的元数据，再删除独立资产结构 |
| 删除表 | 删除 `assets`；之前的 `wallpaper_resources` 已在第一次删除 |
| 删除字段 | 删除 `cover_asset_id`、`original_asset_id`，以及对应外键；不再通过文件 ID 关联其他表 |
| 新增字段 | 商品表新增 30 个字段，原文件和封面各 15 个；完整商品表现在有 43 个字段 |
| 查询 | `WallpaperMapper.xml` 商品查询只关联 `categories`，文件信息直接读取 `wallpapers` |
| 封面 | `GET /catalog/covers/{id}` 的 ID 现在是商品 ID；通过商品记录读取封面的 Bucket、对象键、类型及大小；公开商品 VO 生成相应 URL |
| 上传 | 停用独立上传入口；保留内部 `FileUploadService`、`MediaInspector`、`OssStorage`，供商品保存内部调用 |
| 删除代码 | 删除 Asset Entity、Mapper/XML、DTO、VO、独立上传 Controller、AssetService/Impl、AssetPersistenceService，以及不适用的资产表测试夹具 |

当前数据库是 `users`、`sessions`、`categories`、`wallpapers`、`orders`、`entitlements`，共 6 张表。

## 新增的商品文件字段

同一行商品保存当前原文件与当前封面。两组字段没有版本序号，不记录以往文件。

| 含义 | 原文件字段 | 封面字段 | SQL 类型 |
| --- | --- | --- | --- |
| 原始文件名 | `original_filename` | `cover_filename` | VARCHAR(255) |
| 存储方式 | `original_storage_provider` | `cover_storage_provider` | VARCHAR(32) |
| OSS Bucket | `original_bucket` | `cover_bucket` | VARCHAR(128) |
| 稳定对象键/相对路径 | `original_object_key` | `cover_object_key` | VARCHAR(512) |
| OSS 对象版本 ID，仅用于精确删除 | `original_object_version_id` | `cover_object_version_id` | VARCHAR(128) |
| 真实 MIME 类型 | `original_mime_type` | `cover_mime_type` | VARCHAR(127) |
| 文件扩展名 | `original_file_extension` | `cover_file_extension` | VARCHAR(16) |
| 实际文件大小 | `original_size_bytes` | `cover_size_bytes` | BIGINT |
| SHA-256 校验值 | `original_sha256` | `cover_sha256` | CHAR(64) |
| 宽度 | `original_width` | `cover_width` | INT |
| 高度 | `original_height` | `cover_height` | INT |
| 帧率 | `original_fps` | `cover_fps` | DECIMAL(10,3) |
| 文件状态 | `original_status` | `cover_status` | VARCHAR(16) |
| 上传管理员 ID | `original_uploaded_by` | `cover_uploaded_by` | BIGINT |
| 上传时间，UTC | `original_uploaded_at` | `cover_uploaded_at` | DATETIME(3) |

文件内容仍在 OSS，数据库不保存文件字节或临时签名 URL。`object_version_id` 是云存储自己的对象标识，便于在多版本 Bucket 中精确删除，不是商品版本，也不会返回给普通用户。

这些列允许为 NULL，方便没有文件的草稿；只要某份文件已有对象键，就必须具备文件名、存储方式、格式、大小、哈希、状态和上传信息。READY 文件必须有正数宽高及有效帧率。上架商品必须有两份 READY 文件和上架时间。原文件上限 10 GiB、封面上限 4 MiB；OSS 文件还必须有 Bucket，上传人外键关联 users。

## 当前代码如何查询

```sql
SELECT w.id, w.title,
       w.original_width AS width,
       w.original_height AS height,
       w.original_size_bytes AS sizeBytes,
       w.original_sha256 AS sha256,
       c.name AS categoryName
FROM wallpapers w
JOIN categories c ON c.id = w.category_id
WHERE w.publication_status = 'PUBLISHED'
  AND w.original_status = 'READY'
  AND w.original_object_key IS NOT NULL
  AND w.cover_status = 'READY'
  AND w.cover_object_key IS NOT NULL;
```

这里只有商品和分类的关联。原文件宽高、大小、哈希都来自同一行商品，不需要再关联资产表。完整字段与分页条件在 [WallpaperMapper.xml](../src/main/resources/mapper/catalog/WallpaperMapper.xml)。Controller → Service → Mapper 的分层及 MyBatis-Plus 分页方案保留。

公开 VO 不返回 Bucket、原文件对象键、上传者或对象版本 ID。封面读取只使用封面字段，不能借这个入口读取原文件。封面只有商品上架且封面 READY 时才开放，LOCAL 路径检查与 OSS 大小限制仍保留。

## 后续创建商品的调用关系

以下调用流程已实现，具体代码与失败清理见 catalog-development3.md：

1. 前端本地选择两份文件，填写商品资料，点击创建。
2. 一次 multipart 请求传商品资料、`originalFile`、`coverFile`，前端不传 OSS 对象键或媒体元数据。
3. 商品 Service 校验资料和分类，再内部调用 `FileUploadService.uploadFile` 检查及上传两份文件。
4. 上传返回服务器生成的 `UploadedFile`，包含真实大小、哈希、媒体信息、Bucket、对象键和 OSS 对象版本 ID。
5. 商品保存的短数据库事务把业务资料与两组文件信息写入同一行 `wallpapers`，初始状态 DRAFT。
6. 若第二份上传失败或商品写库失败，删除本次已上传的对象；成功修改后再处理已被替换的旧文件。

两次改表时只完成了第3、4步内部工具；后续商品Controller、DTO、写Mapper及失败补偿均已实现，当前完整流程见catalog-development3.md。

## 执行与验证

两次 SQL 已在本机开发库实际执行。执行前确认商品、历史资源和资产表为空；没有迁移或删除已有商品数据，没有删除用户、会话或 OSS 对象。

新库按 001 → 002 → 003 → 004 初始化；本机已执行，不要重复运行。MySQL DDL 隐式提交，含真实数据的其他环境必须先备份并检查未关联资产。

第二次改动验证：默认后端 153 项测试全部通过，覆盖公开查询、分类管理、内部上传、媒体检查、OSS 调用和旧上传停用；本机 MySQL 7 项检查通过，覆盖商品字段约束、上传人外键、分页及分类外键。订单/权益事务示例的 3 项检查通过，测试数据全部回滚。真实 OSS 本次没有调用。

2026-10-15后续清理：旧上传兼容入口已删除，不再返回410提示；商品创建和修改已实现，资料和文件一次提交。重启后端后管理员访问旧地址返回404，现有商品管理、公开查询、分类管理及auth继续使用。

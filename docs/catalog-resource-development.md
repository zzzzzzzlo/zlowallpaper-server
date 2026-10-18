# 商品文件管理调整说明（历史）

> 历史文档（2026-10-15归档说明）：以下保留早期资产模型和独立上传的实现过程，不是当前接口使用指南。旧独立上传及其410提示路由均已删除；当前上传随商品创建/修改一次提交，见 [catalog-development3](catalog-development3.md)。

2026-10-13 后续实施更新：商品列表、一次multipart创建/修改、上下架和管理员封面已完成；旧资产模型、独立上传和待开发描述仅作历史记录。当前实现与失败清理以 [catalog-development3](E:/zlowallpaper_sever/docs/catalog-development3.md) 为准。

2026-10-13 第二次调整：assets 表已并入 wallpapers，文件信息直接保存商品行；独立上传已停用，创建商品将一次提交资料、原文件及封面。商品写接口尚未实现。以 [两次修改清单](E:/zlowallpaper_sever/docs/catalog-refactor-changes.md) 为当前设计。

调整日期：2026-10-13。按本次确认：上传代码并入 `catalog`；取消商品资源历史版本；两端前端及接口契约移除 `resourceVersion`。

## 现在的关系

`wallpapers.cover_asset_id` 指向当前封面，`wallpapers.original_asset_id` 指向当前原文件，两者都关联 `assets.id`。一个商品只保存这两份当前关联，不再通过版本表寻找原文件。

`assets` 表仍然需要：它保存文件的 OSS 对象键、用途、真实格式、大小、SHA-256、宽高、帧率和上传者。文件字节在 OSS，数据库只保存元数据。`Asset` 是“文件”的英文类名，它保留在 `catalog/entity` 中；独立的 `asset` 业务包已移除。

本次没有增加商品创建、修改和上下架接口；现有公开查询、分类管理、上传接口继续使用原路径。商品写接口将在后续开发。

## 从哪里读代码

| 文件 | 用途 |
| --- | --- |
| `catalog/controller/AssetController.java` | `POST /admin/uploads` 的请求入口，接收 multipart 文件和用途 |
| `catalog/service/impl/AssetServiceImpl.java` | 校验上传、暂存文件、计算哈希、检查媒体、上传 OSS、保存记录、失败补偿 |
| `catalog/media/MediaInspector.java` | 调用 ffprobe/ffmpeg 检查真实格式、媒体信息和首帧 |
| `catalog/storage/OssStorage.java` | 通过 OSS SDK 上传、读取封面，以及删除本次失败上传的对象 |
| `catalog/service/AssetPersistenceService.java` | 单独使用短数据库事务保存文件元数据 |
| `catalog/mapper/AssetMapper.java`、`mapper/catalog/AssetMapper.xml` | 文件记录的 INSERT 和 SELECT |
| `catalog/service/impl/WallpaperServiceImpl.java` | 调用商品查询 Mapper，把查询结果转换为 VO |
| `mapper/catalog/WallpaperMapper.xml` | 从商品直接关联当前封面和原文件，读取当前文件元数据 |
| `catalog/model/WallpaperRow.java` | 联表结果，在 Mapper 和 Service 之间传递 |
| `catalog/vo/WallpaperVO.java` | 返回公开商品资料及当前文件 SHA-256，不公开原文件存储地址 |

其余上传 DTO、VO、中间模型和测试也移动到 `catalog`，具体上传实现的逐文件讲解仍在 [上传开发说明](asset-development.md)，文中的源码链接已更新。

## 商品查询 SQL

```sql
FROM wallpapers w
JOIN categories c ON c.id = w.category_id
JOIN assets cover ON cover.id = w.cover_asset_id
JOIN assets original ON original.id = w.original_asset_id
WHERE w.publication_status = 'PUBLISHED'
  AND cover.purpose = 'COVER' AND cover.status = 'READY'
  AND original.purpose = 'ORIGINAL' AND original.status = 'READY'
```

例如商品的 `cover_asset_id=300`、`original_asset_id=401`：`cover` 读取文件 300，`original` 读取文件 401。它们是同一张 `assets` 表的两个别名，分别处理封面和原文件。宽高、fps、大小、哈希都取自原文件；封面 URL 根据封面 ID 生成。

查询继续限制上架状态、用途和 READY 状态。商品没有当前可用原文件时不会出现在公开列表中。分页方案仍为 MyBatis-Plus，没有新增分页插件。

## 取消版本后如何识别下载文件

商品 JSON 和 DownloadGrant 都不再包含 `resourceVersion`。商品返回当前原文件的 `sha256`，下载授权已有的 `sha256` 继续用于校验文件。工作台移除版本显示，模拟 API 不再递增版本号。

桌面端按 API/账号、商品 ID 和 SHA-256 查找已下载文件。同一份文件不重复入库；当前文件内容变更后哈希不同，可以重新下载。下载前比较商品和授权的哈希，下载后验证实际字节的大小及哈希。这个值描述文件内容，不是版本序号，不会产生历史版本表。

本地库的字段改为 `storeSha256`。旧记录仍保留文件路径、商品和账号，继续可播放；因为没有哈希，不直接复用为当前商城文件。下次下载时记录哈希。不会自动删除正在使用的本地文件。

## 数据库迁移

[003-current-wallpaper-file.sql](../database/003-current-wallpaper-file.sql) 先将商品原来的当前版本关联转换成 `original_asset_id`，然后删除原版本外键、索引、检查约束、`current_resource_version` 字段和 `wallpaper_resources` 表，建立当前原文件外键及上架检查约束。

当前本机开发库已经执行这份迁移。商品、历史资源、资产表在迁移前均为 0 行；用户、会话和其他表没有被删除，也没有调用 OSS 删除文件。

新数据库按 `001-create-business-tables.sql` → `002-create-sessions.sql` → `003-current-wallpaper-file.sql` 顺序初始化。已完成迁移的库不要重复执行 003。旧迁移文件保留，方便追溯数据库结构变更；保留迁移文件不等于保存商品历史版本。MySQL DDL 会隐式提交，有真实数据的环境应先备份再迁移。

数据库保证当前原文件 ID 指向存在的资产；上架商品必须有封面、原文件和上架时间。文件用途、READY 状态、上传者权限等仍要在未来商品写 Service 中检查。

## 与 OSS 多版本、清理的区别

`StoredObject.versionId` 是 OSS 返回的对象版本 ID，用于上传写库失败时精确删除刚上传的对象。它不出现在商品 JSON 中，也不用于保存商品历史。本次保留这项失败补偿，避免多版本 Bucket 仅生成删除标记而留下文件字节。

取消商品历史不等于已实现孤立文件清理。以后修改商品原文件时，应切换当前关联；旧文件不作为可查询的商品历史保存，但删除仍需确认没有其他商品引用、避开有效下载授权，并处理 OSS 与数据库操作失败的补偿。暂存上传、超时残留和清理任务仍待开发。

此前讨论的“点击保存后再上传”仍属于下一阶段前端流程调整，本次只完成模块合并和版本取消。

## 验证与使用

默认后端 154 项测试通过，覆盖鉴权、公开查询、分类管理、上传、媒体检查、OSS 调用和健康检查。新增检查验证切换当前原文件后元数据与哈希同步变化、响应不再有版本字段。本机 MySQL 测试还检查原文件外键和上架约束。

本机 MySQL 10 项检查全部通过，事务示例也确认停售保留权益、调价不改变订单金额、退款后可重新购买，测试数据全部回滚。

工作台 33 项测试通过。桌面程序编译成功，Qt 测试输出 12 项通过，包含下载、哈希识别、旧记录保留及授权哈希不匹配的检查。真实 OSS 上传本次没有调用。

更新后请重启后端，并刷新工作台页面；Apifox 需要重新导入最新 OpenAPI，商品测试断言改为 SHA-256。桌面端使用重新编译的程序，旧程序仍期待版本字段。

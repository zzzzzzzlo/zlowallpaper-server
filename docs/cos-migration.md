# 腾讯云 COS 接入与现有文件迁移

日期：2026-10-16。后端：`E:\zlowallpaper_sever`。

## 配置和访问地址

目标是普通 COS，存储桶 `zlowallpaper-1505875143`，广州 `ap-guangzhou`。本机 `.env` 配置：

```dotenv
STORAGE_PROVIDER=COS
COS_BUCKET=zlowallpaper-1505875143
COS_REGION=ap-guangzhou
COS_SECRET_ID=只填本机密钥
COS_SECRET_KEY=只填本机密钥
```

其中 `STORAGE_PROVIDER` 在完成迁移校验后才切换。不要将 `.env` 提交 Git，也不要把密钥放进两端前端。

COS Java SDK 根据桶名和地域自动生成 HTTPS 域名 `zlowallpaper-1505875143.cos.ap-guangzhou.myqcloud.com`，无需另配 Endpoint。桶保持私有；裸链接不能作为用户下载链接。后端核验权益后签发五分钟下载地址；前端访问 COS 时不携带商城的 Bearer 令牌。

CAM 后端专用子用户目前采用 `QcloudCOSDataFullControl`，包含文件读、写、列出、删除；覆盖账号下所有 COS 桶。如以后增加其他业务桶，可以再改为仅授权本项目桶的自定义策略。前端不直传 COS，因此本次无需为上传配置 COS 浏览器跨域。

## 调用链及技术

保留原 Controller → Service → Mapper 业务结构、HTTP 路径和返回字段。仅文件操作增加统一存储入口：

```text
创建/修改商品 → FileUploadServiceImpl.uploadFile
  → ObjectStorage.upload → 配置指定的 CosStorage / OssStorage
  → 可信文件元数据 → WallpaperPersistenceService 短事务写商品

读取封面 → CoverService 权限查询
  → ObjectStorage.readCover → 按数据库该文件的 provider 读取

下载授权 → DownloadServiceImpl 权益查询
  → ObjectStorage.createDownloadUrl → 按该文件的 provider 签名

失败补偿/旧文件回收 → ProductFileCleanupService 引用检查
  → ObjectStorage.deleteObject → 按待清理对象的 provider 删除
```

`ObjectStorage` 只集中分流云厂商操作，不承接额外业务检查。新增 `CosProperties` 绑定配置；`CosStorage` 使用官方 Maven 依赖 `com.qcloud:cos_api:5.6.227`。阿里云 SDK 暂保留，用于迁移过渡和回退。两种 SDK 客户端均延迟创建，Spring 销毁时关闭连接池。

COS 小于等于 8 MiB 的文件直接上传，更大文件采用 8 MiB 顺序分片，失败尽力取消未完成分片；不会将大文件完整读入内存。上传并发数量、媒体解析、SHA-256、商品文件替换冲突和事务边界保持原逻辑。

文件实体继续内嵌商品表，不新建表。清理任务新增 provider 字段，旧 JSON 未提供时默认 OSS；旧 OSS 任务的文件名哈希保持兼容。清理引用查询同时覆盖 OSS/COS，防止回收仍被商品使用的对象。

## 迁移过程

命令源码位于 [CosMigration.java](../tools/storage/CosMigration.java)，通过 [Run-CosMigration.ps1](../tools/storage/Run-CosMigration.ps1) 执行，不注册 HTTP 接口，也不在应用启动时自动运行。

1. `plan`：读取商品文件元数据，在 `E:\壁纸` 和客户端 `loaded_wallpaper` 中按大小和 SHA-256 匹配源文件，生成固定目标 UUID。此步骤没有云请求。
2. `stage`：优先使用本地匹配文件上传 COS；下载每一份目标文件，重新核对大小和 SHA-256。已校验项可断点继续，此时数据库位置保持原值。
3. 暂停后端写请求；备份旧 JAR 和 `.env`。
4. `apply`：锁住商品并复查计划中的源位置、大小、SHA，单个数据库事务修改 original/cover 的 provider、bucket、object_key、object_version_id。
5. `verify`：核对数据库位置、大小和 SHA 与已校验清单一致；将新上传配置切至 COS，启动新后端，验收真实 HTTP 上传、封面和授权下载。

本次计划为 **42 份文件、709,423,091 字节（约 676.6 MiB）**，42 份全部匹配到本地副本，预计无需阿里云文件下载。COS 下载校验约消耗同样大小的腾讯云外网流量，另有少量接口验收开销。

迁移只改文件位置，不修改商品 ID、名称、状态、价格、文件内容哈希、时间，以及用户、会话、订单、权益、分类。命令在位置更新前后计算这些字段及其他表的摘要进行比对。清单、商品旧位置备份和结果均在忽略提交的 `.local-setup/cos-migration/`。

```powershell
# 在后端目录执行；先构建后端，依赖类路径需已生成。
& tools/storage/Run-CosMigration.ps1 plan
& tools/storage/Run-CosMigration.ps1 stage
# 停止后端写入之后再执行：
& tools/storage/Run-CosMigration.ps1 apply
& tools/storage/Run-CosMigration.ps1 verify
```

## 回退

本次不会删除旧 OSS 对象。回退前先停止后端写入，再执行 `rollback` 恢复计划中的旧位置，将 `.env` 的 `STORAGE_PROVIDER` 改回 OSS，启动后端。回退也会复查当前文件位置和内容，防止覆盖迁移之后管理员的新修改；若已更换文件，应先人工核对。

保留旧对象便于回退，仍会占用阿里云存储空间。后续确认稳定后可以单独整理旧文件，不与本次迁移混做。

## 验证记录

最终后端测试：257 项，失败 0、错误 0、跳过 1；包括 COS 分片失败取消、签名、存储分流、旧清理任务兼容和停售商品权益限制。

实际迁移完成：42/42 份 COS 文件下载后大小及 SHA-256 一致；数据库 42 个文件位置与清单一致。用户、会话、分类、订单、权益五张表和商品非位置字段的前后摘要一致；旧对象删除数量为 0。所有源文件均使用本地副本，未产生阿里云文件回源。

后端已以 COS 配置重新启动，健康检查 HTTP 200，公开商城分页 HTTP 200、12 张上架商品。使用临时账号与商品完成真实 HTTP/COS 验收：

- multipart 创建后两份文件 provider 均为 COS。
- 管理封面、公开封面、已购停售封面的字节与上传内容一致。
- 免费订单创建、权益发放和五分钟签名下载成功，GET 文件字节一致。
- COS 对象不带签名的匿名 HEAD 返回 403。
- 替换封面和下架后访问限制符合原规则。
- 一张实际迁移商品的公开封面与数据库 SHA-256 一致。

验收使用的两个临时账号、商品、订单、权益、会话及三个测试云对象已经清理，未扣减现有账号余额。详细本机证据为 `.local-setup/cos-stage.log`、`cos-tests-final.log`、`cos-http-check.log` 和 `cos-migration/apply-result.json`。

本次接入未新增封面缓存或下载缓存功能；前端接口无需因云厂商切换而更换路径和字段。

参考：[腾讯云 Java SDK](https://cloud.tencent.com/document/product/436/10199)、[CAM 预设策略](https://cloud.tencent.com/document/product/436/68280)。

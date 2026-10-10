# 壁纸商城业务表与 Java 命名对照

创建日期：2026-10-09。数据库：`zlowallpaper`，MySQL 8.4.11。

七张表已经实际创建；`sessions` 和 `idempotency_records` 仍待定，未创建。本文件中的 Java 类和属性是后续开发的命名约定，目前没有生成实体类、Mapper 或业务代码。

SQL 在 `database/001-create-business-tables.sql`，只用于初始化空库，不应在已经建表的库里重复执行。后续改表请新增 SQL 文件保存变更，避免丢失设计历史。

数据库表名采用复数、字段采用下划线；Java 实体类采用单数大驼峰，变量及属性采用小驼峰。持久层需要明确配置下划线到驼峰的映射。

| 中文 | MySQL 表名 | Java 实体类 | 单条变量 | 列表变量 | 所属模块 |
| --- | --- | --- | --- | --- | --- |
| 用户表 | `users` | `User` | `user` | `userList` | `auth` |
| 分类表 | `categories` | `Category` | `category` | `categoryList` | `catalog` |
| 壁纸商品表 | `wallpapers` | `Wallpaper` | `wallpaper` | `wallpaperList` | `catalog` |
| 文件资产表 | `assets` | `Asset` | `asset` | `assetList` | `asset` |
| 壁纸资源版本表 | `wallpaper_resources` | `WallpaperResource` | `wallpaperResource` | `wallpaperResourceList` | `catalog / asset / download` |
| 订单表 | `orders` | `Order` | `order` | `orderList` | `order / payment` |
| 购买权益表 | `entitlements` | `Entitlement` | `entitlement` | `entitlementList` | `entitlement / download` |

下面的字段清单来自建表后的 MySQL 元数据。“允许空”是数据库层规则；接口或业务服务可以要求更严格的输入。`updated_at` 初始化时自动赋 UTC 时间，后续修改由应用明确更新，数据库没有自动更新时间触发器。

## 用户表：`users` → `User`

| 含义 | MySQL 字段 | Java 属性 | MySQL 类型 | Java 类型 | 允许空 |
| --- | --- | --- | --- | --- | --- |
| 用户ID | `id` | `id` | `bigint` | `Long` | 否 |
| 登录用户名 | `username` | `username` | `varchar(32)` | `String` | 否 |
| 密码哈希，不存明文 | `password_hash` | `passwordHash` | `varchar(255)` | `String` | 否 |
| 显示名称 | `display_name` | `displayName` | `varchar(64)` | `String` | 否 |
| 角色：USER / ADMIN | `role` | `role` | `varchar(16)` | `String` | 否 |
| 账号状态：ACTIVE / DISABLED | `status` | `status` | `varchar(16)` | `String` | 否 |
| 创建时间，UTC | `created_at` | `createdAt` | `datetime(3)` | `LocalDateTime` | 否 |
| 修改时间，UTC，由应用更新 | `updated_at` | `updatedAt` | `datetime(3)` | `LocalDateTime` | 否 |

## 分类表：`categories` → `Category`

| 含义 | MySQL 字段 | Java 属性 | MySQL 类型 | Java 类型 | 允许空 |
| --- | --- | --- | --- | --- | --- |
| 分类ID | `id` | `id` | `bigint` | `Long` | 否 |
| 分类名称 | `name` | `name` | `varchar(64)` | `String` | 否 |
| 排序值，越小越靠前 | `sort_order` | `sortOrder` | `int` | `Integer` | 否 |
| 是否启用：1启用，0停用 | `enabled` | `enabled` | `tinyint` | `Boolean` | 否 |
| 创建时间，UTC | `created_at` | `createdAt` | `datetime(3)` | `LocalDateTime` | 否 |
| 修改时间，UTC，由应用更新 | `updated_at` | `updatedAt` | `datetime(3)` | `LocalDateTime` | 否 |

## 壁纸商品表：`wallpapers` → `Wallpaper`

| 含义 | MySQL 字段 | Java 属性 | MySQL 类型 | Java 类型 | 允许空 |
| --- | --- | --- | --- | --- | --- |
| 壁纸商品ID | `id` | `id` | `bigint` | `Long` | 否 |
| 商品标题 | `title` | `title` | `varchar(120)` | `String` | 否 |
| 商品描述 | `description` | `description` | `varchar(4000)` | `String` | 否 |
| 所属分类ID | `category_id` | `categoryId` | `bigint` | `Long` | 否 |
| 壁纸类型：IMAGE / GIF / VIDEO | `type` | `type` | `varchar(16)` | `String` | 否 |
| 当前售价，单位分 | `price_cents` | `priceCents` | `int` | `Integer` | 否 |
| 币种，首期CNY | `currency` | `currency` | `char(3)` | `String` | 否 |
| 作品作者名称 | `creator` | `creator` | `varchar(120)` | `String` | 否 |
| 封面资产ID，组装草稿时允许为空 | `cover_asset_id` | `coverAssetId` | `bigint` | `Long` | 是 |
| 当前资源版本，组装草稿时允许为空 | `current_resource_version` | `currentResourceVersion` | `bigint` | `Long` | 是 |
| 发布状态：DRAFT / PUBLISHED / WITHDRAWN | `publication_status` | `publicationStatus` | `varchar(16)` | `String` | 否 |
| 最近一次上架时间，UTC | `published_at` | `publishedAt` | `datetime(3)` | `LocalDateTime` | 是 |
| 创建商品的管理员用户ID | `created_by` | `createdBy` | `bigint` | `Long` | 否 |
| 创建时间，UTC | `created_at` | `createdAt` | `datetime(3)` | `LocalDateTime` | 否 |
| 修改时间，UTC，由应用更新 | `updated_at` | `updatedAt` | `datetime(3)` | `LocalDateTime` | 否 |

## 文件资产表：`assets` → `Asset`

| 含义 | MySQL 字段 | Java 属性 | MySQL 类型 | Java 类型 | 允许空 |
| --- | --- | --- | --- | --- | --- |
| 文件资产ID | `id` | `id` | `bigint` | `Long` | 否 |
| 用途：ORIGINAL原文件 / COVER封面 | `purpose` | `purpose` | `varchar(16)` | `String` | 否 |
| 上传时的文件名，仅作展示 | `original_filename` | `originalFilename` | `varchar(255)` | `String` | 否 |
| 存储方式，例如LOCAL / OSS / S3 | `storage_provider` | `storageProvider` | `varchar(32)` | `String` | 否 |
| 云存储桶，本地存储为空 | `bucket` | `bucket` | `varchar(128)` | `String` | 是 |
| 稳定存储键或相对路径，不存临时签名URL | `object_key` | `objectKey` | `varchar(512)` | `String` | 否 |
| 媒体类型，例如image/png | `mime_type` | `mimeType` | `varchar(127)` | `String` | 否 |
| 文件扩展名，不带点 | `file_extension` | `fileExtension` | `varchar(16)` | `String` | 否 |
| 实际文件字节数 | `size_bytes` | `sizeBytes` | `bigint` | `Long` | 否 |
| 实际文件SHA-256，64位小写十六进制 | `sha256` | `sha256` | `char(64)` | `String` | 否 |
| 像素宽度，处理完成前允许为空 | `width` | `width` | `int` | `Integer` | 是 |
| 像素高度，处理完成前允许为空 | `height` | `height` | `int` | `Integer` | 是 |
| 帧率，静态图片为0，处理完成前允许为空 | `fps` | `fps` | `decimal(10,3)` | `BigDecimal` | 是 |
| 处理状态：PROCESSING / READY / FAILED | `status` | `status` | `varchar(16)` | `String` | 否 |
| 上传者用户ID | `uploaded_by` | `uploadedBy` | `bigint` | `Long` | 否 |
| 创建时间，UTC | `created_at` | `createdAt` | `datetime(3)` | `LocalDateTime` | 否 |
| 修改时间，UTC，由应用更新 | `updated_at` | `updatedAt` | `datetime(3)` | `LocalDateTime` | 否 |

## 壁纸资源版本表：`wallpaper_resources` → `WallpaperResource`

| 含义 | MySQL 字段 | Java 属性 | MySQL 类型 | Java 类型 | 允许空 |
| --- | --- | --- | --- | --- | --- |
| 壁纸资源记录ID | `id` | `id` | `bigint` | `Long` | 否 |
| 所属壁纸商品ID | `wallpaper_id` | `wallpaperId` | `bigint` | `Long` | 否 |
| 资源版本号，从1递增 | `version` | `version` | `bigint` | `Long` | 否 |
| 该版本的原文件资产ID | `original_asset_id` | `originalAssetId` | `bigint` | `Long` | 否 |
| 创建时间，UTC | `created_at` | `createdAt` | `datetime(3)` | `LocalDateTime` | 否 |

## 订单表：`orders` → `Order`

| 含义 | MySQL 字段 | Java 属性 | MySQL 类型 | Java 类型 | 允许空 |
| --- | --- | --- | --- | --- | --- |
| 订单ID | `id` | `id` | `bigint` | `Long` | 否 |
| 购买者用户ID | `user_id` | `userId` | `bigint` | `Long` | 否 |
| 购买的壁纸商品ID | `wallpaper_id` | `wallpaperId` | `bigint` | `Long` | 否 |
| 下单时的商品标题快照 | `title_snapshot` | `titleSnapshot` | `varchar(120)` | `String` | 否 |
| 订单成交金额，单位分 | `amount_cents` | `amountCents` | `int` | `Integer` | 否 |
| 成交币种 | `currency` | `currency` | `char(3)` | `String` | 否 |
| 订单状态：PENDING / PAID / CANCELED / REFUNDED | `status` | `status` | `varchar(16)` | `String` | 否 |
| 支付方式：FREE / TEST，未付款为空 | `payment_method` | `paymentMethod` | `varchar(16)` | `String` | 是 |
| 下单时间，UTC | `created_at` | `createdAt` | `datetime(3)` | `LocalDateTime` | 否 |
| 付款时间，UTC | `paid_at` | `paidAt` | `datetime(3)` | `LocalDateTime` | 是 |
| 取消时间，UTC | `canceled_at` | `canceledAt` | `datetime(3)` | `LocalDateTime` | 是 |
| 退款时间，UTC | `refunded_at` | `refundedAt` | `datetime(3)` | `LocalDateTime` | 是 |
| 修改时间，UTC，由应用更新 | `updated_at` | `updatedAt` | `datetime(3)` | `LocalDateTime` | 否 |
| 数据库生成列，用于限制重复有效订单，Java不写入 | `active_wallpaper_id` | `不映射（数据库生成）` | `bigint` | `—` | 是 |

## 购买权益表：`entitlements` → `Entitlement`

| 含义 | MySQL 字段 | Java 属性 | MySQL 类型 | Java 类型 | 允许空 |
| --- | --- | --- | --- | --- | --- |
| 购买权益ID | `id` | `id` | `bigint` | `Long` | 否 |
| 权益所属用户ID | `user_id` | `userId` | `bigint` | `Long` | 否 |
| 拥有的壁纸商品ID | `wallpaper_id` | `wallpaperId` | `bigint` | `Long` | 否 |
| 授予本次权益的订单ID | `source_order_id` | `sourceOrderId` | `bigint` | `Long` | 否 |
| 权益状态：ACTIVE / REVOKED | `status` | `status` | `varchar(16)` | `String` | 否 |
| 本次授予时间，UTC | `granted_at` | `grantedAt` | `datetime(3)` | `LocalDateTime` | 否 |
| 撤销时间，UTC | `revoked_at` | `revokedAt` | `datetime(3)` | `LocalDateTime` | 是 |
| 记录创建时间，UTC | `created_at` | `createdAt` | `datetime(3)` | `LocalDateTime` | 否 |
| 修改时间，UTC，由应用更新 | `updated_at` | `updatedAt` | `datetime(3)` | `LocalDateTime` | 否 |

## 状态值与业务规则

| 字段 | 取值 |
| --- | --- |
| users.role | USER：普通用户；ADMIN：商城管理员。管理员也存在 users 表中；注册接口固定 USER。 |
| users.status | ACTIVE：可用；DISABLED：禁用。 |
| categories.enabled | 1：启用；0：停用；Java 使用 Boolean。 |
| wallpapers.type | IMAGE：静态图片；GIF：动图；VIDEO：视频。 |
| wallpapers.publication_status | DRAFT：草稿；PUBLISHED：上架；WITHDRAWN：停售。 |
| assets.purpose | ORIGINAL：下载用原文件；COVER：商品封面。 |
| assets.status | PROCESSING：处理中；READY：可用；FAILED：处理失败。 |
| orders.status | PENDING：待付款；PAID：已付款；CANCELED：已取消；REFUNDED：已退款。 |
| orders.payment_method | 未付款为 NULL；FREE：免费领取；TEST：开发环境模拟付款。真实支付方式以后再扩展。 |
| entitlements.status | ACTIVE：有效下载权益；REVOKED：已撤销。 |

- 所有主键采用 BIGINT 自增，Java 实体中使用 Long。前端接口的 ID、资源版本和 sizeBytes 仍按既定契约输出字符串；不能直接把实体当作接口响应。
- 金额采用整数分，例如 1200 表示 12 元；fps 采用 DECIMAL / BigDecimal；文件大小采用 BIGINT / Long，能容纳 10 GiB。
- DATETIME(3) 存 UTC，Java LocalDateTime 属性也按 UTC 使用，接口转换为带 Z 的 ISO-8601。updatedAt 由后端在修改数据时更新。
- 用户名唯一，当前排序规则不区分大小写和重音。注册时应校验并规范化输入，不能仅依赖数据库唯一约束。
- `wallpapers.current_resource_version` 对应接口 `resourceVersion`；商品宽高、fps、大小从当前资源关联的 assets 读取。封面地址 coverUrl 按 coverAssetId 生成，未单独存 URL。
- `orders.title_snapshot` 对应订单接口的 title；订单价格保存下单快照，不跟随商品后续调价。testPaymentEnabled 由部署环境与业务规则计算，不单独落库。
- `assets.object_key` 是存储根目录下的相对路径或云端对象键；bucket 在本地模式为空。文件本身由文件系统/对象存储保存；临时签名 URL 不落库。
- 原文件最大 10 GiB、封面最大 4 MiB；READY 资产需要有效的宽、高、帧率，静态图片 fps 为 0。
- 创建商品在同一事务里先插入草稿，再插入资源版本，然后回填当前版本。允许数据库草稿的封面/版本暂时为空，是为了解决这些记录的创建顺序；接口创建时仍按契约要求提交封面和原文件。
- `(wallpaper_id, version)` 唯一，当前版本必须属于同一商品。更换原文件新增版本，旧版本不覆盖。
- 同一用户同一商品最多一笔 PENDING / PAID 订单；数据库生成列 active_wallpaper_id 配合唯一索引实现，业务代码不赋值。取消/退款后可创建新订单。这是业务防重约束，不能替代待定的请求幂等方案。
- `(user_id, wallpaper_id)` 购买权益唯一。来源订单通过复合外键保证属于同一用户、同一商品；退款撤销，重新购买时更新来源订单、授予时间和状态，订单历史保留。
- 外键删除策略是 RESTRICT，不级联删除。停售修改 publicationStatus，既有订单、权益和资产不删除。
- 管理员身份、封面/原文件用途匹配、发布时资产 READY、订单状态转换和“付款＋权益”事务仍由业务服务校验；这些表不能独立完成全部业务规则。
- 会话、请求幂等和下载票据的存储方案尚未实现。九表讨论中的两张待定表继续待定。

## 已执行验证

- 七张表创建成功，字段注释、索引、CHECK 与外键已生效。
- 事务验证购买、停售后保留权益及原文件、商品调价不改订单金额、退款后重新购买。
- 实际验证数据库拒绝重复用户名、重复有效订单、重复权益、无效当前资源版本、删除已引用资产、将其他用户订单作为权益来源。
- 所有验证数据已回滚，七张表当前各有 0 行；未创建初始管理员、分类或商品。

验证流程保存在 `database/verify-business-tables.sql`。本地执行辅助脚本 `scripts/invoke-local-mysql.ps1` 从已忽略的 .env 读取商城账号密码，通过受限临时配置连接 MySQL，结束后删除临时配置，不把密码放进命令行或文档。


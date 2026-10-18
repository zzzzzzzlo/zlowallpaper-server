# 壁纸商城业务表与 Java 命名对照

> 2026-10-16更新：显示名称字段已移除；UserVO仅返回id、username、balanceCents。旧库执行008迁移。前端修改用户名、密码和充值入口仅预留，后端尚未开放，详见[user-profile-development.md](user-profile-development.md)。

2026-10-13 第二次调整：assets 表已并入 wallpapers，文件信息直接保存商品行；独立上传已停用，创建商品将一次提交资料、原文件及封面。商品写接口尚未实现。以 [两次修改清单](E:/zlowallpaper_sever/docs/catalog-refactor-changes.md) 为当前设计。

创建日期：2026-10-09。数据库：`zlowallpaper`，MySQL 8.4.11。

七张初始业务表已创建；新增 sessions 后为八张，2026-10-13 取消历史资源表后当前为六张表，资源表也已并入商品。`idempotency_records` 仍待定。auth中的User、Session实体与Mapper已实现；其余全表实体命名为后续开发约定，catalog查询使用Row模型。

新库按 database/001-create-business-tables.sql、002-create-sessions.sql、003-current-wallpaper-file.sql、004-inline-wallpaper-files.sql 顺序初始化。当前开发库四份变更已执行，不应重复执行。后续改表请新增 SQL 文件保存变更，避免丢失设计历史。

数据库表名采用复数、字段采用下划线；Java 实体类采用单数大驼峰，变量及属性采用小驼峰。持久层需要明确配置下划线到驼峰的映射。

| 中文 | MySQL 表名 | Java 实体类 | 单条变量 | 列表变量 | 所属模块 |
| --- | --- | --- | --- | --- | --- |
| 用户表 | `users` | `User` | `user` | `userList` | `auth` |
| 会话表 | `sessions` | `Session` | `session` | `sessionList` | `auth` |
| 分类表 | `categories` | `Category` | `category` | `categoryList` | `catalog` |
| 壁纸商品表 | `wallpapers` | `Wallpaper` | `wallpaper` | `wallpaperList` | `catalog` |
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
| 壁纸币余额，100单位=1币 | `balance_cents` | `balanceCents` | `bigint` | `Long` | 否 |
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
| 壁纸币币种ZLC | `currency` | `currency` | `char(3)` | `String` | 否 |
| 作品作者名称 | `creator` | `creator` | `varchar(120)` | `String` | 否 |
| 发布状态：DRAFT / PUBLISHED / WITHDRAWN | `publication_status` | `publicationStatus` | `varchar(16)` | `String` | 否 |
| 最近一次上架时间，UTC | `published_at` | `publishedAt` | `datetime(3)` | `LocalDateTime` | 是 |
| 创建商品的管理员用户ID | `created_by` | `createdBy` | `bigint` | `Long` | 否 |
| 创建时间，UTC | `created_at` | `createdAt` | `datetime(3)` | `LocalDateTime` | 否 |
| 修改时间，UTC，由应用更新 | `updated_at` | `updatedAt` | `datetime(3)` | `LocalDateTime` | 否 |

### 商品行内的两组文件字段

| 含义 | 原文件字段 | 封面字段 | 类型 |
| --- | --- | --- | --- |
| 文件名 | `original_filename` | `cover_filename` | VARCHAR(255) |
| 存储方式 | `original_storage_provider` | `cover_storage_provider` | VARCHAR(32) |
| 云存储 Bucket（OSS/COS） | `original_bucket` | `cover_bucket` | VARCHAR(128) |
| 稳定对象键或相对路径 | `original_object_key` | `cover_object_key` | VARCHAR(512) |
| 云对象版本ID，用于精确访问和删除 | `original_object_version_id` | `cover_object_version_id` | VARCHAR(128) |
| 真实MIME | `original_mime_type` | `cover_mime_type` | VARCHAR(127) |
| 扩展名 | `original_file_extension` | `cover_file_extension` | VARCHAR(16) |
| 实际字节数 | `original_size_bytes` | `cover_size_bytes` | BIGINT |
| SHA-256校验值 | `original_sha256` | `cover_sha256` | CHAR(64) |
| 宽度 | `original_width` | `cover_width` | INT |
| 高度 | `original_height` | `cover_height` | INT |
| 帧率 | `original_fps` | `cover_fps` | DECIMAL(10,3) |
| 文件状态 | `original_status` | `cover_status` | VARCHAR(16) |
| 上传管理员ID | `original_uploaded_by` | `cover_uploaded_by` | BIGINT |
| UTC上传时间 | `original_uploaded_at` | `cover_uploaded_at` | DATETIME(3) |

数据库没有独立 assets 表。文件列允许为空以保存未完成草稿；有对象键时需有完整文件信息，READY 要求有效宽高及帧率。上架必须两份文件READY。原文件最大10 GiB、封面最大4 MiB。文件字节保存在COS/OSS，不存数据库；provider区分云厂商。

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
| 支付方式：FREE / COIN，TEST仅保留历史记录，未付款为空 | `payment_method` | `paymentMethod` | `varchar(16)` | `String` | 是 |
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
| 本次授予时间，UTC | `granted_at` | `grantedAt` | `datetime(3)` | `LocalDateTime` | 否 |
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
| wallpapers.original_* / cover_* | 同行保存当前原文件和封面，字段前缀区分用途。 |
| wallpapers.original_status / cover_status | PROCESSING：处理中；READY：可用；FAILED：处理失败。 |
| orders.status | PENDING：待付款；PAID：已付款；CANCELED：已取消；REFUNDED：已退款。 |
| orders.payment_method | 未付款为 NULL；FREE：免费领取；COIN：实际扣减壁纸币；TEST仅兼容历史记录，不再通过接口产生。 |

- 所有主键采用 BIGINT 自增，Java 实体中使用 Long。前端接口的 ID 和 sizeBytes 仍按既定契约输出字符串；不能直接把实体当作接口响应。
- 金额采用整数分，例如 1200 表示 12 元；fps 采用 DECIMAL / BigDecimal；文件大小采用 BIGINT / Long，能容纳 10 GiB。
- DATETIME(3) 存 UTC，Java LocalDateTime 属性也按 UTC 使用，接口转换为带 Z 的 ISO-8601。updatedAt 由后端在修改数据时更新。
- 用户名唯一，当前排序规则不区分大小写和重音。注册时应校验并规范化输入，不能仅依赖数据库唯一约束。
- 商品宽高、fps、大小和 sha256 从本行 original_* 字段读取，不再返回 resourceVersion。封面地址 coverUrl 按商品 ID 生成，未单独存 URL。
- `orders.title_snapshot` 对应订单接口的 title；订单价格保存下单快照，不跟随商品后续调价。testPaymentEnabled已移除，正常壁纸币付款不依赖测试开关。
- `wallpapers.original_object_key` 与 `cover_object_key` 是存储根目录下的相对路径或云端对象键；bucket 在本地模式为空。文件本身由文件系统/对象存储保存；临时签名 URL 不落库。
- 原文件最大 10 GiB、封面最大 4 MiB；READY 文件需要有效的宽、高、帧率，静态图片 fps 为 0。
- 商品保存当前封面及原文件元数据。数据库草稿允许文件列为空，未来创建接口仍要求上传两份有效文件；上架时必须具有两份文件和上架时间。
- 更换原文件更新 original_* 字段，不新增历史版本记录。旧文件清理仍须检查引用和下载授权；孤立文件清理待开发。
- 同一用户同一商品最多一笔 PENDING / PAID 订单；数据库生成列 active_wallpaper_id 配合唯一索引实现，业务代码不赋值。取消/退款后可创建新订单。这是业务防重约束；通过当前权益与待付款订单实现购买防重，无需单独保存创建请求键。
- `(user_id, wallpaper_id)` 购买权益唯一。来源订单通过复合外键保证属于同一用户、同一商品；退款完成将订单改为REFUNDED并删除权益，重新购买新增权益，订单历史保留。
- 外键删除策略是 RESTRICT，不级联删除。停售修改 publicationStatus，既有订单、权益和资产不删除。
- 管理员身份、封面/原文件用途匹配、发布时文件 READY、订单状态转换和“扣余额＋付款＋权益”事务仍由业务服务校验；这些表不能独立完成全部业务规则。
- 会话已采用MySQL sessions表，字段与轮换设计见 [auth开发说明](auth-development.md)。增量迁移为database/002-create-sessions.sql；订单简化迁移见database/006-simplify-order-entitlements.sql，下载票据仍待实现。

## 创建订单简化（2026-10-14）

POST /orders无需请求键。已移除order_create_requests，当前库恢复为六张表。entitlements只有id、user_id、wallpaper_id、source_order_id、granted_at、created_at、updated_at七列；存在记录即拥有权益。

先查当前权益返回来源PAID订单，再查并复用PENDING订单。确需新单时检查商品已上架；免费订单与权益一起提交，收费订单只保存PENDING。用户行锁与唯一约束防并发重复。PAID没有权益时返回购买状态冲突，不能重复收费。

退款完成后订单为REFUNDED，删除该订单授予的权益；重新购买创建新订单和新权益。不再保存旧创建请求的历史结果。006增量迁移已应用；新库初始化不再执行已移除的005。详细说明见 [订单开发文档](order-development.md)。

## 已执行验证

- 七张表创建成功，字段注释、索引、CHECK 与外键已生效。
- 事务验证购买、停售后保留权益及原文件、商品调价不改订单金额、退款后重新购买。
- 实际验证数据库拒绝重复用户名、重复有效订单、重复权益、缺失原文件信息、无效上传管理员及文件大小、将其他用户订单作为权益来源。
- 原初始化验证数据已回滚。开发期间已创建用户、会话和管理员；2026-10-13 商品迁移前 wallpapers、历史资源及 assets 表均为 0 行，用户和会话没有删除。

验证流程保存在 `database/verify-business-tables.sql`。本地执行辅助脚本 `scripts/invoke-local-mysql.ps1` 从已忽略的 .env 读取商城账号密码，通过受限临时配置连接 MySQL，结束后删除临时配置，不把密码放进命令行或文档。


007-user-coin-balance.sql新增默认0余额及非负约束，当前商品和待付款订单币种改ZLC，金额整数值不变；历史已付款等订单保留CNY/FREE/TEST。不存在充值或退款接口；未来COIN退款需同事务返还余额、更新订单和删除权益并防止重复退款。

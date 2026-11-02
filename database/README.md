# 数据库初始化与变更

使用 MySQL 8，数据库名 `zlowallpaper`，字符集 `utf8mb4`。应用用户为 `wallpaper_app`，密码来自 `.env` 的 MYSQL_PASSWORD。数据库账号和商城登录账号是两套账号。

## 初始化空库

创建空数据库和应用账号后，在 MySQL 客户端选择 zlowallpaper，以仓库根目录为工作目录，依次执行：

```sql
SOURCE database/001-create-business-tables.sql;
SOURCE database/002-create-sessions.sql;
SOURCE database/003-current-wallpaper-file.sql;
SOURCE database/004-inline-wallpaper-files.sql;
SOURCE database/006-simplify-order-entitlements.sql;
SOURCE database/007-user-coin-balance.sql;
SOURCE database/008-remove-user-display-name.sql;
SOURCE database/009-simplify-sessions.sql;
SOURCE database/010-order-expiration.sql;
```

编号保留真实变更顺序，没有005不表示遗漏必须执行的脚本。早期文件包含过渡结构，后续脚本迁移到当前模型，不能只执行001。

最终六张表为 users、sessions、categories、wallpapers、orders、entitlements。商品 original_* 和 cover_* 保存当前文件元数据，没有独立资产表或资源历史表。

应用不会启动时自动执行这些脚本、重建表或创建管理员。旧库只执行缺少的增量；不要从001重跑。MySQL DDL 不能当作可回滚的普通业务事务。

## 会话简化迁移

旧库先备份 sessions，然后执行009。迁移保留id、user_id、refresh_token_hash、created_at、expires_at和revoked_at，只删除family_id、revoke_reason、replaced_by_id及其约束/索引。旧已撤销记录不会重新激活；现有有效登录保留ID和期限。009可重复执行，新库执行时不改变002已创建的简化结构。部署时停止旧版后端，迁移后再启动新版，避免旧代码继续写入已删除的列。

## 开发账号和余额

注册固定创建普通用户。开发管理员由维护者在数据库中明确设置角色，不接受前端指定管理员身份。

dev-set-coin-balance.sql 仅用于手动设置开发余额，不是充值接口，也不会启动时自动赠币。余额使用整数最小单位，100单位=1币。

见 [数据模型](../docs/data-model.md) 和 [接口调用链](../docs/api-call-chains.md)。

## 订单有效期迁移

先备份orders并停止旧后端，再执行010并启动新版。迁移增加expires_at、cancel_reason和(status,expires_at,id)索引，保留所有原订单。旧PENDING按创建时间+15分钟补齐；已经到期的改为CANCELED/TIMEOUT，余额、PAID订单及权益不改动。历史已取消订单不虚构原因。新订单默认15分钟，可配置ORDER_PAYMENT_TIMEOUT_MINUTES；修改配置只影响新单。

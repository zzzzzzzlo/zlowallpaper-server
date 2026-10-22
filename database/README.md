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
```

编号保留真实变更顺序，没有005不表示遗漏必须执行的脚本。早期文件包含过渡结构，后续脚本迁移到当前模型，不能只执行001。

最终六张表为 users、sessions、categories、wallpapers、orders、entitlements。商品 original_* 和 cover_* 保存当前文件元数据，没有独立资产表或资源历史表。

应用不会启动时自动执行这些脚本、重建表或创建管理员。旧库只执行缺少的增量；不要从001重跑。MySQL DDL 不能当作可回滚的普通业务事务。

## 开发账号和余额

注册固定创建普通用户。开发管理员由维护者在数据库中明确设置角色，不接受前端指定管理员身份。

dev-set-coin-balance.sql 仅用于手动设置开发余额，不是充值接口，也不会启动时自动赠币。余额使用整数最小单位，100单位=1币。

见 [数据模型](../docs/data-model.md) 和 [接口调用链](../docs/api-call-chains.md)。

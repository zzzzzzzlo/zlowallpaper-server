-- 壁纸币付款：100最小单位=1币。新增余额默认0，不向已有账号赠币。
-- 不新增表；历史PAID/CANCELED/REFUNDED订单保留币种与付款方式，未付款订单按原数值转为ZLC。
-- 在已完成001/002/003/004/006的目标库执行；通过元数据判断支持重复执行。

SET @coin_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE users ADD COLUMN balance_cents BIGINT NOT NULL DEFAULT 0 COMMENT ''壁纸币余额最小单位，100单位=1币'' AFTER status',
    'SELECT 1') FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'balance_cents');
PREPARE coin_statement FROM @coin_sql;
EXECUTE coin_statement;
DEALLOCATE PREPARE coin_statement;

SET @coin_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE users ADD CONSTRAINT ck_users_balance CHECK (balance_cents >= 0)', 'SELECT 1')
    FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'users' AND constraint_name = 'ck_users_balance');
PREPARE coin_statement FROM @coin_sql;
EXECUTE coin_statement;
DEALLOCATE PREPARE coin_statement;

-- 先解除旧币种检查，再改变当前商品币种；金额整数值不变。
SET @coin_sql = (SELECT IF(COUNT(*) > 0,
    'ALTER TABLE wallpapers DROP CHECK ck_wallpapers_currency', 'SELECT 1')
    FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'wallpapers' AND constraint_name = 'ck_wallpapers_currency');
PREPARE coin_statement FROM @coin_sql;
EXECUTE coin_statement;
DEALLOCATE PREPARE coin_statement;
ALTER TABLE wallpapers ALTER COLUMN currency SET DEFAULT 'ZLC';
UPDATE wallpapers SET currency = 'ZLC' WHERE currency = 'CNY';
ALTER TABLE wallpapers ADD CONSTRAINT ck_wallpapers_currency CHECK (currency = 'ZLC');

SET @coin_sql = (SELECT IF(COUNT(*) > 0,
    'ALTER TABLE orders DROP CHECK ck_orders_currency', 'SELECT 1')
    FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'orders' AND constraint_name = 'ck_orders_currency');
PREPARE coin_statement FROM @coin_sql;
EXECUTE coin_statement;
DEALLOCATE PREPARE coin_statement;
ALTER TABLE orders ALTER COLUMN currency SET DEFAULT 'ZLC';
UPDATE orders SET currency = 'ZLC' WHERE status = 'PENDING' AND currency = 'CNY';
ALTER TABLE orders ADD CONSTRAINT ck_orders_currency CHECK (currency IN ('CNY', 'ZLC'));

SET @coin_sql = (SELECT IF(COUNT(*) > 0,
    'ALTER TABLE orders DROP CHECK ck_orders_payment_method', 'SELECT 1')
    FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'orders' AND constraint_name = 'ck_orders_payment_method');
PREPARE coin_statement FROM @coin_sql;
EXECUTE coin_statement;
DEALLOCATE PREPARE coin_statement;
ALTER TABLE orders ADD CONSTRAINT ck_orders_payment_method
    CHECK (payment_method IS NULL OR payment_method IN ('FREE', 'TEST', 'COIN'));

-- TEST仅兼容迁移前的已付款记录；正常付款只写COIN，免费订单仍写FREE。

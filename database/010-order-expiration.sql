-- 为现有订单增加付款截止时间与取消原因，保留全部订单、付款记录和权益。
-- 执行前备份orders并停止旧版后端；迁移后使用新版代码启动。
-- 旧PENDING按创建时间+15分钟补齐，已到期的关闭为TIMEOUT；历史取消原因保持NULL。
SET @order_expiration_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE orders ADD COLUMN expires_at DATETIME(3) NULL COMMENT ''付款截止时间，UTC；免费单为NULL'' AFTER created_at',
    'SELECT 1') FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name = 'expires_at');
PREPARE order_expiration_statement FROM @order_expiration_sql;
EXECUTE order_expiration_statement;
DEALLOCATE PREPARE order_expiration_statement;

SET @order_expiration_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE orders ADD COLUMN cancel_reason VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT ''USER主动取消；TIMEOUT超时关闭；历史未知为NULL'' AFTER canceled_at',
    'SELECT 1') FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name = 'cancel_reason');
PREPARE order_expiration_statement FROM @order_expiration_sql;
EXECUTE order_expiration_statement;
DEALLOCATE PREPARE order_expiration_statement;

SET @order_expiration_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE orders ADD INDEX idx_orders_status_expires (status, expires_at, id)',
    'SELECT 1') FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'orders' AND index_name = 'idx_orders_status_expires');
PREPARE order_expiration_statement FROM @order_expiration_sql;
EXECUTE order_expiration_statement;
DEALLOCATE PREPARE order_expiration_statement;

SET @order_expiration_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE orders ADD CONSTRAINT ck_orders_cancel_reason CHECK (cancel_reason IS NULL OR (status = ''CANCELED'' AND cancel_reason IN (''USER'', ''TIMEOUT'')))',
    'SELECT 1') FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'orders' AND constraint_name = 'ck_orders_cancel_reason');
PREPARE order_expiration_statement FROM @order_expiration_sql;
EXECUTE order_expiration_statement;
DEALLOCATE PREPARE order_expiration_statement;

UPDATE orders SET expires_at = DATE_ADD(created_at, INTERVAL 15 MINUTE)
WHERE status = 'PENDING' AND expires_at IS NULL;

SET @order_migrated_at = UTC_TIMESTAMP(3);
UPDATE orders
SET status = 'CANCELED', cancel_reason = 'TIMEOUT', canceled_at = @order_migrated_at, updated_at = @order_migrated_at
WHERE status = 'PENDING' AND expires_at <= @order_migrated_at;

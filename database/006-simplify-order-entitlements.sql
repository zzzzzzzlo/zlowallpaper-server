-- 订单简化：删除创建请求表，权益表只保存当前有效权益。
-- 新库的001已经是七列权益结构；本脚本通过元数据检查兼容旧库，也允许重复执行。
-- 执行前核对历史退款订单应已为REFUNDED；撤销权益删除，用户、商品和订单历史保留。
DROP TABLE IF EXISTS order_create_requests;

SET @simplify_sql = (SELECT IF(COUNT(*) > 0,
    'DELETE FROM entitlements WHERE status = ''REVOKED''', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'entitlements' AND column_name = 'status');
PREPARE simplify_statement FROM @simplify_sql;
EXECUTE simplify_statement;
DEALLOCATE PREPARE simplify_statement;

SET @simplify_sql = (SELECT IF(COUNT(*) > 0,
    'ALTER TABLE entitlements DROP CHECK ck_entitlements_revocation', 'SELECT 1')
    FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 'entitlements' AND constraint_name = 'ck_entitlements_revocation');
PREPARE simplify_statement FROM @simplify_sql;
EXECUTE simplify_statement;
DEALLOCATE PREPARE simplify_statement;

SET @simplify_sql = (SELECT IF(COUNT(*) > 0,
    'ALTER TABLE entitlements DROP CHECK ck_entitlements_status', 'SELECT 1')
    FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 'entitlements' AND constraint_name = 'ck_entitlements_status');
PREPARE simplify_statement FROM @simplify_sql;
EXECUTE simplify_statement;
DEALLOCATE PREPARE simplify_statement;

SET @simplify_sql = (SELECT IF(COUNT(*) > 0,
    'ALTER TABLE entitlements DROP INDEX idx_entitlements_user_status', 'SELECT 1')
    FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'entitlements' AND index_name = 'idx_entitlements_user_status');
PREPARE simplify_statement FROM @simplify_sql;
EXECUTE simplify_statement;
DEALLOCATE PREPARE simplify_statement;

SET @simplify_sql = (SELECT IF(COUNT(*) > 0,
    'ALTER TABLE entitlements DROP COLUMN status', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'entitlements' AND column_name = 'status');
PREPARE simplify_statement FROM @simplify_sql;
EXECUTE simplify_statement;
DEALLOCATE PREPARE simplify_statement;

SET @simplify_sql = (SELECT IF(COUNT(*) > 0,
    'ALTER TABLE entitlements DROP COLUMN revoked_at', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'entitlements' AND column_name = 'revoked_at');
PREPARE simplify_statement FROM @simplify_sql;
EXECUTE simplify_statement;
DEALLOCATE PREPARE simplify_statement;

SET @simplify_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE entitlements ADD INDEX idx_entitlements_user_granted (user_id, granted_at, id)', 'SELECT 1')
    FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'entitlements' AND index_name = 'idx_entitlements_user_granted');
PREPARE simplify_statement FROM @simplify_sql;
EXECUTE simplify_statement;
DEALLOCATE PREPARE simplify_statement;
ALTER TABLE entitlements COMMENT = '用户的当前有效壁纸权益，退款删除，重新购买新增';

-- 简化已有sessions表：保留所有会话ID、用户、哈希、时间和撤销状态。
-- 旧的已轮换记录仍保持撤销；已有有效登录沿用当前ID，不重新激活旧记录。
-- 执行前备份sessions。DDL隐式提交；单次ALTER去掉旧约束、索引和三列。
-- 新库的002已是简化结构；此迁移按元数据判断，可重复执行。
SET @simplify_sessions_sql = (
    SELECT IF(COUNT(*) > 0,
        'ALTER TABLE sessions DROP CHECK ck_sessions_revocation, DROP INDEX uk_sessions_replaced_by, DROP INDEX idx_sessions_family_id, DROP COLUMN family_id, DROP COLUMN revoke_reason, DROP COLUMN replaced_by_id, MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT ''会话ID，JWT的sid关联此记录'', MODIFY COLUMN expires_at DATETIME(3) NOT NULL COMMENT ''本次登录固定截止时间，UTC'', COMMENT=''独立登录会话''',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'sessions' AND column_name = 'family_id'
);
PREPARE simplify_sessions_statement FROM @simplify_sessions_sql;
EXECUTE simplify_sessions_statement;
DEALLOCATE PREPARE simplify_sessions_statement;

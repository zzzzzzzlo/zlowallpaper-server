-- 移除未独立使用的显示名称，用户身份始终由 users.id 关联。
-- 执行前备份 id、display_name；不修改用户名、密码、余额及其他表的用户ID。
-- 元数据判断兼容已执行的数据库，以及使用新版001初始化的新库。
SET @remove_display_name_sql = (
    SELECT IF(COUNT(*) > 0, 'ALTER TABLE users DROP COLUMN display_name', 'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'display_name'
);
PREPARE remove_display_name_statement FROM @remove_display_name_sql;
EXECUTE remove_display_name_statement;
DEALLOCATE PREPARE remove_display_name_statement;

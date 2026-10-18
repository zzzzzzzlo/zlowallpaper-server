-- 开发环境手动设置测试余额，不是充值接口，也不会随迁移自动执行。
-- 按需修改用户名和金额；10000最小单位=100枚壁纸币。执行前核对数据库及账号。
SET @coin_test_username = 'userzlo';
SET @coin_test_balance = 10000;

UPDATE users
SET balance_cents = @coin_test_balance, updated_at = UTC_TIMESTAMP(3)
WHERE username = @coin_test_username;

SELECT id, username, balance_cents FROM users WHERE username = @coin_test_username;

-- 事务内验证：所有示例业务数据最终回滚。负数测试ID避免推进自增序列。
USE zlowallpaper;
SET SESSION time_zone = '+00:00';
START TRANSACTION;
INSERT INTO users (id, username, password_hash, role)
VALUES (-91001, '__schema_check_admin', 'not-a-real-password-hash', 'ADMIN'),
       (-91002, '__schema_check_buyer', 'not-a-real-password-hash', 'USER');
UPDATE users SET balance_cents = 10000 WHERE id = -91002;
INSERT INTO categories (id, name) VALUES (-91001, '__schema_check_category');
INSERT INTO wallpapers (id, title, category_id, type, price_cents, created_by, publication_status, published_at,
    original_filename, original_storage_provider, original_object_key, original_mime_type, original_file_extension,
    original_size_bytes, original_sha256, original_width, original_height, original_fps, original_status, original_uploaded_by, original_uploaded_at,
    cover_filename, cover_storage_provider, cover_object_key, cover_mime_type, cover_file_extension,
    cover_size_bytes, cover_sha256, cover_width, cover_height, cover_fps, cover_status, cover_uploaded_by, cover_uploaded_at)
VALUES (-91001, '验证商品', -91001, 'VIDEO', 1200, -91001, 'PUBLISHED', UTC_TIMESTAMP(3),
    'original.mp4','LOCAL','checks/original.mp4','video/mp4','mp4',200,REPEAT('b',64),1920,1080,30,'READY',-91001,UTC_TIMESTAMP(3),
    'cover.png','LOCAL','checks/cover.png','image/png','png',100,REPEAT('a',64),1920,1080,0,'READY',-91001,UTC_TIMESTAMP(3));
INSERT INTO orders (id, user_id, wallpaper_id, title_snapshot, amount_cents)
VALUES (-91001, -91002, -91001, '验证商品', 1200);
UPDATE users SET balance_cents = balance_cents - 1200 WHERE id = -91002 AND balance_cents >= 1200;
UPDATE orders SET status = 'PAID', payment_method = 'COIN', paid_at = UTC_TIMESTAMP(3), updated_at = UTC_TIMESTAMP(3) WHERE id = -91001;
INSERT INTO entitlements (id, user_id, wallpaper_id, source_order_id)
VALUES (-91001, -91002, -91001, -91001);
UPDATE wallpapers SET publication_status = 'WITHDRAWN', price_cents = 2400, updated_at = UTC_TIMESTAMP(3) WHERE id = -91001;
SELECT 'withdrawal_preserves_purchase_and_assets' AS check_name,
    (SELECT COUNT(*) FROM entitlements WHERE user_id = -91002 AND wallpaper_id = -91001) = 1
    AND (SELECT amount_cents FROM orders WHERE id = -91001) = 1200
    AND (SELECT balance_cents FROM users WHERE id = -91002) = 8800
    AND (SELECT original_object_key FROM wallpapers WHERE id = -91001) = 'checks/original.mp4'
    AND (SELECT cover_object_key FROM wallpapers WHERE id = -91001) = 'checks/cover.png' AS passed;
UPDATE orders SET status = 'REFUNDED', refunded_at = UTC_TIMESTAMP(3), updated_at = UTC_TIMESTAMP(3) WHERE id = -91001;
DELETE FROM entitlements WHERE user_id = -91002 AND wallpaper_id = -91001 AND source_order_id = -91001;
-- 仅演示未来COIN退款的原子结果；真实退款API仍未开发，届时必须按订单状态防止重复退币。
UPDATE users SET balance_cents = balance_cents + 1200 WHERE id = -91002;
UPDATE wallpapers SET publication_status = 'PUBLISHED', published_at = UTC_TIMESTAMP(3), updated_at = UTC_TIMESTAMP(3) WHERE id = -91001;
INSERT INTO orders (id, user_id, wallpaper_id, title_snapshot, amount_cents, status, payment_method, paid_at)
VALUES (-91002, -91002, -91001, '验证商品', 2400, 'PAID', 'COIN', UTC_TIMESTAMP(3));
UPDATE users SET balance_cents = balance_cents - 2400 WHERE id = -91002 AND balance_cents >= 2400;
INSERT INTO entitlements (id, user_id, wallpaper_id, source_order_id) VALUES (-91002, -91002, -91001, -91002);
SELECT 'refund_then_repurchase_keeps_order_history' AS check_name,
    (SELECT COUNT(*) FROM orders WHERE user_id = -91002 AND wallpaper_id = -91001) = 2
    AND (SELECT COUNT(*) FROM entitlements WHERE user_id = -91002 AND wallpaper_id = -91001) = 1
    AND (SELECT balance_cents FROM users WHERE id = -91002) = 7600
    AND (SELECT source_order_id FROM entitlements WHERE id = -91002) = -91002 AS passed;
ROLLBACK;
SELECT 'verification_data_rolled_back' AS check_name,
    (SELECT COUNT(*) FROM users WHERE id IN (-91001, -91002)) = 0
    AND (SELECT COUNT(*) FROM categories WHERE id = -91001) = 0
    AND (SELECT COUNT(*) FROM wallpapers WHERE id = -91001) = 0
    AND (SELECT COUNT(*) FROM orders WHERE id IN (-91001, -91002)) = 0
    AND (SELECT COUNT(*) FROM entitlements WHERE id IN (-91001, -91002)) = 0 AS passed;

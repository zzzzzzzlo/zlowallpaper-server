-- 事务内验证：所有示例业务数据最终回滚。负数测试ID避免推进自增序列。
USE zlowallpaper;
SET SESSION time_zone = '+00:00';
START TRANSACTION;
INSERT INTO users (id, username, password_hash, display_name, role)
VALUES (-91001, '__schema_check_admin', 'not-a-real-password-hash', '验证管理员', 'ADMIN'),
       (-91002, '__schema_check_buyer', 'not-a-real-password-hash', '验证购买者', 'USER');
INSERT INTO categories (id, name) VALUES (-91001, '__schema_check_category');
INSERT INTO assets (id, purpose, original_filename, object_key, mime_type, file_extension, size_bytes, sha256, width, height, fps, status, uploaded_by)
VALUES (-91001, 'COVER', 'cover.png', 'checks/cover.png', 'image/png', 'png', 100, REPEAT('a',64), 1920, 1080, 0, 'READY', -91001),
       (-91002, 'ORIGINAL', 'original.mp4', 'checks/original.mp4', 'video/mp4', 'mp4', 200, REPEAT('b',64), 1920, 1080, 30, 'READY', -91001);
INSERT INTO wallpapers (id, title, category_id, type, price_cents, cover_asset_id, created_by)
VALUES (-91001, '验证商品', -91001, 'VIDEO', 1200, -91001, -91001);
INSERT INTO wallpaper_resources (id, wallpaper_id, version, original_asset_id)
VALUES (-91001, -91001, 1, -91002);
UPDATE wallpapers SET current_resource_version = 1, publication_status = 'PUBLISHED', published_at = UTC_TIMESTAMP(3), updated_at = UTC_TIMESTAMP(3) WHERE id = -91001;
INSERT INTO orders (id, user_id, wallpaper_id, title_snapshot, amount_cents)
VALUES (-91001, -91002, -91001, '验证商品', 1200);
UPDATE orders SET status = 'PAID', payment_method = 'TEST', paid_at = UTC_TIMESTAMP(3), updated_at = UTC_TIMESTAMP(3) WHERE id = -91001;
INSERT INTO entitlements (id, user_id, wallpaper_id, source_order_id)
VALUES (-91001, -91002, -91001, -91001);
UPDATE wallpapers SET publication_status = 'WITHDRAWN', price_cents = 2400, updated_at = UTC_TIMESTAMP(3) WHERE id = -91001;
SELECT 'withdrawal_preserves_purchase_and_assets' AS check_name,
    (SELECT COUNT(*) FROM entitlements WHERE user_id = -91002 AND wallpaper_id = -91001 AND status = 'ACTIVE') = 1
    AND (SELECT amount_cents FROM orders WHERE id = -91001) = 1200
    AND (SELECT COUNT(*) FROM wallpaper_resources WHERE wallpaper_id = -91001) = 1
    AND (SELECT COUNT(*) FROM assets WHERE id IN (-91001, -91002)) = 2 AS passed;
UPDATE orders SET status = 'REFUNDED', refunded_at = UTC_TIMESTAMP(3), updated_at = UTC_TIMESTAMP(3) WHERE id = -91001;
UPDATE entitlements SET status = 'REVOKED', revoked_at = UTC_TIMESTAMP(3), updated_at = UTC_TIMESTAMP(3) WHERE id = -91001;
UPDATE wallpapers SET publication_status = 'PUBLISHED', published_at = UTC_TIMESTAMP(3), updated_at = UTC_TIMESTAMP(3) WHERE id = -91001;
INSERT INTO orders (id, user_id, wallpaper_id, title_snapshot, amount_cents, status, payment_method, paid_at)
VALUES (-91002, -91002, -91001, '验证商品', 2400, 'PAID', 'TEST', UTC_TIMESTAMP(3));
UPDATE entitlements SET source_order_id = -91002, status = 'ACTIVE', revoked_at = NULL, granted_at = UTC_TIMESTAMP(3), updated_at = UTC_TIMESTAMP(3) WHERE id = -91001;
SELECT 'refund_then_repurchase_keeps_order_history' AS check_name,
    (SELECT COUNT(*) FROM orders WHERE user_id = -91002 AND wallpaper_id = -91001) = 2
    AND (SELECT COUNT(*) FROM entitlements WHERE user_id = -91002 AND wallpaper_id = -91001) = 1
    AND (SELECT source_order_id FROM entitlements WHERE id = -91001) = -91002 AS passed;
ROLLBACK;
SELECT 'verification_data_rolled_back' AS check_name,
    (SELECT COUNT(*) FROM users WHERE id IN (-91001, -91002)) = 0
    AND (SELECT COUNT(*) FROM categories WHERE id = -91001) = 0
    AND (SELECT COUNT(*) FROM assets WHERE id IN (-91001, -91002)) = 0
    AND (SELECT COUNT(*) FROM wallpapers WHERE id = -91001) = 0
    AND (SELECT COUNT(*) FROM wallpaper_resources WHERE id = -91001) = 0
    AND (SELECT COUNT(*) FROM orders WHERE id IN (-91001, -91002)) = 0
    AND (SELECT COUNT(*) FROM entitlements WHERE id = -91001) = 0 AS passed;

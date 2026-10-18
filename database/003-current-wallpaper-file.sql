-- 2026-10-13：商品只关联当前文件，取消历史资源版本表。
-- 先迁移当前关联，再移除历史结构；不删除 assets 记录或 OSS 文件。
-- DDL 会隐式提交，不能依赖 ROLLBACK 撤销本迁移；已有数据时先备份。
USE zlowallpaper;
ALTER TABLE wallpapers ADD COLUMN original_asset_id BIGINT NULL
    COMMENT '当前原文件资产ID，不维护历史版本' AFTER cover_asset_id;
UPDATE wallpapers w
JOIN wallpaper_resources r ON r.wallpaper_id = w.id AND r.version = w.current_resource_version
SET w.original_asset_id = r.original_asset_id;
ALTER TABLE wallpapers
    DROP FOREIGN KEY fk_wallpapers_current_resource,
    DROP CHECK ck_wallpapers_published,
    DROP CHECK ck_wallpapers_version,
    DROP INDEX idx_wallpapers_current_resource,
    DROP COLUMN current_resource_version,
    ADD CONSTRAINT fk_wallpapers_original FOREIGN KEY (original_asset_id)
        REFERENCES assets (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT ck_wallpapers_published CHECK (publication_status <> 'PUBLISHED'
        OR (cover_asset_id IS NOT NULL AND original_asset_id IS NOT NULL AND published_at IS NOT NULL));
DROP TABLE wallpaper_resources;

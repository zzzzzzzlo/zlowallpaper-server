-- 2026-10-13：文件元数据直接写入商品，取消独立 assets 表。
-- 前置：001、002、003 已执行；先迁移当前两份文件，再删除旧关联与表。
-- DDL 隐式提交。含真实数据的环境先备份，并另行核对未被商品引用的旧资产。
-- 本机执行前确认 wallpapers 和 assets 都为0行；本脚本不删除任何 OSS 对象。
USE zlowallpaper;
ALTER TABLE wallpapers
    ADD COLUMN original_filename VARCHAR(255) NULL COMMENT '原文件：原始文件名，仅展示',
    ADD COLUMN original_storage_provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原文件：存储方式：LOCAL / OSS',
    ADD COLUMN original_bucket VARCHAR(128) NULL COMMENT '原文件：私有 OSS Bucket，本地为空',
    ADD COLUMN original_object_key VARCHAR(512) COLLATE utf8mb4_0900_bin NULL COMMENT '原文件：稳定对象键或相对路径，不存签名URL',
    ADD COLUMN original_object_version_id VARCHAR(128) NULL COMMENT '原文件：OSS对象版本ID，仅用于精确删除，不是商品版本',
    ADD COLUMN original_mime_type VARCHAR(127) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原文件：服务端检测的真实媒体类型',
    ADD COLUMN original_file_extension VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原文件：扩展名，不含点',
    ADD COLUMN original_size_bytes BIGINT NULL COMMENT '原文件：实际文件字节数',
    ADD COLUMN original_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原文件：实际文件SHA-256，64位小写十六进制',
    ADD COLUMN original_width INT NULL COMMENT '原文件：像素宽度',
    ADD COLUMN original_height INT NULL COMMENT '原文件：像素高度',
    ADD COLUMN original_fps DECIMAL(10,3) NULL COMMENT '原文件：帧率，静态图片为0',
    ADD COLUMN original_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原文件：文件状态：PROCESSING / READY / FAILED',
    ADD COLUMN original_uploaded_by BIGINT NULL COMMENT '原文件：上传管理员ID',
    ADD COLUMN original_uploaded_at DATETIME(3) NULL COMMENT '原文件：上传时间，UTC',
    ADD COLUMN cover_filename VARCHAR(255) NULL COMMENT '封面：原始文件名，仅展示',
    ADD COLUMN cover_storage_provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '封面：存储方式：LOCAL / OSS',
    ADD COLUMN cover_bucket VARCHAR(128) NULL COMMENT '封面：私有 OSS Bucket，本地为空',
    ADD COLUMN cover_object_key VARCHAR(512) COLLATE utf8mb4_0900_bin NULL COMMENT '封面：稳定对象键或相对路径，不存签名URL',
    ADD COLUMN cover_object_version_id VARCHAR(128) NULL COMMENT '封面：OSS对象版本ID，仅用于精确删除，不是商品版本',
    ADD COLUMN cover_mime_type VARCHAR(127) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '封面：服务端检测的真实媒体类型',
    ADD COLUMN cover_file_extension VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '封面：扩展名，不含点',
    ADD COLUMN cover_size_bytes BIGINT NULL COMMENT '封面：实际文件字节数',
    ADD COLUMN cover_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '封面：实际文件SHA-256，64位小写十六进制',
    ADD COLUMN cover_width INT NULL COMMENT '封面：像素宽度',
    ADD COLUMN cover_height INT NULL COMMENT '封面：像素高度',
    ADD COLUMN cover_fps DECIMAL(10,3) NULL COMMENT '封面：帧率，静态图片为0',
    ADD COLUMN cover_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '封面：文件状态：PROCESSING / READY / FAILED',
    ADD COLUMN cover_uploaded_by BIGINT NULL COMMENT '封面：上传管理员ID',
    ADD COLUMN cover_uploaded_at DATETIME(3) NULL COMMENT '封面：上传时间，UTC';
UPDATE wallpapers w
LEFT JOIN assets original ON original.id = w.original_asset_id
LEFT JOIN assets cover ON cover.id = w.cover_asset_id
SET w.original_filename = original.original_filename,
    w.original_storage_provider = original.storage_provider,
    w.original_bucket = original.bucket,
    w.original_object_key = original.object_key,
    w.original_mime_type = original.mime_type,
    w.original_file_extension = original.file_extension,
    w.original_size_bytes = original.size_bytes,
    w.original_sha256 = original.sha256,
    w.original_width = original.width,
    w.original_height = original.height,
    w.original_fps = original.fps,
    w.original_status = original.status,
    w.original_uploaded_by = original.uploaded_by,
    w.original_uploaded_at = original.created_at,
    w.cover_filename = cover.original_filename,
    w.cover_storage_provider = cover.storage_provider,
    w.cover_bucket = cover.bucket,
    w.cover_object_key = cover.object_key,
    w.cover_mime_type = cover.mime_type,
    w.cover_file_extension = cover.file_extension,
    w.cover_size_bytes = cover.size_bytes,
    w.cover_sha256 = cover.sha256,
    w.cover_width = cover.width,
    w.cover_height = cover.height,
    w.cover_fps = cover.fps,
    w.cover_status = cover.status,
    w.cover_uploaded_by = cover.uploaded_by,
    w.cover_uploaded_at = cover.created_at;
ALTER TABLE wallpapers
    DROP FOREIGN KEY fk_wallpapers_cover,
    DROP FOREIGN KEY fk_wallpapers_original,
    DROP CHECK ck_wallpapers_published,
    DROP COLUMN cover_asset_id,
    DROP COLUMN original_asset_id,
    ADD CONSTRAINT fk_wallpapers_original_uploader FOREIGN KEY (original_uploaded_by) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT fk_wallpapers_cover_uploader FOREIGN KEY (cover_uploaded_by) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD CONSTRAINT ck_wallpapers_published CHECK (publication_status <> 'PUBLISHED' OR
        (published_at IS NOT NULL AND original_object_key IS NOT NULL AND cover_object_key IS NOT NULL
         AND original_status IS NOT NULL AND original_status = 'READY'
         AND cover_status IS NOT NULL AND cover_status = 'READY')),
    ADD CONSTRAINT ck_wallpapers_original_file CHECK (original_object_key IS NULL OR (
        original_filename IS NOT NULL AND original_storage_provider IS NOT NULL AND original_mime_type IS NOT NULL AND original_file_extension IS NOT NULL AND original_size_bytes IS NOT NULL AND original_sha256 IS NOT NULL AND original_status IS NOT NULL AND original_uploaded_by IS NOT NULL AND original_uploaded_at IS NOT NULL
        AND CHAR_LENGTH(original_object_key) > 0 AND CHAR_LENGTH(original_filename) > 0
        AND CHAR_LENGTH(original_storage_provider) > 0 AND CHAR_LENGTH(original_mime_type) > 0 AND CHAR_LENGTH(original_file_extension) > 0
        AND original_size_bytes BETWEEN 1 AND 10737418240
        AND REGEXP_LIKE(original_sha256, '^[0-9a-f]{64}$', 'c')
        AND (original_storage_provider <> 'OSS' OR (original_bucket IS NOT NULL AND CHAR_LENGTH(original_bucket) > 0)))),
    ADD CONSTRAINT ck_wallpapers_original_status CHECK (original_status IS NULL OR original_status IN ('PROCESSING','READY','FAILED')),
    ADD CONSTRAINT ck_wallpapers_original_dimensions CHECK ((original_width IS NULL OR original_width >= 0) AND (original_height IS NULL OR original_height >= 0) AND (original_fps IS NULL OR original_fps >= 0)),
    ADD CONSTRAINT ck_wallpapers_original_ready CHECK (original_status IS NULL OR original_status <> 'READY' OR (original_object_key IS NOT NULL AND original_width IS NOT NULL AND original_width > 0 AND original_height IS NOT NULL AND original_height > 0 AND original_fps IS NOT NULL)),
    ADD CONSTRAINT ck_wallpapers_cover_file CHECK (cover_object_key IS NULL OR (
        cover_filename IS NOT NULL AND cover_storage_provider IS NOT NULL AND cover_mime_type IS NOT NULL AND cover_file_extension IS NOT NULL AND cover_size_bytes IS NOT NULL AND cover_sha256 IS NOT NULL AND cover_status IS NOT NULL AND cover_uploaded_by IS NOT NULL AND cover_uploaded_at IS NOT NULL
        AND CHAR_LENGTH(cover_object_key) > 0 AND CHAR_LENGTH(cover_filename) > 0
        AND CHAR_LENGTH(cover_storage_provider) > 0 AND CHAR_LENGTH(cover_mime_type) > 0 AND CHAR_LENGTH(cover_file_extension) > 0
        AND cover_size_bytes BETWEEN 1 AND 4194304
        AND REGEXP_LIKE(cover_sha256, '^[0-9a-f]{64}$', 'c')
        AND (cover_storage_provider <> 'OSS' OR (cover_bucket IS NOT NULL AND CHAR_LENGTH(cover_bucket) > 0)))),
    ADD CONSTRAINT ck_wallpapers_cover_status CHECK (cover_status IS NULL OR cover_status IN ('PROCESSING','READY','FAILED')),
    ADD CONSTRAINT ck_wallpapers_cover_dimensions CHECK ((cover_width IS NULL OR cover_width >= 0) AND (cover_height IS NULL OR cover_height >= 0) AND (cover_fps IS NULL OR cover_fps >= 0)),
    ADD CONSTRAINT ck_wallpapers_cover_ready CHECK (cover_status IS NULL OR cover_status <> 'READY' OR (cover_object_key IS NOT NULL AND cover_width IS NOT NULL AND cover_width > 0 AND cover_height IS NOT NULL AND cover_height > 0 AND cover_fps IS NOT NULL));
DROP TABLE assets;

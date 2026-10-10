-- 初始业务表：只在空的 zlowallpaper 数据库执行，不删除或覆盖已有表。
-- sessions / idempotency_records 待定，本文件不创建。
USE zlowallpaper;
SET NAMES utf8mb4;
SET SESSION time_zone = '+00:00';

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username VARCHAR(32) NOT NULL COMMENT '登录用户名',
    password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希，不存明文',
    display_name VARCHAR(64) NOT NULL COMMENT '显示名称',
    role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'USER' COMMENT '角色：USER / ADMIN',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '账号状态：ACTIVE / DISABLED',
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)) COMMENT '创建时间，UTC',
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)) COMMENT '修改时间，UTC，由应用更新',
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    CONSTRAINT ck_users_username_length CHECK (CHAR_LENGTH(username) BETWEEN 3 AND 32),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商城用户及管理员账号';

CREATE TABLE categories (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分类ID',
    name VARCHAR(64) NOT NULL COMMENT '分类名称',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1启用，0停用',
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)) COMMENT '创建时间，UTC',
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)) COMMENT '修改时间，UTC，由应用更新',
    PRIMARY KEY (id),
    UNIQUE KEY uk_categories_name (name),
    KEY idx_categories_enabled_sort (enabled, sort_order, id),
    CONSTRAINT ck_categories_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_categories_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='壁纸商品分类';

CREATE TABLE assets (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '文件资产ID',
    purpose VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '用途：ORIGINAL原文件 / COVER封面',
    original_filename VARCHAR(255) NOT NULL COMMENT '上传时的文件名，仅作展示',
    storage_provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'LOCAL' COMMENT '存储方式，例如LOCAL / OSS / S3',
    bucket VARCHAR(128) NULL COMMENT '云存储桶，本地存储为空',
    object_key VARCHAR(512) COLLATE utf8mb4_0900_bin NOT NULL COMMENT '稳定存储键或相对路径，不存临时签名URL',
    mime_type VARCHAR(127) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '媒体类型，例如image/png',
    file_extension VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '文件扩展名，不带点',
    size_bytes BIGINT NOT NULL COMMENT '实际文件字节数',
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '实际文件SHA-256，64位小写十六进制',
    width INT NULL COMMENT '像素宽度，处理完成前允许为空',
    height INT NULL COMMENT '像素高度，处理完成前允许为空',
    fps DECIMAL(10,3) NULL COMMENT '帧率，静态图片为0，处理完成前允许为空',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PROCESSING' COMMENT '处理状态：PROCESSING / READY / FAILED',
    uploaded_by BIGINT NOT NULL COMMENT '上传者用户ID',
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)) COMMENT '创建时间，UTC',
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)) COMMENT '修改时间，UTC，由应用更新',
    PRIMARY KEY (id),
    KEY idx_assets_uploaded_created (uploaded_by, created_at, id),
    KEY idx_assets_status_created (status, created_at, id),
    CONSTRAINT fk_assets_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_assets_purpose CHECK (purpose IN ('ORIGINAL', 'COVER')),
    CONSTRAINT ck_assets_status CHECK (status IN ('PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT ck_assets_size CHECK (size_bytes > 0 AND size_bytes <= 10737418240 AND (purpose <> 'COVER' OR size_bytes <= 4194304)),
    CONSTRAINT ck_assets_sha256 CHECK (REGEXP_LIKE(sha256, '^[0-9a-f]{64}$', 'c')),
    CONSTRAINT ck_assets_dimensions CHECK ((width IS NULL OR width >= 0) AND (height IS NULL OR height >= 0) AND (fps IS NULL OR fps >= 0)),
    CONSTRAINT ck_assets_ready_metadata CHECK (status <> 'READY' OR (width IS NOT NULL AND width > 0 AND height IS NOT NULL AND height > 0 AND fps IS NOT NULL)),
    CONSTRAINT ck_assets_object_key CHECK (CHAR_LENGTH(object_key) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件位置及媒体元数据，文件本身不存数据库';

CREATE TABLE wallpapers (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '壁纸商品ID',
    title VARCHAR(120) NOT NULL COMMENT '商品标题',
    description VARCHAR(4000) NOT NULL DEFAULT '' COMMENT '商品描述',
    category_id BIGINT NOT NULL COMMENT '所属分类ID',
    type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '壁纸类型：IMAGE / GIF / VIDEO',
    price_cents INT NOT NULL COMMENT '当前售价，单位分',
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY' COMMENT '币种，首期CNY',
    creator VARCHAR(120) NOT NULL DEFAULT '' COMMENT '作品作者名称',
    cover_asset_id BIGINT NULL COMMENT '封面资产ID，组装草稿时允许为空',
    current_resource_version BIGINT NULL COMMENT '当前资源版本，组装草稿时允许为空',
    publication_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DRAFT' COMMENT '发布状态：DRAFT / PUBLISHED / WITHDRAWN',
    published_at DATETIME(3) NULL COMMENT '最近一次上架时间，UTC',
    created_by BIGINT NOT NULL COMMENT '创建商品的管理员用户ID',
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)) COMMENT '创建时间，UTC',
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)) COMMENT '修改时间，UTC，由应用更新',
    PRIMARY KEY (id),
    KEY idx_wallpapers_publication_created (publication_status, created_at, id),
    KEY idx_wallpapers_category_publication (category_id, publication_status, created_at, id),
    KEY idx_wallpapers_current_resource (id, current_resource_version),
    CONSTRAINT fk_wallpapers_category FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_wallpapers_cover FOREIGN KEY (cover_asset_id) REFERENCES assets (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_wallpapers_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_wallpapers_title CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT ck_wallpapers_type CHECK (type IN ('IMAGE', 'GIF', 'VIDEO')),
    CONSTRAINT ck_wallpapers_price CHECK (price_cents BETWEEN 0 AND 100000000),
    CONSTRAINT ck_wallpapers_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_wallpapers_publication CHECK (publication_status IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN')),
    CONSTRAINT ck_wallpapers_version CHECK (current_resource_version IS NULL OR current_resource_version > 0),
    CONSTRAINT ck_wallpapers_published CHECK (publication_status <> 'PUBLISHED' OR (cover_asset_id IS NOT NULL AND current_resource_version IS NOT NULL AND published_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='壁纸商品资料，停售使用WITHDRAWN，保留历史';

CREATE TABLE wallpaper_resources (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '壁纸资源记录ID',
    wallpaper_id BIGINT NOT NULL COMMENT '所属壁纸商品ID',
    version BIGINT NOT NULL COMMENT '资源版本号，从1递增',
    original_asset_id BIGINT NOT NULL COMMENT '该版本的原文件资产ID',
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)) COMMENT '创建时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_wallpaper_resources_version (wallpaper_id, version),
    CONSTRAINT fk_wallpaper_resources_wallpaper FOREIGN KEY (wallpaper_id) REFERENCES wallpapers (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_wallpaper_resources_asset FOREIGN KEY (original_asset_id) REFERENCES assets (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_wallpaper_resources_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品与原文件的版本关联，保留旧版本';

-- 当前版本必须属于该商品。创建流程：商品草稿 -> 资源版本 -> 回填当前版本。
ALTER TABLE wallpapers ADD CONSTRAINT fk_wallpapers_current_resource
    FOREIGN KEY (id, current_resource_version) REFERENCES wallpaper_resources (wallpaper_id, version)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

CREATE TABLE orders (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    user_id BIGINT NOT NULL COMMENT '购买者用户ID',
    wallpaper_id BIGINT NOT NULL COMMENT '购买的壁纸商品ID',
    title_snapshot VARCHAR(120) NOT NULL COMMENT '下单时的商品标题快照',
    amount_cents INT NOT NULL COMMENT '订单成交金额，单位分',
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CNY' COMMENT '成交币种',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING' COMMENT '订单状态：PENDING / PAID / CANCELED / REFUNDED',
    payment_method VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '支付方式：FREE / TEST，未付款为空',
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)) COMMENT '下单时间，UTC',
    paid_at DATETIME(3) NULL COMMENT '付款时间，UTC',
    canceled_at DATETIME(3) NULL COMMENT '取消时间，UTC',
    refunded_at DATETIME(3) NULL COMMENT '退款时间，UTC',
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)) COMMENT '修改时间，UTC，由应用更新',
    active_wallpaper_id BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING', 'PAID') THEN wallpaper_id ELSE NULL END) STORED COMMENT '数据库生成列，用于限制重复有效订单，Java不写入',
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_active_purchase (user_id, active_wallpaper_id),
    UNIQUE KEY uk_orders_owner_product (id, user_id, wallpaper_id),
    KEY idx_orders_user_created (user_id, created_at, id),
    KEY idx_orders_status_created (status, created_at, id),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_orders_wallpaper FOREIGN KEY (wallpaper_id) REFERENCES wallpapers (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_orders_amount CHECK (amount_cents BETWEEN 0 AND 100000000),
    CONSTRAINT ck_orders_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING', 'PAID', 'CANCELED', 'REFUNDED')),
    CONSTRAINT ck_orders_payment_method CHECK (payment_method IS NULL OR payment_method IN ('FREE', 'TEST')),
    CONSTRAINT ck_orders_paid_metadata CHECK (status NOT IN ('PAID', 'REFUNDED') OR (paid_at IS NOT NULL AND payment_method IS NOT NULL)),
    CONSTRAINT ck_orders_canceled_metadata CHECK (status <> 'CANCELED' OR canceled_at IS NOT NULL),
    CONSTRAINT ck_orders_refunded_metadata CHECK (status <> 'REFUNDED' OR refunded_at IS NOT NULL),
    CONSTRAINT ck_orders_free_payment CHECK (payment_method IS NULL OR payment_method <> 'FREE' OR amount_cents = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单商品订单，保存成交快照与支付状态';

CREATE TABLE entitlements (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '购买权益ID',
    user_id BIGINT NOT NULL COMMENT '权益所属用户ID',
    wallpaper_id BIGINT NOT NULL COMMENT '拥有的壁纸商品ID',
    source_order_id BIGINT NOT NULL COMMENT '授予本次权益的订单ID',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE' COMMENT '权益状态：ACTIVE / REVOKED',
    granted_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)) COMMENT '本次授予时间，UTC',
    revoked_at DATETIME(3) NULL COMMENT '撤销时间，UTC',
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)) COMMENT '记录创建时间，UTC',
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)) COMMENT '修改时间，UTC，由应用更新',
    PRIMARY KEY (id),
    UNIQUE KEY uk_entitlements_user_wallpaper (user_id, wallpaper_id),
    KEY idx_entitlements_user_status (user_id, status, granted_at, id),
    CONSTRAINT fk_entitlements_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_entitlements_wallpaper FOREIGN KEY (wallpaper_id) REFERENCES wallpapers (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_entitlements_source_order FOREIGN KEY (source_order_id, user_id, wallpaper_id) REFERENCES orders (id, user_id, wallpaper_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_entitlements_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_entitlements_revocation CHECK ((status = 'ACTIVE' AND revoked_at IS NULL) OR (status = 'REVOKED' AND revoked_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户的当前壁纸购买权益，撤销后重新购买可更新本记录';

-- 仅供admin-category内存数据库；其他商品查询表复用catalog-schema.sql。
CREATE TABLE categories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- H2按UTF-16单元计VARCHAR长度，128容纳64个补充平面码点；业务仍严格限制64码点。
    -- 正式MySQL使用utf8mb4 VARCHAR(64)，不需要加宽。
    name VARCHAR_IGNORECASE(128) NOT NULL UNIQUE,
    sort_order INT NOT NULL DEFAULT 0,
    enabled TINYINT NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)),
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL
);

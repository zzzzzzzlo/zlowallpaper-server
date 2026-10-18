CREATE TABLE orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    wallpaper_id BIGINT NOT NULL REFERENCES wallpapers(id),
    title_snapshot VARCHAR(120) NOT NULL,
    amount_cents INT NOT NULL CHECK(amount_cents BETWEEN 0 AND 100000000),
    currency CHAR(3) NOT NULL DEFAULT 'ZLC' CHECK(currency IN ('CNY','ZLC')),
    status VARCHAR(16) NOT NULL CHECK(status IN ('PENDING','PAID','CANCELED','REFUNDED')),
    payment_method VARCHAR(16) CHECK(payment_method IS NULL OR payment_method IN ('FREE','TEST','COIN')),
    created_at TIMESTAMP(3) NOT NULL, paid_at TIMESTAMP(3), canceled_at TIMESTAMP(3), refunded_at TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL,
    active_wallpaper_id BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING','PAID') THEN wallpaper_id ELSE NULL END),
    UNIQUE(user_id, active_wallpaper_id), UNIQUE(id, user_id, wallpaper_id),
    CHECK(status NOT IN ('PAID','REFUNDED') OR (paid_at IS NOT NULL AND payment_method IS NOT NULL)),
    CHECK(status<>'CANCELED' OR canceled_at IS NOT NULL),
    CHECK(status<>'REFUNDED' OR refunded_at IS NOT NULL),
    CHECK(payment_method IS NULL OR payment_method<>'FREE' OR amount_cents=0)
);
CREATE TABLE entitlements (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id), wallpaper_id BIGINT NOT NULL REFERENCES wallpapers(id),
    source_order_id BIGINT NOT NULL,
    granted_at TIMESTAMP(3) NOT NULL, created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
    UNIQUE(user_id, wallpaper_id),
    FOREIGN KEY(source_order_id,user_id,wallpaper_id) REFERENCES orders(id,user_id,wallpaper_id)
);

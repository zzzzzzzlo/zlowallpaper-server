# 数据模型

当前业务表为 users、sessions、categories、wallpapers、orders、entitlements。结构来自 [database](../database/README.md) 中顺序执行的 SQL；没有独立资产表、资源历史表或下单请求表。

表名和字段使用下划线，Java实体使用驼峰；Mapper XML明确列出查询字段。查询需要的WallpaperRow、CoverRow等模型只承接查询结果，不是额外数据库表。

## 用户与会话

| 表 | 主要字段 | 业务作用 |
| --- | --- | --- |
| users | id、username、password_hash、role、status、balance_cents | 用户身份、密码哈希、当前角色/状态及站内币余额 |
| sessions | id、user_id、family_id、refresh_token_hash、expires_at、revoked_at | 刷新令牌轮换、登出、过期与重放撤销 |

用户在订单、权益和会话中的关联均使用稳定的users.id，不用用户名作为外键。用户名唯一；显示名称字段已经移除。UserVO只公开id、username、balanceCents。

注册固定创建USER，管理员为ADMIN；状态为ACTIVE或DISABLED。每次认证核对当前账号状态与会话，不只验证JWT签名。数据库存密码哈希和刷新令牌哈希，不存明文密码或刷新令牌。

余额用BIGINT/Long保存整数最小单位，100单位=1枚壁纸币，新用户为0。JSON中的余额为字符串，避免客户端整数精度问题。修改用户名/密码、充值还没有后端接口。

## 分类与商品

| 表 | 主要字段 | 业务作用 |
| --- | --- | --- |
| categories | id、name、sort_order、enabled | 分类名称、展示顺序、启停 |
| wallpapers | id、title、description、category_id、type、price_cents、currency、creator、publication_status、published_at | 商品资料、售价和发布状态 |

商品类型为IMAGE、GIF或VIDEO；发布状态为DRAFT、PUBLISHED、WITHDRAWN。分类停用不自动下架商品，分类删除前检查所有状态商品的引用。

商品同时内嵌两组当前文件元数据：

| 前缀/字段 | 含义 |
| --- | --- |
| original_* | 用户购买后下载的当前原文件 |
| cover_* | 当前封面，新增上传经过缩放压缩 |
| filename | 上传展示名，不参与对象路径拼接 |
| storage_provider、bucket、object_key、object_version_id | 存储厂商、桶、UUID对象键和精确版本位置 |
| mime_type、file_extension | 最终文件真实类型 |
| size_bytes、sha256、width、height、fps | 最终文件字节数、完整内容指纹、尺寸和帧率 |
| status、uploaded_by、uploaded_at | 处理状态、上传管理员和UTC时间 |

没有单独资产ID或商品resourceVersion。原壁纸宽高、帧率、大小和SHA从original字段返回；coverUrl由服务端根据商品ID和访问场景生成，不单独保存临时URL。

草稿表结构允许尚未具备文件信息；当前创建接口仍要求两份有效文件，上架必须原文件/封面均READY。源封面上传最大10 MiB，处理后封面存储最大4 MiB，原文件最大10 GiB。静态图片fps为0。

更换原文件不会撤销已有购买权益，购买者取得商品当前文件。源封面文件名可能为PNG，最终存储扩展名为JPG，分别表示输入名称和处理结果。

## 订单与购买权益

| 表 | 主要字段 | 业务作用 |
| --- | --- | --- |
| orders | id、user_id、wallpaper_id、title_snapshot、amount_cents、currency、status、payment_method | 下单快照、付款状态和支付方式 |
| entitlements | id、user_id、wallpaper_id、source_order_id、granted_at | 当前有效购买权益和来源订单 |

orders状态支持PENDING、PAID、CANCELED、REFUNDED；当前业务只实现创建与付款，没有取消/退款入口。支付方式为FREE或COIN，TEST仅兼容历史数据。新订单币种为ZLC，旧已完成订单可能保留CNY。

订单标题和价格是下单快照，不跟随商品改名或调价变化。商品ID仍关联当前商品；不得因为价格变更就覆盖历史成交金额。

权益按用户/商品唯一，保存当前有效拥有关系，并关联来源订单。PAID订单没有对应权益会被视为状态冲突，不能简单再次扣费“补购”。未来退款若开发，还需要完整事务和幂等处理；不能仅凭Mapper已有删除方法宣称已经实现。

下单、付款先锁用户，再在事务内读取当前权益和订单。唯一约束保护重复有效订单和重复权益；余额扣减SQL带余额条件。同一事务完成扣币、订单PAID和权益写入，异常时整体回滚。

## 类型与时间

- 数据库主键BIGINT，Java使用Long，接口ID使用字符串。
- 商品/订单金额使用整数最小单位，priceCents/amountCents为整数；例如1200表示12枚壁纸币。
- 文件大小BIGINT/Long；帧率DECIMAL/BigDecimal。
- DATETIME(3)按UTC使用，接口输出带时区的ISO-8601时间。
- SHA-256是文件内容指纹，不代表已实现全局文件去重或自动完整性回读校验。
- 普通查询和FOR UPDATE用途不同；具体事务、锁及并发复查见 [调用链](api-call-chains.md)。

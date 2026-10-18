# ZloWallpaper 后端需求与接口交接

2026-10-17已购分页：GET /me/wallpapers支持page/pageSize/keyword/categoryId/wallpaperId，data改为ProductPage（items/page/pageSize/total），默认12、最多50条/页，取消200项总量限制。Qt搜索、筛选、翻页和跨页权益识别已适配，工作台不消费此接口。旧客户端必须同步更新；Apifox重新导入openapi.yaml。详见 [已购分页说明](E:/zlowallpaper_sever/docs/owned-pagination.md)。

2026-10-16 COS切换：现有42份文件已逐份下载校验后迁移至私有COS；新上传默认COS，保留旧OSS读取/签名/清理兼容。下载授权仍返回5分钟HTTPS签名URL，前端不需要厂商SDK或Endpoint，不能向云存储发送商城Bearer；封面仍通过原后端URL按权限读取。无字段、路径或数据库ID变化。配置及验收见 [COS迁移说明](E:/zlowallpaper_sever/docs/cos-migration.md)。

2026-10-16订单确认与客户端：Order统一新增userId、balanceCents，共9字段；余额是请求时的当前余额，不存入订单表。Qt创建收费订单后展示预览、账户余额与应扣款，关闭保留订单；取消入口仅预留。下载完成立即刷新本地库；顶部“我的已购/壁纸商城”切换与渐隐提示已接入。当前两端已使用真实壁纸币付款、已购与授权下载，下面有关客户端待适配的文字为阶段记录。详见[本次开发说明](E:/zlowallpaper_sever/docs/checkout-ui-development.md)。

2026-10-15已购与下载：GET /me/wallpapers已实现于catalog，复用WallpaperMapper并按当前账号权益关联查询，包含停售商品；200项上限，超限409 OWNED_LIST_LIMIT_EXCEEDED。coverUrl改为带Bearer读取的GET /me/wallpapers/{id}/cover，未购404；公开商品/封面条件不变。POST /wallpapers/{id}/download-grants已实现，返回5分钟OSS GET URL和当前文件校验信息；有存储版本时需oss:GetObjectVersion。真实OSS已复验HTTP 200、35,755,786字节、SHA一致。两端界面和图片授权读取留待统一适配，详情见[已购与下载开发说明](E:/zlowallpaper_sever/docs/download-development.md)。以下早期阶段记录以本段最新状态及openapi.yaml为准。

2026-10-15订单列表：GET /me/orders已实现，身份来自Bearer；返回本账号全部状态订单快照，createdAt及id倒序，空列表data=[]。不分页，最多1000条，超出返回409 ORDER_LIST_LIMIT_EXCEEDED而非截断。代码导读见[订单查询开发说明](E:/zlowallpaper_sever/docs/order-query-development.md)。已购列表和下载授权仍待开发。

2026-10-15壁纸币付款：POST /orders/{id}/pay已实现，实际扣余额；旧test-pay和开关已移除。新增User.balanceCents字符串，币种ZLC，100最小单位=1币。两端界面/客户端调用留待后续统一改造，本次仅同步契约供Apifox测试。

2026-10-15：旧独立上传接口已删除；创建/修改商品一次multipart提交资料与文件，当前接口以openapi.yaml为准。

2026-10-13 商品后续实现：管理员列表、一次multipart创建/修改、上下架与授权封面已完成，工作台新协议已启用；下文早期资产上传模型仅作历史记录。当前契约见openapi.yaml，代码导读见 [catalog-development3](../catalog-development3.md)。Qt封面缓存与交易下载接口按用户安排暂留。

2026-10-13 第二次调整：assets 表已并入 wallpapers，文件信息直接保存商品行；独立上传已停用，创建商品将一次提交资料、原文件及封面。商品写接口尚未实现。以 [两次修改清单](E:/zlowallpaper_sever/docs/catalog-refactor-changes.md) 为当前设计。

2026-10-13 更新：上传代码已并入 catalog，商品只关联当前原文件和封面，取消商品资源历史表及 resourceVersion。商品返回当前原文件 sha256，客户端按文件哈希识别下载。完整调整见 [商品文件管理说明](E:/zlowallpaper_sever/docs/catalog-resource-development.md)。

后端实施状态补充（2026-10-12）：管理员上传已实现，文件保存私有OSS并记录资产；媒体检查首期同步返回READY，不执行后台转码。公开封面入口已支持OSS；商品写接口和交易仍待开发。详见 [asset-development.md](E:/zlowallpaper_sever/docs/asset-development.md)。

后端实施状态补充（2026-10-11）：管理员分类列表、新增、修改、删除四接口已实现；对应代码与验收见 [catalog-development2.md](E:/zlowallpaper_sever/docs/catalog-development2.md)。下文交接时的“分类后端待实现”状态已被本次实现更新；商品管理、上传及真实工作台联调仍待后续完成。

版本：0.3.0 / API v1。更新：2026-10-10。状态：原有客户端已实现消费接口；分类管理增补已设计、前端已实现，新增后端接口待实现。其他后端接口状态以真实集成验收为准。

本文与 `openapi.yaml` 是后端交付基线。变更字段、路径或状态码应先更新契约，再修改客户端和测试。`src/store/DemoServer.*` 是本机开发测试夹具，可作为响应形状参考，不能作为生产后端、安全实现或持久化实现。

## 1. 业务范围与验收目标

产品：Windows 桌面壁纸商城，保留本地壁纸、视频/GIF 播放、Dock、透明任务栏、桌面图标开关、全屏暂停和睡眠恢复。

首期业务闭环：匿名浏览 → 注册/登录 → 创建订单 → 免费领取或测试支付 → 获得购买权益 → 获取下载授权 → 校验下载 → 加入本地库 → 应用到桌面。

本期不包含真实支付渠道、创作者分账、购物车、优惠券、会员和强 DRM。真实收银台未实现；客户端不得把 PENDING 当购买成功，站内币付款不调用外部扣款渠道。

| 需求编号 | 优先级 | 需求 | 验收 |
|---|---|---|---|
| AUTH-01 | P0 | 注册、登录、刷新、登出 | 账号唯一；错误密码失败；刷新令牌旋转；注销使相应会话失效 |
| STORE-01 | P0 | 分类、商品分页/搜索、详情 | 只有上架商品参与公开列表；价格由服务端管理 |
| ORDER-01 | P0 | 免费领取、创建订单、查询订单 | 身份来自会话；客户端不提交价格；重复请求不重复创建业务结果 |
| PAY-COIN-01 | P0 | 壁纸币支付 | 同一事务扣余额、标记PAID并授予权益；余额不足拒绝 |
| OWN-01 | P0 | 获取当前用户已购清单 | 只返回本账号；购买与下载状态独立；多次同步不重复入库 |
| FILE-01 | P0 | 购买后授权下载 | 无权益返回 403；下载授权包含真实大小、SHA-256和期限 |
| ADMIN-01 | P0 | 管理员上传、商品创建/编辑/上下架 | 普通用户不能操作；不允许任意服务器文件路径 |
| ADMIN-CATEGORY-01 | P0 | 分类新增、改名、排序、启停、删除 | 所有状态商品引用均禁止删除；停用不下架商品；原停用分类可保留 |
| OPS-01 | P0 | 错误响应、日志、健康检查、种子数据 | 返回 requestId，方便联调；日志不得包含密码/Token/带票据下载 URL |
| PAY-REAL-01 | 后续 | 真实支付 | 服务端验签、金额核对、回调幂等及退款策略；另行契约评审 |

## 2. 客户端已有边界

- UI：商城、详情、分类、搜索、分页、登录/注册、我的已购、订单、下载进度与取消。
- 网络：Qt Network 异步请求，不要求浏览器 CORS。服务端仍应按未来管理网站的来源策略配置 CORS。
- API：`ApiClient`；账号/商品/订单：`StoreService`；文件下载与入库：`DownloadManager`。
- 不登录、不联网，原本的本地壁纸及桌面美化功能仍可使用。
- 已下载的壁纸保留在本机，即使登出也可以在本地库离线播放。切换账号后，“我的已购”和商城下载映射按账号与服务器隔离。本期不尝试阻止用户复制已下载的原文件。
- 客户端持久化本机下载记录，不把本地绝对路径上传服务器。服务器是购买权益的唯一权威来源。
- 新壁纸文件、library.json和缩略图统一保存在程序旁的 loaded_wallpaper/（本机 E:\zlowallpaper\loaded_wallpaper），下载仍按服务器与账号分目录，临时文件在最终文件旁同盘提交。本次本机已有下载已经校验迁移并恢复记录。此本地位置变化不影响 API 契约。
- 商品文件按 `(API 根地址, userId, wallpaperId, sha256)` 区分；同一文件不重复入库，内容变化可重新下载；已下载本地文件不自动删除。

## 3. 统一协议

API 根地址默认 `http://127.0.0.1:8080/api/v1`；生产地址使用 HTTPS。客户端拒绝非本机的明文 HTTP。根路径固定 `/api/v1`，不携带 query/fragment/用户名密码。

JSON UTF-8，所有 ID 是字符串；User.balanceCents也是十进制字符串，新用户0，GET /me查询当前余额；价格与余额为壁纸币最小整数单位（100=1币，ZLC）；所有时间为 UTC ISO-8601，如 `2026-10-09T10:30:00Z`。`sizeBytes` 使用十进制字符串，防止跨语言大整数精度丢失。

成功（HTTP 200/201）：

```json
{"code":"OK","message":"","data":{},"requestId":"req-123"}
```

失败（保持实际 HTTP 错误状态）：

```json
{"code":"NOT_OWNED","message":"尚未拥有该壁纸，请先购买或领取","data":null,"requestId":"req-124"}
```

| HTTP | 典型 code | 行为 |
|---|---|---|
| 400/422 | VALIDATION_ERROR | 参数错误，展示可读信息 |
| 401 | INVALID_CREDENTIALS / SESSION_EXPIRED | 私有接口返回 401 时客户端清除会话，请用户登录 |
| 403 | NOT_OWNED / FORBIDDEN  | 无权益/无权限，不应伪装为 401 |
| 404 | PRODUCT_NOT_FOUND / ORDER_NOT_FOUND | 商品或本账号订单不可用 |
| 409 | USERNAME_EXISTS / IDEMPOTENCY_CONFLICT | 唯一约束或幂等键冲突 |
| 429 | RATE_LIMITED | 限流，不自动高频重试 |
| 500/503 | INTERNAL_ERROR / SERVICE_UNAVAILABLE | 返回 requestId，不把堆栈或数据库内容暴露给客户端 |

私有接口使用 `Authorization: Bearer <accessToken>`。客户端账号密码仅在注册/登录请求中发送；本地不保存密码。accessToken 只在内存，refreshToken 使用 Windows 当前用户 DPAPI 加密保存，且绑定 API 根地址；演示模式不持久化凭证。

API JSON 最大 4 MiB；商品列表每页默认 12、上限 50；封面不超过 4 MiB。V1 已购/订单使用完整数组：已购最多200项（含长描述）、订单最多1000项（快照字段少），超限返回明确409业务错误，不能静默截断；规模扩大时另发分页契约版本。

API 请求禁止重定向。图片与下载授权 URL 也必须是可直接 GET 的最终 HTTPS（或本机 HTTP）URL，不要依赖 302 跳转登录页面。

## 4. 客户端需要实现的后端接口

| 方法 | 路径（相对 API 根地址） | 权限 | 请求 | data |
|---|---|---|---|---|
| POST | `/auth/register` | 匿名 | username, password | `{username}` |
| POST | `/auth/login` | 匿名 | username, password | Session |
| POST | `/auth/refresh` | refreshToken | refreshToken | Session（新令牌） |
| POST | `/auth/logout` | refreshToken | refreshToken | `{}` |
| GET | `/me` | 登录 | 无 | User（客户端可扩展使用） |
| GET | `/categories` | 匿名 | 无 | Category[] |
| GET | `/wallpapers` | 匿名 | page, pageSize, keyword, categoryId | ProductPage |
| GET | `/wallpapers/{id}` | 匿名 | id | Product |
| POST | `/orders` | 登录 | wallpaperId；无需请求键 | Order |
| GET | `/me/orders` | 登录 | 无 | Order[] |
| POST | `/orders/{id}/pay` | 登录 | 订单ID，无业务请求体；扣壁纸币，重复付款不重复扣币 | Order |
| GET | `/me/wallpapers` | 登录 | 无 | Product[]（已拥有，包括停售但仍可下载的商品） |
| POST | `/wallpapers/{id}/download-grants` | 登录且拥有 | 无 body 字段 | DownloadGrant |

详细字段和参数约束见 `openapi.yaml`。接口不能在未实现时一律返回 200 空对象；客户端会据此错误判断业务完成。

### Session 与 User

```json
{
  "accessToken":"opaque-or-jwt-access-token",
  "refreshToken":"opaque-refresh-token",
  "expiresIn":691200,
  "user":{"id":"user-1","username":"alice","balanceCents":"0"}
}
```

用户名 3–32 字符，密码 8–128 字符。后端决定并校验允许的用户名字符；错误信息要明确。生产密码采用适合密码存储的算法，不能复用演示夹具的快速 SHA-256。刷新令牌需要可撤销、可轮换及重放处理；登出接口可使用刷新令牌而不依赖未过期的 accessToken。

客户端在 accessToken 到期前刷新；网络失败延迟重试，明确失效则删除凭证。购买/下载出现 401 时客户端要求重新登录，不默默重放付款请求。

### Product 与列表

```json
{
  "id":"wp-1001",
  "title":"雾山 · 清晨",
  "description":"一张适合专注工作时使用的壁纸。",
  "categoryId":"nature","categoryName":"自然",
  "type":"VIDEO","priceCents":1200,"currency":"ZLC",
  "coverUrl":"https://storage.example.com/covers/wp-1001.png",
  "width":1920,"height":1080,"fps":30,
  "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","sizeBytes":"18422301","creator":"作者名称"
}
```

`type` 为 IMAGE/GIF/VIDEO；图片 fps 为 0。分页 data 是 `{items: Product[], page: 1, pageSize: 12, total: 60}`。`keyword` 匹配标题，`categoryId` 空值代表全部；服务端使用稳定排序，建议按上架时间倒序再按 ID。公开详情不包含原文件 URL。

### Order 与付款

```json
{
  "id":"order-1001","userId":"user-1","balanceCents":"10000",
  "wallpaperId":"wp-1001","title":"雾山 · 清晨",
  "amountCents":1200,"currency":"ZLC","status":"PENDING",
  "createdAt":"2026-10-09T10:30:00Z"
}
```

- 状态：PENDING / PAID / CANCELED / REFUNDED。客户端展示全部状态，仅对PENDING订单提供壁纸币付款；取消入口暂未实现后端。
- userId来自订单归属；balanceCents为字符串，表示本次查询的当前余额。创建/付款保持原事务及用户行锁；付款响应为扣款后的余额。列表一次查询余额后复用，不按每笔订单查询，余额不是历史快照。
- 价格来自商品数据库；订单记录成交价格快照。客户端只提交 wallpaperId，不接受客户端自行宣告 PAID。
- 免费商品创建订单时直接 PAID 并授予权益。
- `/orders`无需请求键：先查询当前权益并返回来源订单，再复用本账号本商品的PENDING订单；确需新单才验证上架与定价。行锁、有效订单唯一约束和权益唯一约束保证基本购买防重，不保留旧创建请求结果。
- 对同用户同商品已有的有效购买，或尚未结束的订单，返回既有订单，避免用户刷新后再次创建/付款。
- 壁纸币付款已实现：先锁用户再锁订单，在事务中完成扣余额、PAID/COIN与权益写入；不足返回409 INSUFFICIENT_BALANCE。重复请求保留付款时间和方式；取消/退款订单409，记录冲突不自动补发权益。已有待付款订单按下单快照付款，商品之后调价或下架不影响该订单。
- 已购权益默认随商品更新保留。停售阻止新增购买，不默认剥夺既有下载权。
- 退款成功时在数据库事务内将订单改为REFUNDED、删除该来源订单授予的权益；未来COIN退款还需按原金额退币且只退一次（退款接口未开发）；旧退款重复通知不得删除重新购买产生的权益。已复制到本机的文件保留。

### DownloadGrant 与文件服务

```json
{
  "wallpaperId":"wp-1001",
  "url":"https://storage.example.com/download/ticket-xxx",
  "expiresAt":"2026-10-09T10:35:00Z",
  "fileExtension":"mp4","sizeBytes":"18422301",
  "sha256":"64个十六进制字符"
}
```

服务器在每次发票据时校验当前会话及权益，票据绑定当前文件与有效期。客户端在文件请求中不转发账号 Authorization，票据 URL 本身授权；存储/CDN 返回 HTTP 200 原始文件，不包 JSON、不返回网页。

文件支持 jpg/jpeg/png/bmp/webp/gif/mp4/webm/mov/avi；原文件最大 10 GiB。服务器保证扩展名和真实格式相符，size/hash 对应实际响应字节，不能在下载时变换编码却沿用旧 hash。票据过期后重新申请；首期不要求断点续传，取消/失败后从头下载。

客户端使用 AtomicFile（已有目标解析文件真实路径，否则解析父目录的 QSaveFile）临时写入、流式计算 hash、校验字节总数和 SHA-256 后原子提交，后台生成缩略图，再保存 library.json。持久化失败时不得提示已入库。最多同时处理 3 个下载。旧账号任务在切换账号后取消，不会写到新账号条目。

## 5. 管理端需求（桌面客户端不消费）

后端同样需要这些接口，首期可用 Swagger/Postman 代替管理网站：

| 方法 | 路径 | 要求 |
|---|---|---|
| GET | `/admin/wallpapers` | 分页查看包含未上架的商品 |
| POST | `/admin/wallpapers` | 一次multipart提交title、categoryId、priceCents、originalFile、coverFile及可选description、creator；初始DRAFT |
| PATCH | `/admin/wallpapers/{id}` | multipart提交变化资料及可选originalFile/coverFile；省略文件保留，不保存历史版本 |
| PUT | `/admin/wallpapers/{id}/publication` | `{status: PUBLISHED 或 WITHDRAWN}`；完整资源验证通过才能上架 |

管理员身份来自服务端角色，不能相信客户端传入 isAdmin。上传文件存储位置由服务器控制，当前文件的对象键和元数据直接保存在wallpapers，不接收客户端指定的存储路径或资产ID。上传首期同步完成媒体与首帧检查，不在数据库事务中长时间检查。

### 分类管理增补（2026-10-10，已设计，后端待实现）

独立 HTML 管理工作台已接入本次批准的增补；Qt 客户端功能不变。四个接口均由 catalog 模块、CategoryMapper 实现，所有请求校验管理员 Bearer 权限。首期完整列表不分页。

| 方法 | 路径 | 成功 HTTP / data | 请求 |
|---|---|---|---|
| GET | `/admin/categories` | 200 / AdminCategory[]（含停用） | 无业务参数 |
| POST | `/admin/categories` | 201 / AdminCategory | name 必填；sortOrder、enabled 可选，默认 0、true |
| PATCH | `/admin/categories/{id}` | 200 / AdminCategory | name、sortOrder、enabled 至少一项，仅实际变化项 |
| DELETE | `/admin/categories/{id}` | 200 / {} | 无请求体；不得返回 204 |

AdminCategory 必填：字符串 id、trim 后 1–64 个 Unicode 字符的 name、int32 sortOrder（-2147483648 至 2147483647）、布尔 enabled、非负整数 wallpaperCount。编号和引用数由服务端生成，不接受客户端写入；引用数动态计算所有 DRAFT/PUBLISHED/WITHDRAWN 商品，不新增数据库字段。名称不区分大小写地唯一，停用分类也参与判重。相同排序值按 id 升序。

删除安全边界：任何商品引用都禁止删除。列表计数仅是快照，服务端必须最终校验并保留 `wallpapers.category_id ON DELETE RESTRICT` 处理并发引用。不能连带删除商品、文件、订单或购买权益；不能将删除失败转换成停用。现有数据库结构已支持本次功能，无需建表或结构迁移。

停用不下架已有商品、不改变购买权益；公开 GET /categories 保持匿名、仅返回启用分类的 id/name。商品编辑器保留原停用分类并标注“已停用”，未改分类时 PATCH 不发送 categoryId；新建或换到其他分类仅接受启用分类，后端做最终校验。新增、改名、启停、删除成功后前端重读管理列表及公开分类；其他设备重新请求读取最新状态。

错误保留统一外壳与 data:null：400 VALIDATION_ERROR、401 SESSION_EXPIRED、403 FORBIDDEN、404 CATEGORY_NOT_FOUND（包括重复删除）、409 CATEGORY_NAME_EXISTS / CATEGORY_IN_USE。409 CATEGORY_IN_USE 显示原因并刷新列表。网络、超时、500/503 不显示成功、不重放写请求，不回退模拟数据。

浏览器 CORS 允许管理端具体来源及 GET/POST/PATCH/PUT/**DELETE**/OPTIONS、Authorization/Content-Type；OPTIONS 预检不要求 Bearer，业务请求仍需鉴权。真实新增接口尚未实现，不得将本机夹具测试当作后端联调通过；接口验收覆盖全部操作、引用快照过期、原停用分类保留、401/403/故障及空分类。

## 6. 建议最小数据模型

- users：id、username 唯一、password_hash、role、status、balance_cents（默认0）、时间。
- sessions：用户、refresh_token_hash、有效期、撤销时间、旋转链；不得明文存 refreshToken。
- categories：id、name、排序、状态。
- assets：id、object_key、purpose、mime、extension、size、sha256、width、height、fps、处理状态。
- wallpapers：id、商品字段、category_id、price_cents、currency、当前原文件资产、封面资产、发布状态。
- orders：id、user_id、wallpaper_id、金额快照、状态、支付渠道、created_at。
- entitlements：user_id + wallpaper_id 唯一、来源订单、有效/撤销状态、时间。
- idempotency_records：user_id + operation + key 唯一、请求 hash、响应快照、过期时间。

数据库事务覆盖“支付完成 + 权益创建”。不要把媒体文件直接存大 BLOB，也不需要首期引入微服务。

## 7. 按企业开发流程推进

1. 需求评审：确认本文件的范围、账号规则、免费领取、测试支付、停售/退款及离线策略。
2. 契约评审：以 OpenAPI 为接口基线，冻结 v1 字段；为每个需求编号写成功与失败用例。
3. 后端设计：数据库迁移、权限模型、文件存储、事务/幂等与错误响应。
4. 分阶段实现：商品公开接口 → 鉴权 → 订单/权益 → 文件授权 → 管理接口。
5. 联调：启动本机后端，桌面“连接设置”填写 API 根地址；关闭演示模式，用真实后端账号走闭环。
6. 集成验收：执行下表，再补服务端越权与事务测试。服务端日志和 requestId 可关联。
7. 发布：生产必须 HTTPS；旧test-pay已删除，正常付款只扣站内币；部署健康检查、迁移和备份，并提供版本与回滚说明。客户端当前没有真实收银台，不能因此宣称支持生产收款。

| 验收场景 | 预期 |
|---|---|
| 后端关闭/超时 | 显示连接错误，本地壁纸和 Dock 不受影响 |
| 匿名浏览/搜索/分页 | 正常展示，过滤结果总数准确 |
| 错误密码/重复注册 | 返回明确失败，不产生有效会话 |
| 免费领取/测试购买 | 服务器订单 PAID，权益清单出现且不重复 |
| 未购用户伪造商品 ID 申请下载 | 403，无原文件 URL |
| 用户 B 查询/付款用户 A 的订单 | 404 或 403，不泄漏 A 数据 |
| Token 过期/注销/刷新重放 | 会话规则正确，客户端重新登录 |
| 下载中断/取消/大小不符/hash 不符 | 不创建有效库条目，允许重试 |
| 重复下载/重复同步 | 同账号同商品同文件哈希只入库一次 |
| 下载时换账号/退出 | 旧任务取消，新账号不会继承购买权限 |
| 两台设备同账号 | 已购一致，本机下载状态独立 |
| 商品更新/停售 | 只保存当前文件；已购处理符合已确认策略 |
| 非管理员上传/上架 | 服务端拒绝 |
| 余额不足/重复付款 | 不足时409且不改变购买状态；重复成功请求不再次扣币 |

## 8. 给后端开发对话的开工提示

请读取本仓库 frontend/docs/BACKEND-HANDOFF.md 和 openapi.yaml，再实现独立后端。客户端代码在 frontend/，不要替换 Qt 界面或改变已冻结接口。先给出数据模型/迁移与分阶段计划，再实现商品、鉴权、订单权益、下载授权及管理接口；第一期使用站内壁纸币付款，不接外部收费渠道。演示服务器只供响应形状和测试参考，不可复制其密码哈希、安全或内存存储方案作为生产实现。所有关键验收要有可运行测试，并提供本机启动方式和种子数据。后端源码放 repository/backend/。

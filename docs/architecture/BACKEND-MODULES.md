# 六个后端模块展开设计

2026-10-13 后续实施更新：商品列表、一次multipart创建/修改、上下架和管理员封面已完成；旧资产模型、独立上传和待开发描述仅作历史记录。当前实现与失败清理以 [catalog-development3](E:/zlowallpaper_sever/docs/catalog-development3.md) 为准。

2026-10-13 第二次调整：assets 表已并入 wallpapers，文件信息直接保存商品行；独立上传已停用，创建商品将一次提交资料、原文件及封面。商品写接口已实现。以 [两次修改清单](E:/zlowallpaper_sever/docs/catalog-refactor-changes.md) 为当前设计。

2026-10-13 更新：上传代码已并入 catalog，商品只关联当前原文件和封面，取消商品资源历史表及 resourceVersion。商品返回当前原文件 sha256，客户端按文件哈希识别下载。完整调整见 [商品文件管理说明](E:/zlowallpaper_sever/docs/catalog-resource-development.md)。

交互图：`E:\Codex\files\zlowallpaper-architecture\backend-modules.html`。

六个模块都在同一个 Spring Boot 应用里，公共 API 入口负责路由、参数校验和统一响应，不计为业务模块。已实现范围见根 README。旧导出图展示最初七模块的设计，源码和下表为当前组织。

## 模块、功能与数据

| 模块 | 主要功能 | 维护的业务表 |
| --- | --- | --- |
| auth | 注册、密码验证、登录、刷新、登出、当前用户、管理员身份校验 | users、sessions |
| catalog | 分类、商品分页搜索、详情、草稿编辑、上下架、文件上传与当前文件 | categories、wallpapers |
| order | 下单、服务端价格、标题和金额快照、当前用户订单、重复有效订单处理 | orders |
| payment | 免费结算、开发环境测试付款、付款状态转换、事务内授予权益 | 使用 orders，协调 entitlements；没有独立支付表 |
| entitlement | 授予权益、已购清单、判断有效权益、撤销及重新购买后恢复 | entitlements |
| download | 查询权益、定位当前原文件与文件、签发限时下载授权 | 读取已有数据，不单独新增下载业务表 |

原导出的图和 JSON 保留最初设计快照，其中 asset/resources 分拆已过时。当前按本页更新及商品文件管理说明阅读；数据库当前六张表，已实现范围见根 README。

## 每个模块具体负责什么

**auth：你是谁，能执行什么操作？** 管理员与普通用户共用 users，注册只能创建 USER。密码保存安全哈希。服务端维护令牌轮换与撤销，输出可信用户ID和角色。订单、权益和下载不能接受客户端任意指定用户ID。公开浏览可匿名，私有操作需登录，商品管理和上传额外要求 ADMIN。会话已使用 MySQL sessions 表。

**catalog：商城卖什么，当前可以卖吗？** 管理商品资料、分类、售价、封面引用和当前原文件关联。公开列表只展示 PUBLISHED。管理端创建 DRAFT，编辑与发布分开执行；在 catalog 中校验用途、归属和 READY 状态。更换原文件更新当前关联，不保存历史版本。WITHDRAWN 阻止新增购买，保留历史及已购下载权；当前没有 DELETE 商品接口。

**catalog 内部文件管理：文件在哪里，是什么，能否使用？** 接收 COVER / ORIGINAL 上传，服务器检查真实格式、大小、哈希、宽高及帧率，将实际文件写入本地存储或对象存储，元数据写商品original_*和cover_*列。原文件最大10 GiB，封面最大4 MiB。维护 PROCESSING / READY / FAILED，向 catalog 和 download 提供资产校验、定位及存储访问能力。数据库不保存文件二进制或临时签名URL。

**order：谁以什么价格买了什么？** 已实现POST /orders，只接收wallpaperId，无需请求键，金额和标题保存快照。收费订单先PENDING，免费订单创建时直接结算并授予权益。当前权益、待付款订单、行锁和唯一约束防重复购买。GET /me/orders已实现，只返回本账号全部状态订单。逐文件讲解见[创建订单](../order-development.md)及[订单查询](../order-query-development.md)。

**payment：订单是否已经完成结算？** 正常壁纸币付款已在OrderServiceImpl.payOrder集中实现，接口POST /orders/{id}/pay。先锁用户再锁订单，同一事务扣余额、标记PAID/COIN并授予权益；重复付款不重复扣币。无测试开关；外部收费渠道和退款接口未开发，详见[壁纸币付款说明](../order-payment-development.md)。

**entitlement：用户现在拥有哪些壁纸？** 同一用户与商品只保留一条当前权益，记录来源订单，存在记录即拥有权益。已购列表包括停售但仍可下载的商品；商品展示资料仍由 catalog 提供。为订单防重和下载查权提供能力。未来退款将订单改为REFUNDED并删除其权益，重新购买产生新权益记录，旧订单历史保留，不远程删除本机已下载文件。

**download：用户能否取得当前原文件？** 已实现：读取可信用户ID，一次SQL关联本账号权益及商品当前原文件，无权益403。OssStorage生成固定当前对象的5分钟GET签名URL，返回扩展名、大小和SHA；客户端直接下载OSS字节，不转发账号Bearer。已购列表与已购封面归catalog，公开商品权限不放宽。详见[已购与下载说明](../download-development.md)。

## 已约定接口的归属

路径均相对于 `/api/v1`。

| 模块 | 接口 |
| --- | --- |
| auth | POST /auth/register、/auth/login、/auth/refresh、/auth/logout；GET /me |
| catalog | GET /categories、/wallpapers、/wallpapers/{id}；GET/POST /admin/wallpapers；PATCH /admin/wallpapers/{id}；PUT /admin/wallpapers/{id}/publication |
| catalog（已购/内部上传） | GET /me/wallpapers及/me/wallpapers/{id}/cover；上传无独立入口，由商品保存调用 |
| order | POST /orders；GET /me/orders |
| payment（当前由order实现） | POST /orders/{id}/pay；免费结算是下单流程内部能力 |
| entitlement | 无独立Controller；授予、查权及按来源订单删除是内部能力 |
| download | POST /wallpapers/{id}/download-grants |

已购封面是本次配套资源入口，防止公开封面暴露停售商品。公共health和统一响应等设施不属于独立业务模块。

## 协作与实现顺序

模块内部按需要组织 Controller、Service、Mapper、Entity、DTO。跨模块调用服务能力，不调用对方 Controller。图中箭头代表职责协作，不要求 OrderService 与 PaymentService 互相注入；免费下单与结算要细化事务编排，避免循环依赖。

付款状态更新与权益授予同事务；上传和耗时媒体处理不放进长数据库事务。资产失败/孤立文件清理需要明确策略。auth 通过公共入口设施覆盖全部私有接口，图中的下载请求只是入口路由的一个例子。

上述核心后端流程现已实现；后续按用户安排集中适配工作台/桌面端，再进行完整用户界面联调。退款、充值和外部支付等扩展仍需另行确定范围。

仍待定：sessions 与 idempotency_records 是否落SQL、Redis用途、文件存储、下载票据、异步资产状态查询。当前上传返回 PROCESSING 后没有查询结果的接口，必须共同评审；不能假定已有轮询能力。

本次只补充架构图和说明，继续由用户亲自逐模块开发，未改业务代码或数据库。

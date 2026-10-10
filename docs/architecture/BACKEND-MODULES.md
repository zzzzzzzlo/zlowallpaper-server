# 七个后端模块展开设计

交互图：`E:\Codex\files\zlowallpaper-architecture\backend-modules.html`。

这是一份待实现的设计。七个模块都在同一个 Spring Boot 应用里，公共 API 入口负责路由、参数校验和统一响应，不计为第八个业务模块。图只展示主要职责协作，没有画出全部入口路由和数据读取关系。

## 模块、功能与数据

| 模块 | 主要功能 | 维护的业务表 |
| --- | --- | --- |
| auth | 注册、密码验证、登录、刷新、登出、当前用户、管理员身份校验 | users；会话存储待定 |
| catalog | 分类、商品分页搜索、详情、草稿编辑、上下架、资源版本 | categories、wallpapers、wallpaper_resources |
| asset | 封面/原文件上传、格式与大小校验、SHA-256、媒体信息、文件位置、处理状态 | assets |
| order | 下单、服务端价格、标题和金额快照、当前用户订单、重复有效订单处理 | orders |
| payment | 免费结算、开发环境测试付款、付款状态转换、事务内授予权益 | 使用 orders，协调 entitlements；没有独立支付表 |
| entitlement | 授予权益、已购清单、判断有效权益、撤销及重新购买后恢复 | entitlements |
| download | 查询权益、定位当前资源版本与文件、签发限时下载授权 | 读取已有数据，不单独新增下载业务表 |

图中 catalog 标签里的 resources 指 wallpaper_resources，不是另一张表。现有七张表已创建，业务代码待实现。

## 每个模块具体负责什么

**auth：你是谁，能执行什么操作？** 管理员与普通用户共用 users，注册只能创建 USER。密码保存安全哈希。服务端维护令牌轮换与撤销，输出可信用户ID和角色。订单、权益和下载不能接受客户端任意指定用户ID。公开浏览可匿名，私有操作需登录，商品管理和上传额外要求 ADMIN。SQL 或 Redis 会话存储方案待定。

**catalog：商城卖什么，当前可以卖吗？** 管理商品资料、分类、售价、封面引用和原文件版本。公开列表只展示 PUBLISHED。管理端创建 DRAFT，编辑与发布分开执行；调用 asset 校验用途、归属和 READY 状态。更换原文件新增资源版本，不覆盖旧版本。WITHDRAWN 阻止新增购买，保留历史及已购下载权；当前没有 DELETE 商品接口。

**asset：文件在哪里，是什么，能否使用？** 接收 COVER / ORIGINAL 上传，服务器检查真实格式、大小、哈希、宽高及帧率，将实际文件写入本地存储或对象存储，元数据写 assets。原文件最大10 GiB，封面最大4 MiB。维护 PROCESSING / READY / FAILED，向 catalog 和 download 提供资产校验、定位及存储访问能力。数据库不保存文件二进制或临时签名URL。

**order：谁以什么价格买了什么？** 下单只接收 wallpaperId，价格由 catalog 提供；金额和标题保存快照，不跟着商品调价。收费订单先 PENDING，免费订单创建时直接结算。当前用户只能读取自己的订单。已有有效购买或未结束订单返回既有结果，数据库约束防并发重复；请求幂等方案仍需实现。

**payment：订单是否已经完成结算？** 核验当前用户、订单和开发环境开关。在一个数据库事务里标记订单 PAID，并调用 entitlement 授予权益，任何一步失败全部回滚。重复付款不能重复授权。首期只有 FREE / TEST，生产禁用 test-pay；真实支付、回调和退款接入以后再设计，不新增未约定的接口。

**entitlement：用户现在拥有哪些壁纸？** 同一用户与商品只保留一条当前权益，记录来源订单及 ACTIVE / REVOKED。已购列表包括停售但仍可下载的商品；商品展示资料仍由 catalog 提供。为订单防重和下载查权提供能力。未来退款可撤销，重新购买可恢复并更新来源订单，旧订单历史保留，不远程删除本机已下载文件。

**download：用户能否取得这个版本的原文件？** 从 auth 身份上下文取得用户ID，向 entitlement 查权，无权益返回403；向 catalog 定位当前资源版本，再向 asset 获取文件位置、扩展名、实际大小和SHA-256。签发绑定版本与期限的 DownloadGrant。客户端再访问文件服务获取原始字节，不向文件地址转发账号 Authorization。下载票据实现方案仍待定。

## 已约定接口的归属

路径均相对于 `/api/v1`。

| 模块 | 接口 |
| --- | --- |
| auth | POST /auth/register、/auth/login、/auth/refresh、/auth/logout；GET /me |
| catalog | GET /categories、/wallpapers、/wallpapers/{id}；GET/POST /admin/wallpapers；PATCH /admin/wallpapers/{id}；PUT /admin/wallpapers/{id}/publication |
| asset | POST /admin/uploads |
| order | POST /orders；GET /me/orders |
| payment | POST /orders/{id}/test-pay；免费结算是下单流程内部能力 |
| entitlement | GET /me/wallpapers；授予及撤销是内部能力 |
| download | POST /wallpapers/{id}/download-grants |

没有增加新接口。公共 health 和统一响应等设施不属于新增业务模块。

## 协作与实现顺序

模块内部按需要组织 Controller、Service、Mapper、Entity、DTO。跨模块调用服务能力，不调用对方 Controller。图中箭头代表职责协作，不要求 OrderService 与 PaymentService 互相注入；免费下单与结算要细化事务编排，避免循环依赖。

付款状态更新与权益授予同事务；上传和耗时媒体处理不放进长数据库事务。资产失败/孤立文件清理需要明确策略。auth 通过公共入口设施覆盖全部私有接口，图中的下载请求只是入口路由的一个例子。

建议先工程基础与 health，再 catalog 公开浏览、auth、asset 与管理员 catalog，随后联合完成 order/payment/entitlement，最后 download 并走通桌面真实联调。

仍待定：sessions 与 idempotency_records 是否落SQL、Redis用途、文件存储、下载票据、异步资产状态查询。当前上传返回 PROCESSING 后没有查询结果的接口，必须共同评审；不能假定已有轮询能力。

本次只补充架构图和说明，继续由用户亲自逐模块开发，未改业务代码或数据库。

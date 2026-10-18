# 契约核对与待评审需求

2026-10-13 商品后续实现：管理员列表、一次multipart创建/修改、上下架与授权封面已完成，工作台新协议已启用；下文早期资产上传模型仅作历史记录。当前契约见openapi.yaml，代码导读见 [catalog-development3](../catalog-development3.md)。Qt封面缓存与交易下载接口按用户安排暂留。

2026-10-13 第二次调整：assets 表已并入 wallpapers，文件信息直接保存商品行；独立上传已停用，创建商品将一次提交资料、原文件及封面。商品写接口尚未实现。以 [两次修改清单](E:/zlowallpaper_sever/docs/catalog-refactor-changes.md) 为当前设计。

2026-10-13 更新：上传代码已并入 catalog，商品只关联当前原文件和封面，取消商品资源历史表及 resourceVersion。商品返回当前原文件 sha256，客户端按文件哈希识别下载。完整调整见 [商品文件管理说明](E:/zlowallpaper_sever/docs/catalog-resource-development.md)。

后端实施状态补充（2026-10-12）：上传首期采用同步签名、元数据和首帧检查，成功返回READY；未实现后台转码或PROCESSING轮询，不修改Asset响应字段。异步状态查询缺口仍保留，供以后引入后台处理时评审。实现与测试见 [asset-development.md](E:/zlowallpaper_sever/docs/asset-development.md)。

后端实施状态补充（2026-10-11）：分类管理四接口已实现，详情与验证见 [catalog-development2.md](E:/zlowallpaper_sever/docs/catalog-development2.md)。本文件原“后端待实现”描述保留为前端交接记录；商品写接口的分类选择校验仍待下一阶段实现，真实工作台联调尚未在本次验收。

更新：2026-10-10。分类管理由用户批准，依据 admin-category-handoff.md 进入 OpenAPI 增补及前端实现，状态为**已设计，新增后端接口待实现**。下面的其他未批准需求仍未进入契约或客户端请求。

## 首期按冻结契约处理

| 问题                         | 依据                                                                          | 当前实现                                                                                                            |
| ---------------------------- | ----------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| 上传返回名不一致             | 交接文档 §5 写“assetId”；OpenAPI `Asset` required 是 `id`                     | 消费 `data.id`，分别传入 `originalAssetId` / `coverAssetId`。不读取或新增 `data.assetId`                            |
| 无角色字段/管理员专用登录    | `User` 只有 id / username / balanceCents                                       | 复用 `/auth/login`，用已实现的 GET `/admin/categories` 验权；403 拒绝进入，商品目录独立加载，不发送 isAdmin，不猜测 token 内容 |
| 无管理详情 GET               | `/admin/wallpapers/{id}` 只有 PATCH                                           | 编辑列表返回的完整 `AdminProduct`，不调用公开详情读取草稿                                                           |
| 管理列表不支持搜索或状态筛选 | GET admin 参数只有 page / pageSize                                            | 不发送 keyword、categoryId、status，不伪造全库筛选或统计                                                            |
| 分类管理（本次已批准）       | 新增 GET/POST /admin/categories、PATCH/DELETE /admin/categories/{id}          | 完整管理列表、增改排序启停删除；后端待实现；公开 GET /categories 保持启用分类 id/name，未实现时明确报错，不回退模拟 |
| 服务端生成媒体字段与文件哈希     | ProductMutation 不含 type / width / height / fps / sha256 / currency | 不把这些只读字段发送到创建或修改请求。价格按整数分发送                                                              |

## P0：异步资产状态（需共同评审后才能补齐）

Asset 允许 PROCESSING / READY / FAILED，但契约没有“按资产 ID 查询当前状态”的接口或推送机制。交接文档要求部分媒体检查后台执行，因此如果 POST 上传总是返回 PROCESSING，首期管理端无法可靠获知就绪状态，也不能完整创建商品。

需求：确定一种方式，在不重复上传、不猜测状态的前提下，读取该管理员已上传资产的最新状态和失败原因；说明鉴权、轮询间隔/终止条件、失败与不存在的状态码，以及结果是否仍复用 Asset。**路径、失败字段、响应类型均待契约评审，不在这里擅自定稿。**

目前：明确显示 PROCESSING，阻止保存，没有调用任何未定义接口。若首期后端直接返回 READY，现有流程可完整联调。必须确认这个取舍，不能仅让前端等待几秒就改成 READY。

## 后续可选需求（未实现）

1. 未引用资产清理：编辑取消、替换素材、请求结果未确认都可能留下孤立资产。优先由后端定期清理；如需前端删除，先明确权限、引用保护、保留期和接口。前端不发送资产或商品 DELETE；本次批准的分类 DELETE 不能连带清理任何其他数据。
2. 创建/上传请求幂等：当前管理员写操作没有 Idempotency-Key 契约。超时不代表服务端没成功，前端不会自动重放；再次操作前应刷新目录确认。若要求安全自动重试，需先定义幂等键和冲突行为。
3. 并发编辑保护：当前无版本条件、ETag/If-Match。多管理员可能覆盖资料，需先确认冲突策略，不擅自提交 version 字段。
4. 管理员全库搜索、状态筛选、商品详情重读：规模扩大后再增加参数/接口并写验收用例。
5. 上传取消、断点续传、处理任务进度：原文件可达 10 GiB；当前是单次 multipart、120 秒超时和忙碌状态，无真实字节进度/分片接口。大文件生产上传方案需评审；演示的 64 MiB 限制不是后端限制。
6. 管理账号置备仍由后端运维完成，不在浏览器内置真实管理员，不开放注册即管理员。分类可由新工作台维护，但须先实现本次四个分类接口；种子数据只是初始化方式。

## 浏览器联调前提（不改变 API 业务字段）

- 新管理端是跨域浏览器，而桌面 Qt 客户端不需要 CORS。后端需允许具体管理端来源，包含 OPTIONS、GET、POST、PATCH、PUT、DELETE，以及 Authorization、Content-Type 请求头；OPTIONS 不要求 Bearer。无 cookie 凭证，不需允许 credentials。
- API 成功和错误响应都必须带正确 CORS 头，否则浏览器只能报告网络/CORS 错误，无法显示真实 401/403/503。
- API、上传不能依赖 301/302 重定向；返回封面应为可直接访问、最大 4 MiB 的最终 URL。图片标签无法在浏览器侧全面控制服务端重定向，最终地址/大小由资源服务保证，不应把 HTML 登录页当作封面。
- 文件格式、实际大小、SHA-256、媒体元数据、管理员权限和资产归属全部由服务器验证；浏览器预检查和模拟校验不是安全边界。

## 基线文件完整性

2026-10-09 原版实现前读取的历史 SHA-256（不是本次增补后的校验值）：

```text
openapi.yaml
02623367A734EECECCD2FA5FF50A3C7222A35804011523C7740BF6725B7403C4

BACKEND-HANDOFF.md
9C61AC8CA58FFD2B394D6D4C574C5A77DDC8328450A752D8F87520620CE46087
```

2026-10-09 首期未修改基线。2026-10-10 按批准交接增补 AdminCategory、分类请求体及四个管理接口；原公开 Category 及其余接口结构保持兼容。源码文档和后端 frintend-docks 副本同步更新。其他可选需求仍需评审，未擅自加入。

## 分类管理后端实施清单（已设计，待实现）

- catalog 模块 CategoryMapper：完整列表按 sortOrder/id 排序，引用数包含所有商品状态；无查询参数、无分页、无新增表或列。
- name trim 后 1–64 个 Unicode 字符，大小写不敏感判重（含停用）；sortOrder int32；enabled 布尔；创建默认 0/true，PATCH 至少一个允许字段，禁止客户端写 id/count。
- 删除仅分类、无请求体、200 data:{}；保留 ON DELETE RESTRICT，并发引用返回 409 CATEGORY_IN_USE，禁止级联或自动停用。不存在/重复删除 404 CATEGORY_NOT_FOUND。
- 停用不改变商品发布状态；新建/换分类仅启用分类可用，原商品保留原停用分类合法；公开 GET /categories 仍只返回启用的 id/name。
- 错误：400 VALIDATION_ERROR、401 SESSION_EXPIRED、403 FORBIDDEN、404 CATEGORY_NOT_FOUND、409 CATEGORY_NAME_EXISTS/CATEGORY_IN_USE；故障保留实际 HTTP/data:null/requestId。真实接口未实现时不返回假成功。

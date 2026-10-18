# 工作台联调与验收

更新：2026-10-15。商品管理、分类管理与认证后端已实现；交易和下载授权不在本工作台本次联调范围。详细后端代码见 [catalog-development3](E:/zlowallpaper_sever/docs/catalog-development3.md)。

## 接口

所有路径位于/api/v1。所有admin请求带Bearer，登录/刷新/登出沿用auth契约。成功JSON的code=OK，失败保留实际HTTP状态、code、requestId和data:null；封面成功返回图片字节。

| 操作                 | 方法/路径                              | 请求                                                                                  | 成功                |
| -------------------- | -------------------------------------- | ------------------------------------------------------------------------------------- | ------------------- |
| 管理员验权、分类列表 | GET /admin/categories                  | 无业务参数                                                                            | 200 AdminCategory[] |
| 新增分类             | POST /admin/categories                 | JSON name，可选sortOrder/enabled                                                      | 201 AdminCategory   |
| 编辑分类             | PATCH /admin/categories/{id}           | JSON变化字段                                                                          | 200 AdminCategory   |
| 删除分类             | DELETE /admin/categories/{id}          | 无body                                                                                | 200 data:{}         |
| 启用分类选项         | GET /categories                        | 无参数                                                                                | 200 id/name数组     |
| 商品目录             | GET /admin/wallpapers                  | page/pageSize                                                                         | 200所有状态分页     |
| 创建草稿             | POST /admin/wallpapers                 | multipart title/categoryId/priceCents/originalFile/coverFile，可选description/creator | 201 AdminProduct    |
| 编辑商品             | PATCH /admin/wallpapers/{id}           | multipart变化资料与可选文件                                                           | 200 AdminProduct    |
| 上下架               | PUT /admin/wallpapers/{id}/publication | JSON status=PUBLISHED或WITHDRAWN                                                      | 200 AdminProduct    |
| 管理封面             | GET /admin/wallpapers/{id}/cover       | Bearer                                                                                | 200图片，最大4MiB   |

没有独立上传调用；后端旧/admin/uploads已删除，管理员访问返回404。没有资产ID、resourceVersion或角色猜测。编辑直接使用管理列表完整商品，无管理详情GET。

## 联调前提与流程

启动本机后端，允许具体页面来源CORS：OPTIONS、GET、POST、PATCH、PUT、DELETE，Authorization、Content-Type。OPTIONS不要求Bearer，不携带cookie凭证。后端默认允许http://127.0.0.1:4173和http://localhost:4173，改静态页面端口需相应配置。

刷新工作台页面，选择真实模式，API地址默认http://127.0.0.1:8080/api/v1。后端public-base-url应匹配客户端所用API根地址，管理员封面不会把凭证发送到其他来源。生产使用HTTPS。

登录→GET管理分类验权→目录→填写资料/选择文件→点击创建草稿（一次POST）→确认上架→编辑（一次PATCH）→确认下架。登录只用真实账号，模拟admin/Admin12345不作为真实后端内置管理员。

文件选择仅本地预览，取消释放选择与Blob URL，不发送请求。创建必须有两份文件；修改省略文件保留，省略资料保留，空description/creator清空。新增或迁移分类只使用启用分类，原商品可继续保留停用分类。删除任何状态商品引用的分类返回409。

新商品返回DRAFT；保存资料保持现有发布状态；上下架是单独逻辑操作。返回文件名、真实媒体参数、原文件SHA和字符串大小，不返回私有存储位置。没有文件的历史草稿媒体字段可null，不能上架。

## 失败处理

401清除页面会话，403保留会话但拒绝操作。服务端返回原错误状态；真实模式不回退模拟。普通请求超时30秒，商品multipart保存至少30分钟。取消连接会中断客户端等待，但不代表服务器已回滚；未确认结果先刷新目录，不自动重放写请求。

服务端处理实际文件签名、媒体解码与完整SHA；浏览器只做基本扩展名、非空、4MiB/10GiB检查。OSS失败或写库失败回收新文件；替换成功后清理旧文件，有数据库引用保护和本机持久任务重试。大文件进度/断点续传、自动幂等重试未实现。

## 已验证

- Node33项：金额、URL、File/表单、分类、分页、创建编辑发布、会话、HTTP FormData与授权图片、错误响应、超时取消、静态服务。
- 后端165项默认测试通过，浏览器用例默认跳过并另行验证。
- 真实MySQL3项：43列Mapper、部分更新、发布可见性与CHECK，测试数据回滚。
- Playwright→真实Tomcat/H2：登录、一次创建、授权封面、上架、multipart编辑、取消无写入、下架；云操作为替身。

没有往真实OSS Bucket上传测试文件。生产网络、实际Bucket权限、真实文件上传需在管理员业务联调时以实际返回结果检查。浏览器测试后的辅助进程关闭，没有修改真实账号。

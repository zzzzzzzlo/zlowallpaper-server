# 接口契约

[openapi.yaml](openapi.yaml) 可直接导入 Apifox。默认地址为 `http://127.0.0.1:8080/api/v1`，不要重复拼接 `/api/v1`。

- JSON 响应包含 `code/message/data/requestId`，同时检查 HTTP 状态和业务码。
- ID、原文件字节数、余额按契约使用字符串；价格是整数最小单位，100单位=1枚壁纸币，币种为 `ZLC`。
- 私有请求携带 `Authorization: Bearer <accessToken>`，管理接口核对当前角色。购买者 ID 来自登录上下文。
- 登录/刷新响应字段不变；同次登录复用refreshToken和session ID，刷新不延长固定截止时间。登出只撤销当前登录，其他登录不受影响；重复登出成功。
- 封面返回图片字节；公开、管理员和已购入口执行各自的可见性查询。
- 商品保存采用 multipart；创建必填两份文件，修改省略文件表示保留，没有独立上传接口。
- 源封面最大10 MiB，生成长边最多1920、JPEG q=2 的图片，存储/读取仍最大4 MiB；原文件最大10 GiB。
- 商城和已购返回 `{items,page,pageSize,total}`，默认每页12条、最多50条；订单列表仍是最多1000条的数组，超限明确报错。

管理员商品分页 `GET /admin/wallpapers` 额外支持可选的 `publicationStatus` 与 `categoryId`，可单独或组合使用。状态值为PUBLISHED/DRAFT/WITHDRAWN，分类可选择停用项；省略或空字符串不限，total按筛选结果计算。示例：`/admin/wallpapers?page=1&pageSize=12&publicationStatus=PUBLISHED&categoryId=1`。

创建收费订单只得到 PENDING，不代表已经购买。付款事务成功后才扣余额、更新状态和新增权益。关闭客户端确认窗口不会删除订单。

已购读取当前商品，订单保留下单标题/金额快照。停售商品不能经公开详情和封面读取，已购账号可通过自己的入口访问。

充值、修改用户名、退款和第三方支付没有后端路由。前端预留按钮不代表接口已经实现。

执行顺序见 [调用链](../api-call-chains.md)。OpenAPI 尚未单列公共封面辅助路由；`GET /catalog/covers/{id}` 的行为以调用链与 Controller 为准。

修改密码已实现 `PUT /me/password`：提交currentPassword与newPassword，旧密码校验通过后更新PBKDF2哈希并撤销该账号所有现有会话；成功需重新登录。无需数据库迁移。

订单取消使用POST /orders/{id}/cancel。待付款默认15分钟到期，复用不续期；TIMEOUT和USER都归为CANCELED，以cancelReason区分。付款、取消、超时关闭互斥，关闭不扣币、不授予权益且保留记录。

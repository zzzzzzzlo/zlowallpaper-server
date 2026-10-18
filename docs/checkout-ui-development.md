# 订单确认与客户端下载体验

更新：2026-10-16。后端源码在 E:\zlowallpaper_sever，Qt 源码在 D:\Codex\software\zlowallpaper\frontend，运行客户端在 E:\zlowallpaper。

## 1. 订单确认窗口

点击购买后，仍然先调用 POST /api/v1/orders。收费订单返回 PENDING，弹出“订单已生成”窗口，展示壁纸标题、可用的封面及分类/格式/分辨率、订单编号、用户 ID、创建时间、当前余额和本单应扣款。标题和扣款金额取订单快照；封面及媒体资料复用客户端已加载的商品资料，未加载到时显示壁纸编号，不为了付款额外调用公开详情。

“立即付款”调用 POST /orders/{id}/pay，等待期间禁用付款按钮，避免重复点击。成功后刷新余额、权益和订单。“稍后付款”或关闭窗口只关闭界面，订单仍保留，可以从“我的订单”继续付款，复用同一个确认窗口。免费商品创建时直接 PAID，沿用直接领取逻辑。

“取消订单（待开放）”位于付款按钮旁，调用 StoreService.cancelOrder(orderId)。目前只显示尚未开放，不发送 HTTP、不改本地或服务端订单状态。后续实现取消接口时在此处接入；本次没有声明一个实际上不存在的后端取消路径。

## 2. Order 返回字段

POST /orders、GET /me/orders 和 POST /orders/{id}/pay 共用 OrderVO，原有七字段保留，增加 userId、balanceCents，共九字段：

```json
{
  "id": "101",
  "userId": "11",
  "wallpaperId": "8",
  "title": "Fate·举剑",
  "amountCents": 1200,
  "balanceCents": "10000",
  "currency": "ZLC",
  "status": "PENDING",
  "createdAt": "2026-10-16T10:00:00Z"
}
```

userId 来自可信订单归属；balanceCents 是本次请求读取的当前账户余额，不是下单时的余额快照，不增加订单表字段。ID 和余额都是字符串；amountCents 为整数，100 个最小单位等于 1 枚壁纸币。客户端余额显示保留两位小数，大数余额采用字符串处理。

创建和付款保留现有用户行锁。OrderServiceImpl 复用已注入的 UserMapper，通过新增 selectBalanceById(userId) 查询余额，SQL 在 mapper/auth/UserMapper.xml。付款响应返回扣款后的余额，重复付款返回当前余额。订单列表只查询一次余额，再转换整批订单，不对每笔订单重复查询用户表。列表普通读取不持写锁，各字段代表读取时数据库可见的值。

主要调用关系：

1. OrderController → OrderServiceImpl.createOrder：锁用户、查询余额、按现有权益或待付款订单防重，必要时新建订单。
2. OrderServiceImpl → UserMapper.selectBalanceById：只查询用户余额，不读取密码等资料。
3. toOrderVO(order, balanceCents)：拼装九字段；不在转换函数中查询数据库。
4. StoreService.createOrder → acceptOrderBalance → orderCreated → StorePage.showOrderConfirmation。
5. StoreService.payOrder → acceptOrderBalance → orderPaid：关闭匹配订单的确认窗口，刷新账户和购买状态。

窗口显示的余额可能在打开后被另一笔付款改变，因此最终能否扣款仍由后端事务内判断，前端展示不会代替扣余额条件或订单防重。

## 3. 下载完成立即出现在壁纸库

问题发生在界面刷新：DownloadManager 完成下载、校验和保存后已经调用 WallpaperLibrary.addPrepared 并发出 changed，商城也显示下载成功，但 MainWindow 没有监听此信号，所以直到点击应用才重建列表。

现在 MainWindow 构建时连接 WallpaperLibrary.changed → MainWindow.rebuildList。下载完成就刷新壁纸卡片，不自动应用桌面壁纸。临时文件、最终文件和 library.json 仍使用运行目录下的 loaded_wallpaper；未改动已有用户文件。

## 4. 顶部页面切换与提示

商城顶部增加互斥的“我的已购 / 壁纸商城”，左侧查询本账号权益列表，右侧查询公开上架商品；移除搜索栏旁原先的已购开关。切换时回到第一页并清空详情，已购仍支持停售商品。公开列表数量减少、导致当前页超出最后一页时，自动退到最后有效页并重新查询，避免显示“第 2 / 1 页”。

StorePage.showToast 使用 Qt QLabel、停留动画 QPauseAnimation 与 QPropertyAnimation。成功消息停留约 2.2 秒、错误约 3.2 秒，随后用 0.7 秒淡出并释放控件；提示不抢键盘焦点、不拦截鼠标，新消息替换旧消息。覆盖余额不足、商品/订单不存在、付款和下载结果等已有消息入口，开发阶段的横条仍保留完整请求编号，短提示只展示可读原因。此效果由客户端实现，不需要新增后端接口。

## 5. 验证

- 后端全量测试：248 项，0 失败、0 错误，1 项浏览器条件测试跳过；随后打包成功。最初 repackage 因运行中的 JAR 被占用失败，停止旧服务后重新打包、启动并验证。
- 真实本地 HTTP/MySQL：两个临时账号验证九字段创建/复用/列表/付款、重复付款不二次扣币、余额不足时订单和余额不变。测试账号、会话、订单及权益已清理；未扣现有用户余额、未创建或删除商品文件。
- Qt 测试包含确认窗口内容、关闭后保留订单、取消入口不改订单、付款成功、余额不足、顶部切换及提示淡出；新增完整主窗口测试验证入库信号立即增加壁纸卡片，同时不应用桌面。
- OpenAPI 的 Order 必填字段已同步；Apifox 重新导入并覆盖 Order 模型及对应接口后测试。关闭窗口和取消入口无后端写请求。

退款、充值、用户资料修改和取消订单的实际后端业务仍未在本次实现。

# 壁纸币余额与付款开发说明

更新：2026-10-15。本篇替代之前的测试付款说明。现在正常付款使用 **POST /api/v1/orders/{id}/pay**，实际扣减站内壁纸币；旧test-pay路由、testPaymentEnabled字段和测试开关已移除。没有接入人民币扣款、充值或退款接口，本次没有修改两端用户界面与客户端调用。

## 1. 数据单位及本次改表

统一约定：**100最小整数单位=1枚壁纸币，币种代码ZLC。**

| 字段 | 保存/传递方式 | 举例 |
| --- | --- | --- |
| users.balance_cents | 数据库BIGINT，Java Long，默认0且不得为负 | 10000表示100币 |
| UserVO.balanceCents | JSON十进制字符串，登录/刷新和GET /me返回 | "9010"表示90.10币 |
| priceCents | 保留字段名和整数类型，表示最小单位 | 990表示9.90币 |
| amountCents | 下单时金额快照，付款直接扣同样的整数值 | 990表示扣990单位 |
| currency | 当前商品与新订单ZLC；历史终结订单可保留CNY | ZLC |
| payment_method | 免费FREE，余额付款COIN，TEST仅兼容历史 | COIN |

余额用整数运算，避免浮点计算误差；返回字符串避免JavaScript大整数精度问题。用户资料不接受客户端传余额，注册默认0。余额变化通过GET /me重新查询，不依赖旧登录响应。

没有新增钱包表、请求键表或资金流水表。当前面试demo先用用户余额字段与既有订单记录完成付款，不承诺充值渠道、账户对账等完整钱包能力。

## 2. 调用链与函数

```text
POST /orders/{id}/pay + Bearer
  → AuthFilter：确认身份，将用户ID放入BaseContext
  → OrderController.payOrder：接收订单ID
  → OrderServiceImpl.payOrder【同一个@Transactional事务】
      校验ID → 锁用户 → 锁本用户订单 → 查权益 → 判断是否已付款
      → UserMapper.deductBalance：余额足够才扣币
      → OrderMapper.updatePaidStatus：更新PAID/COIN及时间
      → grantEntitlement：插入当前购买权益
      → toOrderVO：转换现有订单字段
  → 提交成功后回到Controller，HTTP 200 + ApiResponse
```

原先五个核心函数继续使用，正常付款改名为payOrder；只新增一个实际扣余额的Mapper方法，没有增加业务辅助类：

| 文件/函数 | 作用与调用关系 |
| --- | --- |
| OrderController.payOrder | Spring MVC接收路径ID，调用OrderService并返回统一响应 |
| OrderService接口 | 声明payOrder(String id)，由Controller注入使用 |
| OrderServiceImpl.payOrder | 一处展示完整付款、状态判断、行锁和事务边界 |
| UserMapper.deductBalance | Service调用，执行用户表余额UPDATE，SQL在mapper/auth/UserMapper.xml |
| OrderMapper.updatePaidStatus | Service调用，执行订单付款UPDATE，SQL在mapper/order/OrderMapper.xml |
| grantEntitlement | 本类私有方法，免费创建和余额付款复用，参加调用方事务 |
| toOrderVO | 本类私有方法，创建/复用/查询订单及付款复用九字段返回对象（含当前余额与用户ID） |

## 3. 用户实体与余额返回

`User.java` 新增 `Long balanceCents`，由MyBatis把balance_cents映射进来。`UserMapper.xml` 查询字段列表新增余额；注册INSERT依赖数据库默认0，AuthService构建用户对象时也明确设0。

`UserVO.java` 新增 `String balanceCents`。AuthServiceImpl.sessionVO在登录/刷新时传入余额，UserServiceImpl.getCurrentUser为GET /me传入最新余额。这两个位置都使用Long.toString，没有把密码、角色或内部状态一起返回。

登录或刷新响应的user，以及GET /me的data，都是：

```json
{
  "id": "11",
  "username": "userzlo",
  "balanceCents": "10000"
}
```

## 4. Service主流程逐段阅读

**第一步：取身份、检查路径ID。** 身份来自BaseContext。外部ID仍是字符串，CatalogIds检查格式并转为正数Long；订单找不到或属于别人，统一404 ORDER_NOT_FOUND。

**第二步：先锁用户，再锁订单。** 复用selectBuyerStatusForUpdate与selectOwnedOrderForUpdate。下单和付款共同以本用户行作为第一把锁。同一用户同时下单/付不同订单，也会按顺序读取最新状态；账号在认证之后已被停用时，在拿到锁后再次拒绝。代码旁标注“并发控制”“并发复查”。

**第三步：判断订单和权益。**

| 状态 | 处理 |
| --- | --- |
| PENDING且无该商品权益 | 扣币并完成付款 |
| PAID且已有该订单授予的权益 | 直接返回，不经过扣币SQL |
| CANCELED/REFUNDED | 409 ORDER_NOT_PAYABLE，重新购买应使用新订单 |
| PAID缺权益或权益来源不同 | 409 PURCHASE_STATE_CONFLICT，不自动补发 |
| PENDING却已有权益 | 409 PURCHASE_STATE_CONFLICT，不重复扣款 |

历史TEST/FREE订单重复付款不追扣壁纸币，不修改原付款方式与时间。普通免费商品创建时直接PAID/FREE并授予权益，余额不动。

**第四步：扣币、改订单、加权益。** 按订单amountCents付款，不读取商品现在的价格；商品改价或下架不改变已有待付款订单。生成一次UTC毫秒时间，三项写入复用此时间。

**第五步：返回OrderVO。** 返回id、userId、balanceCents、wallpaperId、title、amountCents、currency、status、createdAt九个字段（2026-10-16更新）。UserMapper.selectBalanceById读取当前余额，首次付款返回扣款后余额，重复付款不再扣币。付款首次成功和重复成功均HTTP 200。testPaymentEnabled已移除。余额不写入订单表，详见[订单确认说明](checkout-ui-development.md)。

## 5. 扣余额SQL为什么这样写

```sql
UPDATE users
SET balance_cents = balance_cents - #{amountCents}, updated_at = #{paidAt}
WHERE id = #{userId} AND balance_cents >= #{amountCents};
```

`id`限定当前用户；`balance_cents >= amountCents`表示余额足够才允许扣减。成功更新1行，余额不足更新0行，Service直接据此返回409 INSUFFICIENT_BALANCE；不再额外写一遍相同的Java余额比较。

上面的用户行锁从付款开始一直持有到事务提交/回滚。相同用户的两张订单同时扣币时，后一笔会在前一笔提交后按最新余额执行条件UPDATE，避免各自看旧余额都认为够用。数据库余额非负CHECK继续保护数据约束。

订单UPDATE只改变status=PAID、payment_method=COIN、paid_at、updated_at，不修改金额、标题、币种或created_at。权益INSERT保持用户/商品唯一，以及来源订单与用户/商品的复合外键。

## 6. 事务失败及并发示例

余额1500，两笔订单各990，同时付款：

```text
A：取得用户锁 → 扣990，余额510 → 更新订单A与权益 → 提交
B：等待后取得用户锁 → SQL发现510不足990 → 返回409，订单B仍PENDING
```

同一订单两个请求付款时，第二个等锁后看到PAID和对应权益，直接返回，不扣第二次余额。

若扣币和订单UPDATE已完成，但权益INSERT失败，异常向外抛到Spring事务代理，三项修改一起回滚。最终余额恢复，订单仍PENDING，无付款方式/时间，无新权益。没有noRollbackFor例外。

本次没有退款接口。未来COIN退款需要在同一事务里完成退币、订单REFUNDED和删除该来源权益，并在状态判断后只退一次；历史FREE/TEST没有实际扣币，不能按相同金额凭空退币。

## 7. 数据库迁移与历史处理

`007-user-coin-balance.sql` 已在本机开发库执行：新增余额及非负约束；当前商品和PENDING订单币种从CNY改ZLC，整数价格/金额不变；PAID/CANCELED/REFUNDED等历史订单的币种、时间和付款方式保留。没有自动给已有用户加钱。

脚本允许重复执行，已经存在的余额不会清零。新库按001、002、003、004、006、007初始化；已有库补007，不能重跑001。数据库仍是原六张表。

## 8. Apifox测试步骤

1. 使用最新 `docs/frintend-docks/openapi.yaml` 重新导入/覆盖接口定义，移除旧test-pay接口和旧Order字段。重启后端；普通付款没有local专属开关。
2. 登录，保存accessToken到bearerToken环境变量；GET /me查看balanceCents。
3. 默认余额0。开发时可手动执行 `database/dev-set-coin-balance.sql`：核对用户名后将测试余额设置为10000（100币）。该脚本不自动运行，也不是充值接口。
4. 创建一笔收费商品订单：POST /orders，JSON `{"wallpaperId":"商品ID"}`，记录PENDING订单ID。商品需已上架，currency为ZLC。
5. POST `/orders/订单ID/pay`，Auth带Bearer，Body选none，无需请求键。
6. 预期200、data.status为PAID；GET /me再次查看余额。原余额10000、订单990时，余额应为9010。
7. 再发同一付款请求仍200，余额保持9010，数据库只有一条权益。
8. 测余额不足时为独立测试账号准备不足的余额，预期409 INSUFFICIENT_BALANCE，订单仍PENDING且余额不变。

常见错误：401账号失效，404订单不存在/他人订单，409余额不足/订单终结/权益冲突，503数据库暂不可用；意外异常按统一处理返回500。

## 9. 前端本次范围

只更新后端与桌面端目录中的OpenAPI副本及交接说明，供Apifox导入。**两端UI、ApiClient调用、余额展示和币种格式化均留待后续统一处理。** 旧客户端仍使用test-pay、testPaymentEnabled或只接受CNY，当前不能当作已适配虚拟币支付。

订单列表、已购列表和下载授权接口仍待后续开发，本次不实现充值和退款接口。

## 10. 验证

完整后端 `clean verify` 215项，214通过、1项浏览器条件测试跳过，无失败/错误；其中付款22项，覆盖余额不足、恰好足额、重复扣款、跨账号、跨订单并发防透支、失败回滚和历史付款记录。

真实MySQL完成23项唯一用例：订单及余额10项、商品管理3项、认证3项、分类管理5项、公开查询2项，全部通过。前一轮因各模块测试开关不同而跳过的10项，已经单独开启后补跑通过。测试不设置实际用户测试余额，不调用真实OSS；只创建并清理隔离夹具。

日志：`.local-setup/coin-payment-final-verify.log`、`coin-payment-mysql.log`、`coin-user-catalog-mysql.log`。余额/方法参数注释通过语法树检查；OpenAPI需同时通过解析及本地引用核对。

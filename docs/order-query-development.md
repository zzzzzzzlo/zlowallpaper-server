# 查询我的订单开发说明

## 1. 接口与行为

`GET /api/v1/me/orders`，需要Bearer登录，不需要请求体、用户ID或查询参数。

- 查询本账号全部状态订单：PENDING、PAID、CANCELED、REFUNDED。
- 按创建时间倒序；时间相同再按ID倒序。
- 标题、金额和币种来自订单快照，不读取商品当前资料。商品改名、调价、下架不影响历史订单。
- 无订单时HTTP 200、`data: []`。不要求管理员身份，普通登录用户也可以查询自己的订单。
- v1暂不分页，最多完整返回1000条；超过返回HTTP 409、`ORDER_LIST_LIMIT_EXCEEDED`、`data: null`。不会悄悄截断成前1000条。
- 不新增数据库表或字段，复用OrderVO。2026-10-16为订单确认窗口给三个订单接口统一增加userId和当前余额balanceCents，原字段和路径保留。

## 2. 从请求到返回

1. `AuthFilter`验证Bearer令牌、会话及账号状态，把可信用户ID放入`BaseContext`。缺少/无效令牌或禁用账号返回401。
2. `OrderController.getMyOrders(request)`调用Service，最后使用`ApiResponse.success`包装列表与requestId。
3. `OrderServiceImpl.getMyOrders()`读取当前用户ID。身份不能来自前端参数；即使附加`?userId=其他人`也不会改变查询账号。
4. `OrderMapper.selectOrdersByUserId(userId, limit)`执行XML里的SELECT。本次传1001，多读一条用于识别超过1000条。
5. Service检查数量；超限直接抛业务异常，由`GlobalExceptionHandler`返回409。未超限则用UserMapper.selectBalanceById查询一次当前余额，用普通for循环逐条调用`toOrderVO(order, balanceCents)`。
6. Controller返回HTTP 200的订单数组；过滤器在请求结束时清理线程身份。

这里有两次普通数据库查询：一次查订单列表，一次查当前余额，不按每笔订单重复查询余额。不修改余额、订单或权益，不加`FOR UPDATE`，不另开业务事务。读取的是数据库可见的已提交状态；如果查询后又完成付款，下次查询会看到新状态。余额是当前值，不代表每笔订单当时的账户余额。

## 3. 各文件职责

| 文件 | 本次内容 |
| --- | --- |
| order/controller/OrderController.java | 新增GET /me/orders；创建和付款改为方法上写完整路径，保留POST /orders和POST /orders/{id}/pay |
| order/service/OrderService.java | 增加List<OrderVO> getMyOrders()声明 |
| order/service/impl/OrderServiceImpl.java | 读取身份、查询、检查上限、循环转换；复用toOrderVO，无额外分流类 |
| order/mapper/OrderMapper.java | 声明selectOrdersByUserId，@Param对应XML参数 |
| resources/mapper/order/OrderMapper.xml | 按当前账号查订单快照并排序，LIMIT限制读取数量 |
| order/vo/OrderVO.java | 共9字段；2026-10-16增加userId与当前余额字符串balanceCents |
| auth/mapper/UserMapper.java及XML | selectBalanceById一次查询当前账号余额 |

这里把Controller原来类上的`/orders`前缀移到两个原有方法上，是为了让同一订单Controller也能提供`/me/orders`。三个完整路径不会相互拼接。

## 4. SQL做了什么

```sql
SELECT id, user_id, wallpaper_id, title_snapshot, amount_cents, currency, status,
       payment_method, created_at, paid_at, canceled_at, refunded_at, updated_at
FROM orders
WHERE user_id = #{userId}
ORDER BY created_at DESC, id DESC
LIMIT #{limit}
```

`user_id`条件保证账号隔离；没有状态条件，因此包含全部状态。没有关联wallpapers或entitlements，历史订单不依赖当前是否仍有购买权益。`#{}`由MyBatis绑定参数，不拼接SQL。

例如同一账号两条订单创建时间相同、ID分别为12和13，则13先返回；其他账号即使有更大的ID，也不会进入这个账号的结果。只用一次SELECT同时完成排序和数量识别，不再增加COUNT查询。

## 5. 返回字段

```json
{
  "code": "OK",
  "message": "",
  "data": [
    {
      "id": "12",
      "userId": "11",
      "balanceCents": "9010",
      "wallpaperId": "8",
      "title": "Fate·举剑",
      "amountCents": 990,
      "currency": "ZLC",
      "status": "PAID",
      "createdAt": "2026-10-15T10:00:00Z"
    }
  ],
  "requestId": "示例请求标识"
}
```

ID沿用字符串类型；时间为UTC；990最小单位代表9.90壁纸币。历史CNY订单按原快照返回，不转换成ZLC。不返回内部userId、付款方式、文件地址等字段。

## 6. Apifox验证

1. 登录取得accessToken；选择本机开发环境，确认Auth引用有效Bearer。
2. 使用GET，请求`http://127.0.0.1:8080/api/v1/me/orders`，Body选择none。
3. 选择200响应定义，确认`data`为Order数组。此前已有订单就会返回，无需新建测试订单。
4. 换另一个账号令牌，只能看到另一个账号的订单；新账号没有订单返回空数组。
5. 缺少令牌返回401。商品改名/调价/下架后，订单标题金额保持下单值。

两份OpenAPI同步本次说明；桌面端与工作台用户界面仍按此前安排留待集中适配。

## 7. 验证范围

`OrderQueryIntegrationTest`通过完整认证过滤器、Controller、Service、Mapper及独立H2库验证：空列表与requestId、全部状态与稳定排序、历史快照与大ID、账号隔离与伪造userId、缺少/无效登录、禁用账号、Service无身份、1000/1001条边界。

`OrderMySqlIT.myOrdersReadsSnapshotFromMySqlAfterProductChanges`在真实MySQL验证参数化LIMIT及订单实体映射，商品修改后查询仍返回原订单快照；只创建和清理独立标记夹具，不操作既有用户订单。

本次执行结果：订单查询新增8项测试与既有创建订单、付款、认证回归合计80项全部通过；真实MySQL新增1项通过。数据库结构没有改动，未向OSS写入文件。

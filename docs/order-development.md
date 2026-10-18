# 订单开发说明：当前权益与待付款订单防重

更新：2026-10-14。本页替代原请求键方案。POST /api/v1/orders不再要求Idempotency-Key，order_create_requests表、对应实体和SQL均已移除。

## 1. 当前下单流程

1. 从BaseContext取得认证过滤器提供的用户ID，锁住该用户行并确认账号仍为ACTIVE。
2. 锁定商品行并读取真实标题、价格、币种和发布状态，让下单与管理端调价、下架使用一致的商品快照。
3. 查当前权益。有记录就表示拥有，返回source_order_id对应的本账号PAID订单，HTTP 200。
4. 没有权益再查有效订单。有PENDING订单就返回原订单，HTTP 200；有PAID却没有权益则返回409，避免重复收费。
5. 两者都没有时才检查商品必须PUBLISHED。草稿、下架或不存在商品返回404。
6. 收费商品创建PENDING订单，HTTP 201；免费商品创建PAID订单并同时新增权益，HTTP 201。

权益和待付款订单的查询先于新购买资格判断，因此商品后来下架，用户仍可重读已有购买结果。分类停用不等于商品下架，当前商品发布状态决定能否新增购买。

## 2. Apifox测试

方法和地址：POST http://127.0.0.1:8080/api/v1/orders。Auth携带有效Bearer访问令牌，Body选择JSON：

```json
{
  "wallpaperId": "8"
}
```

不需要额外请求键。旧客户端即使还发送该头，后端也忽略；桌面端和OpenAPI已经移除。商品8创建后为DRAFT，需要先上架才能首次购买。

新单201，复用200；两者data都为OrderVO：字符串id、userId和wallpaperId、当前余额字符串balanceCents、标题快照title、成交金额amountCents、币种currency、状态status、UTC createdAt（共九字段，2026-10-16更新）。外壳继续包含code、message、data、requestId。余额从UserMapper.selectBalanceById读取，不作为订单快照存表，详见[订单确认窗口说明](checkout-ui-development.md)。

2026-10-15壁纸币付款已实现，POST /orders/{id}/pay实际扣余额，不再有测试开关或testPaymentEnabled字段，详细流程和Apifox步骤见 [壁纸币付款开发说明](order-payment-development.md)。商品价格、购买者与付款状态由后端决定，DTO不接受客户端传userId、amountCents或status。重启后端并重新导入当前OpenAPI后再测试。

## 3. 现在的权益表

entitlements只有七个字段：

| 数据库字段 | Java属性 | 用途 |
| --- | --- | --- |
| id | id | 权益自增编号 |
| user_id | userId | 拥有商品的用户 |
| wallpaper_id | wallpaperId | 拥有的壁纸 |
| source_order_id | sourceOrderId | 授予该权益的订单 |
| granted_at | grantedAt | 本次授予时间，UTC |
| created_at | createdAt | 本条记录创建时间，UTC |
| updated_at | updatedAt | 本条记录修改时间，UTC |

status、revoked_at及相关CHECK/索引已删除。存在记录即有权益，不存在即没有权益。用户与商品组合仍唯一；来源订单的复合外键继续确保同账号、同商品。

退款完成时应在同一数据库事务中将订单改为REFUNDED，并删除该订单授予的权益。历史保存在orders；重新购买创建新订单、新权益，不恢复旧权益ID。

创建订单阶段没有新增对外付款、退款接口；后续壁纸币付款已实现，退款接口仍未开发；这些后续业务状态通过事务测试验证。现有免费下单已经真实使用订单和权益一起提交的事务。

## 4. 文件和调用关系

Java路径位于src/main/java/com/zlo/wallpaper，XML位于src/main/resources。

| 文件 | 作用与调用方 |
| --- | --- |
| order/dto/OrderCreateDTO.java | Controller绑定JSON，仅允许wallpaperId |
| order/controller/OrderController.java | Spring MVC入口，调用OrderService并决定201/200 |
| order/service/OrderService.java | 声明createOrder(dto)、payOrder(id)，Controller依赖此接口 |
| order/service/impl/OrderServiceImpl.java | 集中实现下单、壁纸币付款、事务和两种付款的权益授予 |
| order/entity/Order.java | Mapper读取或Service创建的订单实体 |
| order/model/OrderProductRow.java | Mapper承接商品五列查询，不是新表 |
| order/model/OrderCreateResult.java | Service返回给Controller的order和created标志 |
| order/vo/OrderVO.java | Service转换的九字段前端订单数据，含当前余额和用户ID |
| order/mapper/OrderMapper.java | Service注入的MyBatis代理，声明锁定读取及插入 |
| mapper/order/OrderMapper.xml | 上述Mapper实际SQL |
| entitlement/entity/Entitlement.java | 七列当前权益实体 |
| entitlement/mapper/EntitlementMapper.java | 当前权益读取、新增、来源订单删除 |
| mapper/entitlement/EntitlementMapper.xml | 权益实际SQL |
| database/006-simplify-order-entitlements.sql | 已有库的简化迁移 |

每个类和显式方法已补中文注释，包含创建/注入位置、参数、返回值和源码调用或框架回调位置。参见 [代码注释说明](backend-code-comments.md)。

## 5. DTO与Controller逐段读

OrderCreateDTO用Lombok生成访问方法。NotBlank拒绝缺失或空白，Size最多128字符，Pattern限制外部ID格式。StrictStringDeserializer禁止把JSON数字8隐式转成字符串"8"。

rejectUnknownField由Jackson处理未知字段时调用，拒绝客户端指定价格、用户或PAID状态；JSON读取错误由现有GlobalExceptionHandler统一返回400。

Controller的RestController注册HTTP入口；RequestMapping只写/orders，因为应用已有/api/v1前缀。Autowired注入OrderService。Valid RequestBody绑定和校验输入，不再读取请求键头。

createOrder调用业务后，根据result.isCreated选择201或200。ApiResponse只包装result.getOrder，因此created是内部信息，不额外出现在业务JSON中。requestId来自当前HTTP请求，每次重复操作可以有不同requestId。

## 6. ServiceImpl各方法

createOrder是公开事务入口。首先取得可信用户ID并锁users行；无身份或账号禁用返回401。CatalogIds将外部编号映射为本机正数BIGINT，无法映射返回404。

商品先锁定读取，但PUBLISHED检查留到真正需要新单时。权益存在时读取来源订单并确认账号、商品和PAID状态；不匹配就返回PURCHASE_STATE_CONFLICT。权益不存在时读取生成列对应的有效订单：PENDING复用，PAID缺权益拒绝。

新单的titleSnapshot、amountCents、currency来自商品表；免费设PAID、FREE和paidAt，收费设PENDING且付款信息为空。时间为UTC毫秒，避免首次返回和数据库重读精度不同。

insertOrder后MyBatis回填id。免费调用grantEntitlement，在当前事务内new Entitlement，填账号、商品、来源订单与时间，然后insert。没有ACTIVE、REVOKED或重新激活分支。

result调用提取出来的toOrderVO，把两个ID转字符串、title取旧快照、金额取成交价、时间转UTC偏移；created仅供Controller选择状态码。toOrderVO也由壁纸币付款复用，付款不再返回testPaymentEnabled。

productMissing、loginRequired、purchaseConflict分别构造404、401、409业务异常，供业务判断throw，再由统一异常处理输出。

## 7. Mapper与XML

Mapper接口由MyBatis扫描生成代理，方法名对应XML的id。多参数通过Param命名，SQL用井号参数绑定而非拼接输入。

| 方法 | SQL内容 |
| --- | --- |
| selectBuyerStatusForUpdate | 按用户主键锁users并读取账号状态 |
| selectProductForUpdate | 按商品主键锁wallpapers，读定价和发布状态 |
| selectActiveOrderForUpdate | 按用户和active_wallpaper_id查唯一有效订单 |
| selectOwnedOrderForUpdate | 同时限制订单ID与用户ID，读取权益来源订单 |
| insertOrder | 写服务器快照、状态与时间，并回填订单ID |
| selectForUpdate | 按用户和商品读取当前权益，查不到即未拥有 |
| insert | 新增当前权益，回填权益主键 |
| deleteBySourceOrder | 只删除本账号本商品由指定订单授予的权益 |

orderColumns统一查询字段。useGeneratedKeys=true、keyProperty=id让数据库生成主键后回填参数对象。不再有查询或插入创建请求记录的SQL。

退款删除必须限制source_order_id：

```sql
DELETE FROM entitlements
WHERE user_id = #{userId}
  AND wallpaper_id = #{wallpaperId}
  AND source_order_id = #{orderId}
```

旧订单退款后又购买，新权益来源已经换成新订单。旧退款重复删除会影响0行，不能删掉新权益。该Mapper方法目前在退款场景测试使用，后续真实退款服务应在订单退款事务内调用。

## 8. 事务与行锁

事务保证免费订单与权益一起成功或失败。只用事务做“先查再插”仍可能让两个请求同时认为没有订单，所以两者都先争用同一用户行。

第一个请求完成提交后，第二个获得用户锁，再读取最新权益或PENDING订单并复用。顺序为用户→商品→权益→来源或有效订单。后续付款、退款也需要一致的锁顺序，并检查当前订单状态。

FOR UPDATE必须在事务内使用，等待后读取最新状态，提交或回滚自动释放锁。这里锁内只有短数据库操作，没有OSS上传或外部支付调用。

现有active_wallpaper_id生成列保留：PENDING/PAID时等于wallpaper_id，CANCELED/REFUNDED时为null；用户和此列的唯一索引继续防止重复有效订单。退款后不能留PAID，否则再买会被唯一键阻止。

付款成功需在同一事务中完成PENDING→PAID与插入权益；退款成功需完成PAID→REFUNDED与来源权益删除。重复付款/退款依据订单状态处理，取消或退款后的旧付款通知不能重新授予权益。

移除请求表后，提供的是购买防重，不记住某次创建请求的历史结果。取消或退款后到达的旧创建请求可能被当作新购买，前端不能承诺按旧键恢复原订单。

## 9. 迁移与验证

005创建请求表脚本已移除；新库001权益定义已简化。已有库006通过information_schema检查旧结构，删除请求表、撤销权益、旧状态与撤销时间，调整索引和CHECK；允许重复执行。

本机迁移前订单、权益、请求记录都是0行；用户、商品和会话保留。新库依次执行001、002、003、004、006、007；已有库只补增量，不重跑001。

verify-business-tables.sql已改为退款删权益、再次购买新增权益，最后回滚所有测试数据。自动测试包含29项订单HTTP/回滚用例和5项真实MySQL用例，全部通过；覆盖新单、重复购买、账号隔离、并发、订单权益不一致、退款重新购买和旧退款保护。

完整后端回归195项，194通过、1项为未开启的条件式浏览器用例，失败/错误0。桌面端商城测试通过，程序构建成功。OpenAPI解析与本地引用检查通过。006重复执行成功，数据库复检权益7列、请求表0张、订单测试账号0个；事务回滚脚本的停售保护、退款后再购买和夹具回滚三项检查均通过。

壁纸币付款已经后续实现；订单列表、已购和下载授权继续按用户安排分步开发，仍没有新增对外退款接口。上面的195项结果属于创建订单阶段验证，新付款验证见order-payment-development.md。

虚拟币补充：商品与新订单币种ZLC，100最小整数单位=1壁纸币；免费订单不扣币，收费订单由/pay扣users.balance_cents。余额不足保持PENDING；未来COIN退款还需退币，退款接口仍未开发。

# auth 模块开发与联调说明

> 2026-10-16更新：显示名称字段已移除；UserVO仅返回id、username、balanceCents。旧库执行008迁移。前端修改用户名、密码和充值入口仅预留，后端尚未开放，详见[user-profile-development.md](user-profile-development.md)。

2026-10-15余额补充：users新增balance_cents（Long，默认0），100整数单位=1壁纸币；UserVO新增字符串balanceCents，登录、刷新及GET /me均返回。账号注册不接受余额输入，按数据库默认0初始化。正常付款详见 [壁纸币付款开发说明](order-payment-development.md)。

本模块采用普通 Java 类、Lombok getter/setter、`@Autowired`、Service 接口及实现、Mapper XML。账号存 MySQL `users`，登录状态存 MySQL `sessions`，不依赖 Redis。

这份文档分成两部分：前半部分用于启动、联调和查配置；后半部分是对照当前源码的逐文件导读。代码片段是阅读辅助，完整实现以链接中的源文件为准。示例用户、会话编号和日期均为教学数据。

### 阅读导航

- [代码导读与推荐顺序](#reading-order)：先建立各层的关系。
- [DTO、Entity、VO 和内部模型](#data-classes)：理解参数、数据库记录和返回对象。
- [配置和基础组件](#configuration)：理解密码组件、令牌配置和时间来源。
- [Controller 与 Service 接口](#controllers)：理解请求入口和方法签名。
- [Mapper 与每条 SQL](#mapper-sql)：先读普通查询，再读会话锁和更新。
- [AuthServiceImpl 的每个方法](#auth-service)：注册、登录、刷新、登出、认证及私有辅助方法。
- [UserServiceImpl 与当前用户](#current-user)：从登录身份查询自己的资料。
- [令牌工具类](#token-tools)：随机令牌、哈希、JWT 签发和校验。
- [过滤器、上下文与错误处理](#filters)：理解请求进入 Controller 之前发生什么。
- [清理任务、建表脚本和测试](#tasks-tests)：理解记录保留与验证方式。
- [五条完整调用链和阅读自查](#request-traces)：把文件串起来看。

## 启动和迁移

本机已执行 `database/002-create-sessions.sql`，现在共有八张表；`idempotency_records` 仍待定。其他机器需要先执行 001，再执行 002；已有七张表的数据库只执行 002。应用启动不会自动建表或插入账号。

本机 `.env` 已配置随机生成的 `AUTH_JWT_SECRET`，不提交真实值。其他环境需要配置 Base64 编码的至少32个随机字节。JWT密钥需要保持稳定；更换密钥会使旧访问令牌失效。

从项目根目录运行 `scripts/start-backend.ps1`，或在 IDEA 运行 `ZloWallpaperApplication`。修改 `pom.xml` 后在 IDEA 重新加载 Maven，SDK/Runner 使用 Java21。Lombok 是编译期生成 getter/setter 的依赖，通常 IDEA 会自动识别。

## 五个接口

基础地址：`http://127.0.0.1:8080/api/v1`。四个 POST 使用 `Content-Type: application/json`。

| 方法 / 路径 | 请求 | 成功结果 |
| --- | --- | --- |
| POST /auth/register | username、password | 201，data:{username}；需要另行登录 |
| POST /auth/login | username、password | 200，data:SessionVO |
| POST /auth/refresh | refreshToken | 200，新的SessionVO；旧令牌失效 |
| POST /auth/logout | refreshToken | 200，data:{}；不要求有效accessToken |
| GET /me | Authorization: Bearer accessToken | 200，data:{id,username,balanceCents} |

SessionVO 的字段为 accessToken、refreshToken、expiresIn、user；expiresIn 是访问令牌剩余秒数，2026-10-14起默认691200秒（8天），最多为整组会话剩余时间。用户ID作为字符串返回。每个响应都有 code、message、data、requestId，错误保留实际HTTP状态，data:null。配置更改不会延长已经签发的旧JWT，重启后端后重新登录或刷新令牌，才能取得新期限的accessToken。

用户名要求3–32位ASCII字母、数字、点、下划线或短横线；不自动修剪用户名。MySQL名称判重不区分大小写。密码要求8–128个Unicode字符且不能全部为空白，不修剪密码。认证字段必须是JSON字符串。

普通用户和管理员共用登录接口。公开注册只能创建USER/ACTIVE；管理员由后续受控置备流程创建，不开放注册为管理员。UserVO 不返回角色或密码，管理接口权限由服务端读取当前数据库角色判断。

## Apifox 操作顺序

1. 注册：POST `/auth/register`，示例请求：

   ```json
   {"username":"learner","password":"Learning123!"}
   ```

2. 登录：POST `/auth/login`，使用同样的请求体。将 data.accessToken、data.refreshToken 分别存到 Apifox 环境变量。
3. 查询自己：GET `/me`，在 Bearer Token 中填访问令牌。仅设置请求头，不发送JSON请求体。
4. 刷新：POST `/auth/refresh`，请求 `{"refreshToken":"填写实际刷新令牌"}`。成功后同时替换环境变量中的两个令牌，不能继续使用旧令牌。
5. 登出：POST `/auth/logout`，请求当前刷新令牌。再次GET `/me` 应返回401 SESSION_EXPIRED。

注册重名409 USERNAME_EXISTS；登录账号、密码错误或账号禁用401 INVALID_CREDENTIALS；参数错误400 VALIDATION_ERROR；过期/伪造/撤销令牌401 SESSION_EXPIRED；普通用户访问 `/admin/**` 返回403 FORBIDDEN。

商品、分类、封面和健康检查仍允许匿名GET。其他业务路径默认要求认证，OPTIONS预检不要求令牌。管理端合法来源的401/403响应保留CORS头；认证过滤器不会把异常交给浏览器登录重定向。管理商品接口仍待实现，通过管理员校验后会得到路由404；管理员分类四接口已实现，见 [catalog-development2.md](E:/zlowallpaper_sever/docs/catalog-development2.md)。

## 会话表和令牌轮换

一条sessions记录对应一代随机刷新令牌，不对应每次HTTP请求。一次登录创建第一条记录，每次刷新增加一条记录，同一组共享family_id和固定expires_at。新设备登录使用新的family_id。

| MySQL字段 | Java字段 | 类型 / 说明 |
| --- | --- | --- |
| id | id | Long，自增记录ID，也是JWT的sid |
| user_id | userId | Long，关联users.id |
| family_id | familyId | String，一次登录及其轮换链的UUID |
| refresh_token_hash | refreshTokenHash | String，SHA-256哈希，唯一，不存明文刷新令牌 |
| created_at | createdAt | LocalDateTime，创建时间UTC |
| expires_at | expiresAt | LocalDateTime，整组固定截止时间UTC |
| revoked_at | revokedAt | LocalDateTime，撤销时间，允许空 |
| revoke_reason | revokeReason | String，ROTATED/LOGOUT/REPLAY/DISABLED，允许空 |
| replaced_by_id | replacedById | Long，下一代记录ID，允许空，由事务维护 |

刷新流程：核验令牌哈希 → 锁定整组首条记录 → 重新读取令牌记录 → 检查有效期、撤销状态及用户状态 → 插入新记录 → 标记旧记录ROTATED并设置replaced_by_id → 提交事务。

`SELECT ... FOR UPDATE` 是事务里的数据库行锁。在同一组登录同时收到刷新、登出请求时，让它们按顺序修改，避免同时生成两组有效后继。锁后再次用FOR UPDATE读取，是为了读取最新已提交状态，避免MySQL可重复读快照保留并发操作前的数据。

被轮换的旧刷新令牌再次使用，会撤销整个family，并返回401。这个撤销必须提交，因此刷新方法使用 `@Transactional(noRollbackFor = BusinessException.class)`；数据库故障和程序异常仍回滚。

本阶段选择立即使旧访问令牌失效：访问令牌的sid指向对应记录，记录轮换后即被撤销。前端需要在刷新成功后同时替换两个令牌；并发重复刷新被视为重放，会导致这一组登录失效，客户端应避免同时刷新。

登出只撤销对应family，其他设备不受影响。未知令牌、重复登出返回200空对象；已轮换的旧刷新令牌也能撤销它对应的整组登录。重新启用被禁用账号不会恢复已经撤销的会话。

访问令牌使用JWT签名，包含用户ID、会话记录ID、签发和到期时间；每次私有请求还检查MySQL会话和用户当前角色/状态。密码使用PBKDF2随机盐哈希，通过PasswordEncoder比较；高熵随机刷新令牌使用SHA-256做查询指纹，两种哈希用途不同。

## 清理和配置

`wallpaper.auth.access-token-seconds` 默认691200（8天）；`session-days` 默认30；`retention-days` 默认7。整组会话不会因刷新无限延期；剩余时间不足60秒时要求重新登录。

`SessionCleanupTask` 每天UTC03:00（北京时间11:00）删除整组截止时间已超过7天的记录。有效期及保留期内保留旧记录用于重放识别；不删除用户、订单或权益。`cleanup-cron` 可配置，设为 `-` 关闭定时执行。过期判定始终由接口执行，不依赖清理任务及时运行。

## 验证

默认 `mvn verify` 使用独立H2，实际执行Mapper SQL和事务，不读取本机凭证。认证测试包含注册判重、长Unicode密码、JSON类型、登录失败、/me、JWT与会话过期、轮换、重放撤销提交、独立登录、并发刷新、登出、角色/账号状态变化、CORS、线程上下文清理和过期清理。

真实MySQL额外验证：

```powershell
$env:JAVA_HOME = 'D:\Codex\software\jdk-21.0.12.1+1'
& 'D:\Codex\software\apache-maven-3.9.16\bin\mvn.cmd' -B -ntp '-Dmaven.repo.local=D:/Codex/software/maven-repository' '-Dauth.mysql.tests=true' '-Dtest=AuthMySqlIT' test
```

此检查只创建UUID命名的测试账号，真实提交事务以验证轮换锁、重放撤销和刷新/登出竞争。结束后按精确用户ID清理测试账号与会话，不清理业务账号；自增ID可能留下正常空档。

后续阅读建议：先看AuthController和DTO/VO，再看UserMapper注册、登录的实现，最后看SessionMapper、刷新事务与AuthFilter。

2026-10-11 验收：`mvn verify` 56项全部通过（auth21、catalog32、健康/CORS3），打包成功；真实MySQL事务测试3项通过；临时服务18081上的14次HTTP请求通过，逐次核对状态、requestId和CORS。临时服务已停止，测试账号及会话已清理；未创建真实管理员账号。

<a id="reading-order"></a>

## 代码导读：先知道自己在读哪一层

### 建议分四轮阅读

| 轮次 | 阅读内容 | 这一轮需要弄懂的问题 |
| --- | --- | --- |
| 第一轮 | DTO、VO、AuthController、AuthService、UserMapper，再读 register/login | 请求的数据在哪里接收？密码如何入库？登录返回什么？ |
| 第二轮 | UserController、UserServiceImpl、AuthFilter、BaseContext、authenticate | 后端怎么知道正在请求的是哪个用户？ |
| 第三轮 | Session、SessionMapper、refresh/logout、TokenGenerator、JwtUtil | 两种令牌怎样使用？为什么刷新要修改两条记录？ |
| 第四轮 | 配置、清理、异常处理、测试 | 参数错误、过期、并发和数据库故障怎样处理？ |

第一轮暂时把 `createSession(...)` 理解为“保存一次登录记录”，把 `sessionVO(...)` 理解为“生成登录返回值”。等注册和登录读通，再进入这两个辅助方法，不必一开始就钻进 JWT 和行锁。

### 文件夹与调用方向

```text
auth/
  controller/   接收 HTTP 请求，调用 Service，包装返回值
  dto/          接收请求参数、检查参数格式
  entity/       对应 users、sessions 的数据库记录
  mapper/       声明数据库读写方法，SQL 在 resources/mapper/auth 中
  service/      声明业务方法
    impl/       实现业务判断，组织 Mapper 和工具类
  vo/           返回给前端的数据
  model/        后端内部传递的登录身份
  util/         生成令牌、计算哈希、签发和校验 JWT
  filter/       在请求到达 Controller 前检查登录与管理员权限
  task/         定时清理过期会话
```

普通业务调用仍是 `Controller → Service 接口 → ServiceImpl → Mapper → XML SQL → MySQL`。`Service` 是调用方看到的方法约定，运行时实际执行 Spring 注入的 `ServiceImpl`。Mapper 接口的实现由 MyBatis 生成，不需要另写 `UserMapperImpl`。

过滤器位于这条链之前，DTO 在 Controller 参数绑定时使用，VO 在返回结果时使用。工具类由 Service 调用，清理任务则由定时调度触发。

### 本模块经常出现的 Java 写法

| 写法 | 放在本项目里怎样理解 |
| --- | --- |
| `package ...;` | 文件属于哪个 Java 包；和文件夹结构对应 |
| `import ...;` | 引入其他类的名字，后面可以写短类名；不是创建对象 |
| `implements AuthService` | 这个类实现 AuthService 声明的方法 |
| `@Override` | 告诉编译器：这里在实现接口方法或重写父类方法 |
| `@Autowired private UserMapper userMapper;` | Spring 把已经管理的 Mapper 对象注入字段，不用手动 new |
| `@Service` / `@Component` | 把该类交给 Spring 管理；前者明确标记业务层 |
| `public` / `private` | 前者供其他类调用；后者主要供本类内部使用 |
| `return value;` | 返回结果并结束当前方法 |
| `throw exception;` | 中断正常流程，向调用方传递异常 |
| `a == null` | 判断没有对象；不是判断字符串为空 |
| `!condition` / `a \|\| b` / `a && b` | 取反 / 任一满足 / 两者都满足；`\|\|`、`&&` 会短路求值 |
| `"ACTIVE".equals(status)` | 比较字符串内容，即使 status 为 null 也不会空指针 |
| `String value = condition ? a : b;` | 条件满足取 a，否则取 b，等价于一个简单 if/else |
| `SomeClass.class` | 传递“这个类的类型信息”，不是调用类的方法 |

`@Autowired` 和构造器注入都属于 Spring 的依赖注入机制。Spring Boot 帮助配置和启动 Spring 应用，并没有另发明一套 `@Autowired`。现有 `JwtUtil` 使用单构造器注入，其余多数 auth 类使用字段注入；这两种方式在当前工程中都有效。

<a id="data-classes"></a>

## DTO、Entity、VO 和内部模型

### 1. CredentialsDTO.java：注册和登录共用的字段与校验

文件：[CredentialsDTO.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/dto/CredentialsDTO.java)。

这个类保存 `username` 和 `password`。它是两个具体请求 DTO 的父类，共用规则写一次即可。

`@Getter`、`@Setter` 是 Lombok 注解，编译时生成 `getUsername()`、`setUsername(...)`、`getPassword()`、`setPassword(...)`。源文件里没有这些方法，Service 仍能调用它们。本类没有用 `@Data`：这里只需要读写字段，不需要自动生成可能包含密码的 `toString()`。

| 代码 | 实际检查什么 |
| --- | --- |
| username 上的 `@NotNull` | 字段不能缺失或为 JSON null |
| `@Pattern(regexp = "[A-Za-z0-9._-]{3,32}")` | 用户名只能包含这些字符，长度 3–32；中文、空格和斜杠不能通过 |
| password 上的 `@NotNull` | 密码必须提供 |
| 两个字段上的 `@JsonDeserialize(using = StrictStringDeserializer.class)` | JSON 中必须是字符串，不能拿数字或布尔值代替 |
| 方法上的 `@AssertTrue` | 参数校验时调用 `isPasswordValid()`，结果必须为 true |

`isPasswordValid()` 按下面的顺序执行：

```java
if (password == null) { return true; }
int length = password.codePointCount(0, password.length());
return length >= 8 && length <= 128 && !password.isBlank();
```

这里 null 返回 true 的意思是“空值交给字段上的 `@NotNull` 报错”，整个 DTO 并不会因此接受 null。它也避免了对 null 调用方法。

`codePointCount(...)` 数 Unicode 码点；`String.length()` 数 UTF-16 编码单元，某些 emoji 会被它计为 2。当前密码限制按码点计算，组合字符仍可能包含多个码点。`isBlank()` 判断整串是否全部为空白，不会删除密码首尾空格。密码 `" abcdefgh "` 会按原样保存和比较。

### 2. RegisterDTO.java 与 LoginDTO.java：两个入口，共用父类

文件：[RegisterDTO.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/dto/RegisterDTO.java)、[LoginDTO.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/dto/LoginDTO.java)。

两者目前分别是 `extends CredentialsDTO` 的普通类。类体虽然为空，但继承了父类的两个字段、getter/setter 和校验规则，因此并不是“接口不接收数据”。

分别命名能让 `register(RegisterDTO dto)` 和 `login(LoginDTO dto)` 的用途更清楚；以后两者需要不同字段时可以分别扩展。当前都不接收 `role`，注册的角色由后端写死为普通用户。

### 3. RefreshTokenDTO.java：刷新和登出的请求体

文件：[RefreshTokenDTO.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/dto/RefreshTokenDTO.java)。

它只有 `String refreshToken`，也通过 Lombok 生成 getter/setter。`@NotBlank` 同时拒绝 null、空字符串和全空白字符串；`@Size(max = 512)` 限制输入长度；严格字符串反序列化器检查 JSON 类型。

因此接口接收的是：

```json
{"refreshToken":"登录或刷新响应中的实际 refreshToken"}
```

它不会从这份 DTO 接收 accessToken、userId 或 familyId。服务器拿令牌哈希查表，自己确定对应用户和会话组。

### 4. StrictStringDeserializer.java：把 JSON 读成 Java 字段前检查类型

文件：[StrictStringDeserializer.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/dto/StrictStringDeserializer.java)。

`extends JsonDeserializer<String>` 表示定制“从 JSON 读取一个 String”的过程。`deserialize(JsonParser parser, DeserializationContext context)` 是 Jackson 调用的方法，并不是 Controller 主动调用。

1. `parser.currentToken()` 查看当前 JSON 值的类型。
2. 如果不是 `JsonToken.VALUE_STRING`，调用 `context.reportInputMismatch(...)` 报类型错误。这行虽然写着 `return`，实际会抛出解析异常，不能理解成返回了一条普通字符串。
3. 类型正确时，`parser.getText()` 返回原始字符串内容。

例如 `{"password":12345678}` 会被拒绝；`{"password":"12345678"}` 才能继续做长度校验。JSON null 的必填检查由 DTO 的校验注解负责。

`throws IOException` 是这个重写方法的异常声明。Jackson 解析失败后，Spring 把读取请求体的失败交给全局异常处理，最终返回 400。顺序是“读取 JSON → 创建 DTO → `@Valid` 校验 → 执行 Controller”，读取或校验失败时不会继续注册。

### 5. User.java：users 表的一行

文件：[User.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/entity/User.java)。

| Java 字段 | 数据库字段 | 用途 |
| --- | --- | --- |
| `Long id` | id | 用户自增 ID；插入之前可能为 null，插入后由 MyBatis 回填 |
| `String username` | username | 注册名和登录名 |
| `String passwordHash` | password_hash | 密码哈希，不是明文密码 |
| `Long balanceCents` | balance_cents | 壁纸币余额，默认0；100单位=1币，VO中转为字符串 |
| `String role` | role | USER 或 ADMIN，用于权限判断 |
| `String status` | status | ACTIVE 或 DISABLED，用于账号可用性判断 |
| `LocalDateTime createdAt` | created_at | 创建时间，按 UTC 约定保存 |
| `LocalDateTime updatedAt` | updated_at | 最近修改时间 |

它使用 `@Getter`、`@Setter`，Mapper 查询后把列值放入这个对象。当前 Mapper 的 SQL 手写在 XML 中，表名和列名已经明确，所以这里不需要通过 `@TableName` 告诉 MyBatis-Plus 自动生成 SQL。

Entity 包含密码哈希、角色、状态等内部字段，Controller 不直接把 User 当响应返回。Service 会转换为只含公开字段的 UserVO。

### 6. Session.java：sessions 表的一代令牌记录

文件：[Session.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/entity/Session.java)。

九个字段的数据库对应关系见前文会话表。读代码时重点记住：`id` 标识这一代记录，`userId` 标识账号，`familyId` 串起同一次登录的所有代，`refreshTokenHash` 用来按令牌查表。

`revokedAt`、`revokeReason`、`replacedById` 初始可以为 null。正常刷新后，旧记录会写入撤销时间、ROTATED 和下一代 ID，新记录则继续保持未撤销。`revokedAt == null` 只是有效条件之一，还要检查到期时间和账号状态。

这里的 Session 是项目自己定义的数据库实体，没有使用 Servlet 的 `HttpSession`。它也不是“每次请求都插入一条访问日志”。

### 7. 三个 VO：选择返回哪些字段

| 文件 | 字段 | 哪些接口使用 |
| --- | --- | --- |
| [RegisterVO.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/vo/RegisterVO.java) | username | 注册成功 |
| [UserVO.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/vo/UserVO.java) | String id、username、balanceCents | 当前用户；登录/刷新结果中的 user |
| [SessionVO.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/vo/SessionVO.java) | accessToken、refreshToken、Integer expiresIn、UserVO user | 登录和刷新 |

三个类都使用 `@Getter`、`@Setter`、`@AllArgsConstructor`。最后一个注解生成按字段声明顺序接收全部字段的构造器，因此可以写：

```java
UserVO userVO = new UserVO(user.getId().toString(), user.getUsername(), user.getBalanceCents().toString());
return new SessionVO(accessToken, token, seconds, userVO);
```

`Long.toString()` 使用户 ID 按接口契约返回字符串。`expiresIn` 的 `Integer` 是整数包装类型，传入的 int 会自动装箱。VO 主要用于序列化输出，当前构造流程不需要无参构造器。

Java 对象并不是手写 JSON 字符串。Spring/Jackson 根据 getter 把返回对象序列化成 JSON，字段名来自 Java 属性，外层包装由 ApiResponse 提供。返回值形状例如：

```json
{
  "code": "OK",
  "message": "",
  "data": {
    "accessToken": "访问令牌示意",
    "refreshToken": "刷新令牌示意",
    "expiresIn": 691200,
    "user": {"id": "1001", "username": "learner", "balanceCents": "0"}
  },
  "requestId": "本次请求编号示意"
}
```

### 8. AccessIdentity.java：JWT 校验后的内部身份

文件：[AccessIdentity.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/model/AccessIdentity.java)。

它只有 `long userId` 和 `long sessionId`，使用 `@Getter` 和 `@AllArgsConstructor`。JwtUtil 校验令牌后构造它，AuthServiceImpl 再据此查数据库。

它不是请求 DTO，也不对应独立数据库表，更不直接返回前端。两个 long 是已经校验过的正整数，因此这里不需要用可为 null 的 Long。

### 9. package-info.java：包说明

文件：[package-info.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/package-info.java)。

它用注释说明 auth 包的职责，用 `package` 声明所属包。没有业务方法，不参与登录处理，不需要为它寻找调用入口。

<a id="configuration"></a>

## 配置和基础组件：哪些对象是 Spring 提供的

### 10. pom.xml：本模块用到的依赖

文件：[pom.xml](E:/zlowallpaper_sever/pom.xml)。

| 依赖或配置 | 在 auth 中完成什么 |
| --- | --- |
| Java 21、Spring Boot 父工程 | 确定编译版本和大部分依赖版本 |
| lombok | 编译期生成 getter/setter/构造器 |
| spring-boot-starter-web | Controller、Servlet 请求、Jackson JSON 读写和 Web 启动 |
| spring-boot-starter-validation | 执行 DTO 和配置的校验注解 |
| spring-security-crypto | 提供 PasswordEncoder/PBKDF2；当前没有启用完整 Spring Security Web 登录体系 |
| jjwt-api | 代码编译时使用的 JWT API |
| jjwt-impl、jjwt-jackson | JWT 运行实现及 JSON 支持；runtime 表示运行时需要 |
| mybatis-plus-spring-boot3-starter | 整合 Mapper、XML 和数据库操作；auth 的查询没有用分页 |
| mysql-connector-j | JDBC 连接 MySQL |
| spring-boot-starter-test、h2 | 测试框架和独立内存测试数据库，scope=test 不用于正式运行 |

Redis 依赖已经在工程中，但本模块没有把登录状态存 Redis。`mybatis-plus-jsqlparser` 用于现有分页等功能，不参与 auth 的刷新锁 SQL。`${jjwt.version}` 引用 properties 中的版本，三份 JJWT 依赖保持一致。

Lombok 的 `optional=true` 不表示“本项目可不安装”；它主要影响其他项目依赖本项目时是否传递这项依赖。本项目编译仍需要它。

### 11. AuthProperties.java：把配置读成一个 Java 对象

文件：[AuthProperties.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/config/AuthProperties.java)。

`@ConfigurationProperties(prefix = "wallpaper.auth")` 把对应配置绑定到字段。YAML 的 `access-token-seconds` 对应 Java 的 `accessTokenSeconds`。Service 通过 `properties.getAccessTokenSeconds()` 读值，不必到处写 691200。

| 字段 | 默认值 | 校验与用途 |
| --- | --- | --- |
| jwtSecret | 无可用默认密钥 | `@NotBlank`，JWT 签名密钥的 Base64 文本；JwtUtil 还检查解码格式与长度 |
| accessTokenSeconds | 691200（8天） | 60–691200 秒，单次访问令牌的最大有效期 |
| sessionDays | 30 | 1–90 天，一次登录整组的固定期限 |
| retentionDays | 7 | 1–30 天，过期后保留历史记录的天数 |

`@Validated` 在配置绑定时检查这些规则，配置错误应在启动阶段被发现。这和 Controller 参数上的 `@Valid` 检查每次请求是两处不同的校验。

### 12. PasswordConfig.java：注册密码组件和统一时钟

文件：[PasswordConfig.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/config/PasswordConfig.java)。

`@Configuration` 标记配置类；`@EnableConfigurationProperties(AuthProperties.class)` 注册并绑定前面的配置对象；`@EnableScheduling` 启用 `@Scheduled` 任务。

`passwordEncoder()` 上的 `@Bean` 把返回的密码组件交给 Spring：

```java
return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
```

方法名中的 v5_8 是这套密码哈希参数预设的名称，不表示工程在运行 Spring 5.8。Service 注入 `PasswordEncoder` 接口后，注册调用 `encode(明文)`，登录调用 `matches(明文, 数据库哈希)`。每次 encode 使用随机盐，同一个密码再次 encode 得到的字符串可能不同，因此不能用“重新 encode 后字符串相等”判断密码。

`authClock()` 返回 `Clock.systemUTC()` 并注册为 Bean。Clock 是 Java 的时间来源，`clock.instant()` 获取当前时刻；不是定时器，也不会自己调用刷新接口。统一注入时钟便于让令牌、会话和清理按同一时间约定工作。

### 13. application.yml、application-local.yml 与 .env

文件：[application.yml](E:/zlowallpaper_sever/src/main/resources/application.yml)、[application-local.yml](E:/zlowallpaper_sever/src/main/resources/application-local.yml)、[.env.example](E:/zlowallpaper_sever/.env.example)。

通用配置中，`server.servlet.context-path: /api/v1` 是所有接口的前缀，所以 Controller 中 `/auth/login` 的完整路径是 `/api/v1/auth/login`。默认 local 环境加载本机配置。

```yaml
wallpaper:
  auth:
    jwt-secret: "${AUTH_JWT_SECRET:}"
    access-token-seconds: 691200
    session-days: 30
    retention-days: 7
```

`${AUTH_JWT_SECRET:}` 表示读取这个配置值，未提供时取冒号后的空值。空值会被校验拒绝，并不是自动生成密钥。

本机配置的 `optional:file:./.env[.properties]` 从运行目录读取 .env，并按 properties 格式解释；optional 表示该文件本身可以不存在，但必需的密码/密钥仍须从其他配置来源提供。因此 IDEA 应以项目根目录作为工作目录。

数据源配置指定 MySQL 地址、数据库名、应用账号和 `${MYSQL_PASSWORD}`。UTC 连接设置配合 Java 的 UTC 转换使用。`DATETIME` 本身不携带时区，不能认为 MySQL 自动知道 Java 值属于哪一个时区。

`map-underscore-to-camel-case: true` 使 `password_hash` 映射到 `passwordHash`、`user_id` 映射到 `userId`。`spring.sql.init.mode: never` 表示正式启动不自动执行初始化 SQL。真实 .env 只保存本机凭证，不在本文复制；示例文件用于说明应提供的变量名。

<a id="controllers"></a>

## Controller 与 Service 接口：请求入口

### 14. AuthController.java：四个认证 POST

文件：[AuthController.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/controller/AuthController.java)。

类上的 `@RestController` 表示方法返回数据，由框架写入 HTTP 响应体；`@RequestMapping("/auth")` 定义该 Controller 的共同路径；`@Autowired` 注入 AuthService。

以注册方法为例：

```java
@PostMapping("/register")
@ResponseStatus(HttpStatus.CREATED)
public ApiResponse<RegisterVO> register(
        @Valid @RequestBody RegisterDTO dto, HttpServletRequest request) {
    return ApiResponse.success(authService.register(dto), RequestIdFilter.current(request));
}
```

- `@PostMapping` 选择 POST 和具体路径。Java 方法名 register 不是 URL，路径由注解决定。
- `@RequestBody` 读取 JSON 请求体，转换为 RegisterDTO；这里不是通过 URL 查询参数传用户名和密码。
- `@Valid` 执行 DTO 校验；不符合规则时，框架直接返回错误，不进入方法体。
- `HttpServletRequest` 是 Servlet 提供的当前 HTTP 请求对象，Spring 传入它；前端不用在 JSON 中提供这个参数。
- `authService.register(dto)` 返回 RegisterVO；`RequestIdFilter.current(request)` 取本次请求编号；success 把两者放入统一响应。
- `@ResponseStatus(CREATED)` 将成功状态设为 201。业务异常会按异常处理规则返回 400/409 等，而不是固定返回 201。

`login(...)` 的结构相同，调用 `authService.login(dto)`，返回 `ApiResponse<SessionVO>`，成功默认 200。

`refresh(...)` 接收 RefreshTokenDTO，提取 `dto.getRefreshToken()`，传给 `authService.refresh(...)`，包装新的 SessionVO。Controller 不自己计算令牌有效期，也不操作 sessions 表。

`logout(...)` 先调用 `authService.logout(...)`，再返回 `ApiResponse.success(Map.of(), requestId)`。`Map.of()` 在这里创建空 Map，JSON 是 `{}`，不是 null；`Map<String, Object>` 表示键为字符串、值可以是不同对象。登出业务方法返回 void，Controller 自己提供空响应数据。

### 15. UserController.java：查询当前登录账号

文件：[UserController.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/controller/UserController.java)。

`@GetMapping("/me")` 对应 GET `/api/v1/me`。它没有请求 DTO、用户名参数或用户 ID 参数，调用 `userService.getCurrentUser()` 返回 UserVO，再加上 requestId。

“当前用户”来自认证过滤器已经核验的令牌。若允许前端任意传 userId 再据此查询，就不能保证查询的是自己，所以这里由后端确定身份。

### 16. AuthService.java：认证业务的方法约定

文件：[AuthService.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/service/AuthService.java)。

| 方法 | 参数与结果 | 调用方 |
| --- | --- | --- |
| register | RegisterDTO → RegisterVO | 注册 Controller |
| login | LoginDTO → SessionVO | 登录 Controller |
| refresh | String refreshToken → SessionVO | 刷新 Controller |
| logout | String refreshToken → void | 登出 Controller |
| authenticate | String accessToken → User | AuthFilter，属于内部认证方法 |

接口只声明方法，不写具体业务步骤。这里虽然有五个方法，却不能据此数 HTTP 接口：`authenticate` 不是独立 HTTP 接口，GET `/me` 由 UserService 处理。

### 17. UserService.java：当前用户业务的约定

文件：[UserService.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/service/UserService.java)。

它声明 `UserVO getCurrentUser()`。没有参数，因为实现会从当前请求上下文取用户 ID。UserController 依赖这个接口，具体实现是 UserServiceImpl。

<a id="mapper-sql"></a>

## Mapper 与 XML：每次数据库读写具体做什么

### 18. UserMapper.java 与 UserMapper.xml

文件：[UserMapper.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/mapper/UserMapper.java)、[UserMapper.xml](E:/zlowallpaper_sever/src/main/resources/mapper/auth/UserMapper.xml)。

Java 接口上的 `@Mapper` 让框架识别数据库访问接口。`@Param("username")` 为参数命名，XML 就能用 `#{username}` 引用它。XML 的 namespace 是 Mapper 接口的完整包名，select/insert 的 id 和 Java 方法名对应。

XML 开头的声明和 DTD 定义文件格式；真正的 SQL 位于 `<mapper>` 内。`<sql id="columns">` 是可复用的列清单，`<include refid="columns"/>` 把这份清单展开到 SQL 中，它本身不会单独执行查询。

#### selectByUsername：按用户名找账号

```sql
SELECT id, username, password_hash, role, status, balance_cents, created_at, updated_at
FROM users
WHERE username = #{username}
```

`FROM users` 说明查用户表；`WHERE` 筛选用户名；`resultType="...User"` 表示把一行结果转换为 User。唯一约束确保同名账号至多一行，查不到时返回 null。注册拿它判重，登录拿它取得哈希和账号状态。

`#{username}` 是绑定参数，不是把原始输入直接拼进 SQL。概念上是 `WHERE username = ?`，然后 JDBC 绑定具体值。这里做精确匹配，不是标题搜索那样的 LIKE；实际大小写比较由 users 表的排序规则决定。

#### selectById：按已经确定的用户 ID 查账号

查询的列相同，条件改为 `WHERE id = #{id}`。认证、刷新和查询当前用户都使用它。传入的是后端从令牌、会话或上下文确定的 ID，不要求用户在页面手输编号。

#### insert：保存注册账号并回填 ID

```xml
<insert id="insert" useGeneratedKeys="true" keyProperty="id">
    INSERT INTO users (username, password_hash, role, status, created_at, updated_at)
    VALUES (#{username}, #{passwordHash}, #{role}, #{status}, #{createdAt}, #{updatedAt})
</insert>
```

`INSERT INTO` 后面列出要写的数据库列，VALUES 中同位置放对应 Java 属性。没有写 id，因为数据库负责自增。

`useGeneratedKeys="true" keyProperty="id"` 让 MyBatis 把数据库生成的 ID 写回传入的 User 对象。例如插入前 `user.getId()` 为 null，插入后可能为 1001。`int insert(User user)` 的返回值表示影响行数，通常是 1；它不是新用户 ID。生成的 ID 要从对象字段读取。该机制可对照 [MyBatis 的 insert 与生成键说明](https://mybatis.org/mybatis-3/sqlmap-xml.html)。

### 19. SessionMapper.java 与 SessionMapper.xml

文件：[SessionMapper.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/mapper/SessionMapper.java)、[SessionMapper.xml](E:/zlowallpaper_sever/src/main/resources/mapper/auth/SessionMapper.xml)。

这里也通过 namespace、方法名和 `@Param` 对接。查单条返回 Session；插入、更新和删除返回 int 影响行数。当前没有联表查询，也没有分页查询，先按单表 SQL 阅读即可。

#### 三个查询方法：按记录 ID 或令牌指纹查找

| 方法 | SQL 条件 | 业务用途 |
| --- | --- | --- |
| selectById | `WHERE id = #{id}` | accessToken 中的 sid 指向哪条会话记录 |
| selectByRefreshHash | `WHERE refresh_token_hash = #{hash}` | 从刷新令牌的 SHA-256 指纹定位记录和 family |
| selectByRefreshHashForUpdate | 相同 hash 条件，末尾加 `FOR UPDATE` | 获得组锁后，重新读当前令牌记录的最新状态并加锁 |

都使用相同九列清单，结果映射到 Session。数据库没有存明文刷新令牌，所以不能直接拿 raw refreshToken 和 refresh_token_hash 比较；先由 TokenGenerator.hash 得到指纹。

#### lockFamily：同一组刷新和登出使用共同的锁

展开 SQL 后是：

```sql
SELECT id, user_id, family_id, refresh_token_hash, created_at, expires_at,
       revoked_at, revoke_reason, replaced_by_id
FROM sessions
WHERE family_id = #{familyId}
ORDER BY id
LIMIT 1
FOR UPDATE
```

逐段解释：`WHERE` 只找这一次登录的组；`ORDER BY id` 默认升序；`LIMIT 1` 只取最早的一条，作为这组操作共同使用的锁定记录；`FOR UPDATE` 是事务内的锁定读。LIMIT 在这里是选一条记录，不是在调用分页插件。

这里“锁定整组”是业务上的说法：同组所有刷新和登出先争用同一条首记录，让操作顺序执行。并不是代码发出了 `LOCK TABLE`，也不意味着所有用户都不能登录。InnoDB 具体还可能锁定扫描涉及的索引记录或范围，不必把它理解为物理上严格只锁一个位置。

一个事务持有这把锁时，另一个同组事务到这里会等待；事务提交或回滚后释放。普通 SELECT 也不等于全部被禁止读取。此 SQL 在 refresh/logout 的 `@Transactional` 方法内调用，锁才覆盖后续判断和修改。锁定读的规则可对照 [MySQL 8.4 文档](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)。

#### insert：建立一代会话

```sql
INSERT INTO sessions (user_id, family_id, refresh_token_hash, created_at, expires_at)
VALUES (#{userId}, #{familyId}, #{refreshTokenHash}, #{createdAt}, #{expiresAt})
```

登录时生成新 family，刷新时沿用旧 family。新增记录不填三个撤销字段，因此初始为 null。和用户插入一样，生成的 id 回填到 Session 对象，后面才能把它写入 JWT 的 sid 或旧记录的 replaced_by_id。

#### markRotated：把旧记录标记为已经被替换

```sql
UPDATE sessions
SET revoked_at = #{now}, revoke_reason = 'ROTATED', replaced_by_id = #{nextId}
WHERE id = #{id} AND revoked_at IS NULL
```

SET 修改旧记录的三个字段。WHERE 的 id 指向旧记录，`revoked_at IS NULL` 要求它还没有被撤销。判断 SQL 空值用 `IS NULL`，不能写 `= NULL`。

若旧 id=101，新 id=102，这条 SQL 把 101 的 replaced_by_id 设为 102。Service 要求影响行数恰好为 1；为 0 意味着目标不存在或已经撤销，不能继续当作成功刷新。

#### revokeFamily：撤销这一次登录的全部代

```sql
UPDATE sessions
SET revoked_at = #{now}, revoke_reason = #{reason}
WHERE family_id = #{familyId}
```

它用于登出、重放和禁用账号导致的撤销。WHERE 不筛 user_id，而是筛 family_id，所以其他独立登录不受影响。

当前 SQL 没有加 `AND revoked_at IS NULL`，因此历史记录也会更新原因。例如旧记录原来 ROTATED，整组登出后会变成 LOGOUT；replaced_by_id 仍保留。不要读成“只修改最新一条”或“撤销原因永不改变”。

#### deleteExpired：删除超过保留期的记录

XML 为 `DELETE FROM sessions WHERE expires_at &lt; #{cutoff}`，实际 SQL 的比较符是 `<`。`&lt;` 是 XML 对小于号的转义，避免把它误认成标签开始。

cutoff 由清理任务计算为“现在减去保留天数”。因此它不是把今天刚过期的记录马上删除。同一 family 的 expires_at 一样，整组会一起达到清理条件。

### 用两行示例理解 SQL 的效果

下表是教学数据，省略哈希和时间具体值。假设一次登录属于 family F，用户 1001：

| 时点 | id | user_id | family_id | revoked_at | revoke_reason | replaced_by_id |
| --- | --- | --- | --- | --- | --- | --- |
| 刚登录 | 101 | 1001 | F | null | null | null |
| 刷新成功后：旧记录 | 101 | 1001 | F | 刷新时刻 | ROTATED | 102 |
| 刷新成功后：新记录 | 102 | 1001 | F | null | null | null |

此时新的 accessToken 指向 sid=102；旧 accessToken 指向 sid=101，因此认证时会发现旧记录已撤销。

如果旧刷新令牌再次送来，找到的仍是 101。Service 看见 ROTATED，调用 revokeFamily，101 和 102 都写入 REPLAY 与撤销时间。于是刚拿到的新令牌也不能继续使用。保留旧记录正是为了认出这种情况。

<a id="auth-service"></a>

## AuthServiceImpl.java：逐个方法读业务流程

文件：[AuthServiceImpl.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/service/impl/AuthServiceImpl.java)。

### 20. 类声明与七个依赖

`@Service` 让 Spring 管理该类，`implements AuthService` 实现接口约定。它注入下面七个对象：

| 字段 | 负责什么 | 典型调用 |
| --- | --- | --- |
| userMapper | users 表读写 | 查询账号、插入用户 |
| sessionMapper | sessions 表读写 | 插入、锁定、轮换和撤销 |
| passwordEncoder | 密码哈希与校验 | encode、matches |
| jwtUtil | 访问令牌 | 创建、校验 JWT |
| tokenGenerator | 刷新令牌 | 生成随机值、计算 SHA-256 |
| properties | 认证配置 | 读取有效期 |
| clock | 当前时刻 | clock.instant() |

另外的 `dummyPasswordHash` 是本类内部保存的占位哈希，不是注入的依赖，也不存数据库。

### initializeDummyPassword()：启动时准备占位密码哈希

`@PostConstruct` 表示 Spring 完成该 Bean 的依赖注入后执行初始化。这里将随机 UUID 字符串 encode，存入 dummyPasswordHash。

用户不存在时仍做一次密码比较，可以减少“不存在的用户立即返回，存在的用户花时间算哈希”的明显耗时差异。它不会注册一个假用户，也不保证所有请求耗时完全一样。这个方法在 Bean 初始化时运行，不是每次登录都运行。

### register(RegisterDTO dto)：创建普通用户

1. `selectByUsername(dto.getUsername())`：若已有记录，`throw usernameExists()`，返回重名错误。
2. 创建 User，设置用户名和密码哈希。原始 password 只传入 encode，不写入 User。
3. 后端设置 `role="USER"`、`status="ACTIVE"`。角色不取自客户端，避免公开注册产生管理员。
4. 设置 createdAt 为当前 UTC 时刻，updatedAt 使用同一个值。
5. `userMapper.insert(user)` 写入一行，数据库回填 id。
6. 返回 `new RegisterVO(user.getUsername())`。这里没有创建 Session，所以注册成功还不等于登录成功。

为什么查询后插入，还要捕获 `DuplicateKeyException`？两个并发请求可能都先查到“用户名不存在”，随后一起插入。最终由数据库唯一约束挡住重复；catch 把数据库重复键异常转换为统一的 409，而不是向前端暴露 SQL。

方法上的 `@Transactional` 让数据库操作在一个事务里执行。正常返回提交；本项目默认规则下，未处理的运行时异常会回滚。

### login(LoginDTO dto)：检查密码，创建一次新的登录

第一步查询 User，然后这行选择要比较的哈希：

```java
String hash = user == null ? dummyPasswordHash : user.getPasswordHash();
boolean matches = passwordEncoder.matches(dto.getPassword(), hash);
```

它可理解为：有用户就用真实哈希，没有用户就用占位哈希；两种情况都做一次 matches。matches 的结果保存在 boolean 中，后面再合并检查：

```java
if (user == null || !matches || !"ACTIVE".equals(user.getStatus())) {
    throw new BusinessException(...);
}
```

分别拒绝不存在、密码不匹配、账号不可用。`||` 短路保证 user 为 null 时不会继续调用 `user.getStatus()`。统一返回 INVALID_CREDENTIALS，避免登录接口逐项公开账号信息。

校验通过后：

1. 生成一个新的明文 refreshToken，只准备返回客户端。
2. 获取 createdAt，生成 `UUID.randomUUID().toString()` 作为新 familyId。
3. `createdAt.plusDays(properties.getSessionDays())` 算整组截止时间。plusDays 返回一个新时间值，不修改 createdAt 本身。
4. 调用 createSession 写入记录，得到已回填 id 的 Session。
5. 调用 sessionVO，签发 accessToken，组合返回数据。

每次独立登录产生新的 family，即使是同一账号。当前没有“登录新设备自动踢掉其他设备”的逻辑。`@Transactional` 也保证保存记录后若生成返回结果发生未处理的运行时异常，记录不会作为成功登录被提交。

### refresh(String refreshToken)：只用刷新令牌，生成下一代

这是本模块最需要分段读的方法。它不会重新比较用户名密码，也不是给原 JWT 修改一个到期字段；它创建新的 JWT 和随机刷新令牌。

#### 第一步：定位并锁定会话

调用 `lockSession(refreshToken)`，返回锁后重新读取的记录。`now()` 得到 currentTime。如果查不到，或 `!expiresAt.isAfter(currentTime)`，抛出 expired。

`isAfter` 是严格“晚于”，所以截止时间恰好等于当前时间，也算过期。

#### 第二步：检查撤销状态

如果 `revokedAt != null`，该令牌已经不能刷新。如果原因是 ROTATED，说明这是一个已用过的旧令牌再次出现，调用 revokeFamily(..., "REPLAY", ...) 撤销整组，再返回 401。其他已撤销原因直接返回 401，不创建后继。

这里的“重放”是再次提交已经轮换的旧令牌。它可能来自攻击者，也可能来自前端意外发出的重复刷新；后端当前策略统一撤销整组，前端必须控制并发刷新。

#### 第三步：检查账号和剩余期限

查询所属 User。用户不存在或不再 ACTIVE 时，先把这组标记 DISABLED，再返回 401。

`Duration.between(currentTime, expiresAt).getSeconds()` 算剩余整秒数。小于 60 秒就要求重新登录，避免生成不符合当前 SessionVO 最小有效期约定的令牌。

#### 第四步：新增后继，撤销旧代

生成 nextToken，调用 createSession。这里使用旧记录的 familyId 和 expiresAt，只有 createdAt 取新时刻。因此刷新不会把 30 天重新计时。

随后调用 `markRotated(oldId, nextId, currentTime)`。影响行数不是 1 时抛出 IllegalStateException。这个异常会导致事务回滚：不能留下“新记录保存了，但旧记录没有被正确撤销”的半套结果。

最后 `sessionVO(user, next, nextToken, currentTime)` 返回新的两个令牌。旧 sid 已撤销，旧 accessToken 即使还没有达到 JWT 的 exp，也会被 authenticate 拒绝。

#### 为什么 noRollbackFor 不能略过

方法使用 `@Transactional(noRollbackFor = BusinessException.class)`。BusinessException 是 RuntimeException，如果沿用默认规则，“识别重放 → 撤销整组 → 抛 401 异常”会把刚做的撤销回滚掉，新的令牌仍可能有效。

当前例外规则让这类业务异常不触发回滚，因而 REPLAY/DISABLED 撤销能提交；数据库运行时故障、IllegalStateException 等仍按默认规则回滚。这个规则是为该方法的具体分支设置的，不是要求所有业务方法都忽略业务异常。事务规则可对照 [Spring 的回滚说明](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html)。

HTTP 401 本身不决定数据库回滚。事务在 Service 层依据异常类型处理，HTTP 状态由外层异常处理代码生成。

### logout(String refreshToken)：撤销一个 family，允许重试

先调用 lockSession。查到记录，就调用 revokeFamily(..., "LOGOUT", now())；查不到就正常结束。

没有返回业务对象，所以声明 void。Controller 返回空对象 `{}`。已知令牌的重复登出、未知但格式合法的令牌均成功，体现“重复调用后仍是退出状态”的幂等行为；空令牌仍会在 DTO 校验时被拒绝。

这个方法不要求有效 accessToken；过期的 accessToken 不妨碍提交刷新令牌退出。历史 ROTATED 记录也能定位 family，因此旧刷新令牌仍能退出这一组。只撤销，不立即删行，保留历史用于后续识别。

### authenticate(String accessToken)：每个私有请求的身份检查

AuthFilter 调用这个方法，正常时返回数据库 User。

1. JwtUtil.parseAccessToken 先校验签名、签发方、类型和有效期，取出 userId、sessionId。
2. 按 sessionId 查询 Session。
3. 拒绝记录不存在、所属用户不匹配、已撤销、整组已到期。
4. 再按 userId 查询 User，拒绝不存在或非 ACTIVE。
5. 返回当前 User，让过滤器判断最新 role。

`session.getUserId() != identity.getUserId()` 一侧是 Long、一侧是 long，Java 会拆箱后比较数值。这里不是两个 Long 对象引用的比较。左边 session == null 的短路检查保证后面的字段访问安全。

JWT 不是唯一的授权依据：数据库会话和用户状态也要通过。当前实现属于“有数据库登录状态的 JWT 认证”，不能当作完全不查数据库的无状态 JWT 方案。

角色不放进 JWT 作为长期依据，管理员权限变更后，下次请求会重新读取数据库角色。此方法没有插入 Session，不会让每个请求新增登录记录。

### lockSession(String token)：为什么有三次查询

```java
String hash = tokenGenerator.hash(token);
Session found = sessionMapper.selectByRefreshHash(hash);
if (found == null) { return null; }
if (sessionMapper.lockFamily(found.getFamilyId()) == null) { return null; }
return sessionMapper.selectByRefreshHashForUpdate(hash);
```

第一查定位令牌所属 family；第二查争用整组共同的首记录锁；第三查在等待结束后重新读取当前令牌状态。第一查不能直接作为最终判断依据，因为等待期间另一个事务可能已经轮换它。

在 MySQL 可重复读隔离级别下，普通查询可能继续看到早先的快照；第三查使用锁定读，检查最新状态。这样第二个刷新请求能发现旧令牌已变成 ROTATED，而不会再生成一个有效后继。

这几个 private 方法本身没有开启新事务。它们在外层 refresh/logout 的事务中执行，共享同一事务；提交或回滚由外层方法结束时处理。

### createSession(...)：填实体，存哈希，获得自增 ID

参数分别是 userId、familyId、明文刷新令牌 token、createdAt、expiresAt。方法创建 Session，逐个 set 对应字段，只把 `tokenGenerator.hash(token)` 放入实体，再调用 insert，最后返回 Session。

原始 token 不存表，必须在 login/refresh 的局部变量里保留，用于本次响应。insert 回填的 id 用来签发 JWT。

### sessionVO(...)：限制访问期限并组装响应

关键计算：

```java
int seconds = (int) Math.min(properties.getAccessTokenSeconds(),
        Duration.between(currentTime, session.getExpiresAt()).getSeconds());
```

`Math.min` 取两个值中较小的：配置的访问期限，和这次登录整组剩余期限。例如通常返回 691200（8天）；若组只剩 120 秒，只签发 120 秒，不能让 accessToken 超过整组截止时间。

Duration 的秒数是 long，`(int)` 是显式转换。这里取最小值后最多是配置允许的 691200，不会因为 30 天的秒数转换而超出 int 范围。

然后把 userId、session.id、UTC Instant 和 seconds 传给 JwtUtil，得到 accessToken；把 User 转为 UserVO；最后构造 SessionVO。它没有查询数据库，数据库工作已经由调用方完成。

### now()：统一 UTC，并对齐毫秒精度

`clock.instant()` 取时刻，`truncatedTo(ChronoUnit.MILLIS)` 截到毫秒，`LocalDateTime.ofInstant(..., ZoneOffset.UTC)` 转为按 UTC 表示的本地日期时间，配合数据库 DATETIME(3)。LocalDateTime 本身不存时区，UTC 是这里明确采用的约定。

sessionVO 中反向使用 `currentTime.toInstant(ZoneOffset.UTC)`，把数据库时间表示转回 JWT 需要的时刻。

### expired() 和 usernameExists()：集中创建业务异常

expired 返回 401 / SESSION_EXPIRED；usernameExists 返回 409 / USERNAME_EXISTS。它们返回 BusinessException 对象，方法本身没有抛出，调用处的 `throw expired()` 才真正抛出。

集中创建能避免多个分支手写不一致的错误码和消息。

<a id="current-user"></a>

## UserServiceImpl.java：当前用户 ID 从哪里来

文件：[UserServiceImpl.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/service/impl/UserServiceImpl.java)。

`@Service`、`implements UserService` 和 `@Autowired UserMapper` 的作用与上面相同。只有一个业务方法 getCurrentUser：

1. `BaseContext.getCurrentId()` 读取过滤器已经放入当前线程的用户 ID。
2. `userId == null ? null : userMapper.selectById(userId)`：有 ID 才查询，否则让 user 为 null。
3. user 不存在或状态不是 ACTIVE，返回登录失效。
4. 将 id 转字符串，取 username和字符串balanceCents，构造 UserVO。

过滤器已经查询过用户，这里再次读取是当前实现的明确行为，不是同一次 Java 查询结果自动流入 Service。最终返回的数据来自当前查询，并且不包含 passwordHash、role、status。

为了读懂它，先记住 BaseContext 是“本次请求线程临时保存用户 ID 的地方”；后文解释为什么处理完必须清理。

<a id="token-tools"></a>

## 令牌工具类：生成、编码、哈希、签名分别是什么

### 21. TokenGenerator.java：随机刷新令牌和查询指纹

文件：[TokenGenerator.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/util/TokenGenerator.java)。

`@Component` 使它可被注入。字段 `private final SecureRandom random = new SecureRandom()` 保存安全随机数生成器；final 表示该字段不能再赋值为另一个生成器，不表示这个对象不能继续生成随机数。

generateRefreshToken 的三步是：

```java
byte[] bytes = new byte[32];
random.nextBytes(bytes);
return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
```

byte[] 是字节数组，32 字节提供 256 位随机值。nextBytes 用随机内容填充数组；URL 兼容 Base64 把字节转为字符串；withoutPadding 去掉末尾的 `=` 补位。当前结果为 43 个字符，方便放入 JSON 和客户端存储。

编码不等于加密。刷新令牌的不可预测性来自随机字节，不来自 Base64。它也不含 userId、角色或到期时间，业务信息通过其数据库记录确定。

hash(String token) 的步骤是：

1. `token.getBytes(StandardCharsets.UTF_8)` 把字符串按明确编码转字节。
2. `MessageDigest.getInstance("SHA-256")` 取得算法，digest 计算 32 字节摘要。
3. `HexFormat.of().formatHex(digest)` 把摘要转为 64 字符十六进制字符串，对应 CHAR(64)。

同一令牌每次 hash 结果相同，才能拿它查唯一索引。这里不加密码那样的随机盐：对象是高熵随机令牌，目标是生成可查询指纹；用户自己选择的密码另用有盐、计算更慢的 PBKDF2。

catch 捕获 `NoSuchAlgorithmException`，转为 IllegalStateException，表示运行环境缺少所需算法。这不是“用户输入了错误令牌”，不能当作普通 401 分支。

### 22. JwtUtil.java：访问令牌的签发和校验

文件：[JwtUtil.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/util/JwtUtil.java)。

#### 构造器：启动时准备签名密钥

这里保留构造器注入：Spring 创建 JwtUtil 时，把 AuthProperties 和 Clock 传入唯一构造器，不必另加字段上的 `@Autowired`。两个 final 字段 key、clock 在构造时赋值。

先解码配置的 Base64 密钥；格式不正确抛 IllegalStateException，解码后不足 32 字节也拒绝启动；然后 `Keys.hmacShaKeyFor(bytes)` 生成供 JJWT 使用的 SecretKey。

密钥用于签名和验证，只有后端持有。它不是某个用户的密码，也不是前端环境里填写的 bearerToken。访问令牌更换不要求重新生成服务器密钥。

#### createAccessToken(...)：把身份和期限签成 JWT

方法接收 userId、sessionId、签发时刻 now、有效秒数 seconds。链式调用逐项设置：

| 调用 | JWT 中的含义 |
| --- | --- |
| issuer("zlowallpaper") | iss，签发方 |
| subject(Long.toString(userId)) | sub，所属用户 ID，字符串形式 |
| claim("sid", ...) | 自定义字段，指向 sessions.id |
| claim("kind", "access") | 自定义类型标记，表明是访问令牌 |
| issuedAt(Date.from(now)) | iat，签发时间 |
| expiration(Date.from(now.plusSeconds(seconds))) | exp，到期时间 |
| signWith(key, Jwts.SIG.HS256) | 使用配置密钥和 HS256 签名 |
| compact() | 生成最终 JWT 字符串 |

链式写法可以理解为“创建 builder → 逐项调用 setter 式方法 → 最后生成字符串”，每一段并没有在发网络请求。`Date.from(Instant)` 是把 Java 时间类型转换成库所接收的 Date。

JWT 通常有 `header.payload.signature` 三部分。payload 是可解码的数据，不是保密加密文本；签名用来检测内容被改动。当前代码没有把密码、刷新令牌或角色放入 payload。

#### parseAccessToken(String token)：先验证，再提取身份

`Jwts.parser()` 构造校验器：verifyWith 指定验签密钥，requireIssuer 检查签发方，require("kind", "access") 检查用途。build().parseSignedClaims(token) 解析签名 JWT，并检查签名和到期时间。

`.clock(() -> Date.from(clock.instant()))` 给库提供取当前时间的方法。`() -> ...` 是一个没有参数的 Lambda，库需要时间时调用它；这里没有循环或定时自动刷新。可以暂时把它读成“使用我们注入的 Clock 来校验到期时间”。

解析得到 `Jws<Claims>`：Jws 表示签名 JWT，Claims 是 payload 中的声明集合。代码再确认算法为 HS256，且存在 expiration；取 subject 和 sid，`Long.parseLong(...)` 转为 long，并要求两个 ID 都大于 0；最后构造 AccessIdentity。

`catch (JwtException | IllegalArgumentException exception)` 用竖线表示捕获这两类异常，例如过期、签名失败或 ID 不是数字，统一转为登录失效。主动抛出的 BusinessException 也向外传递为相同业务错误。

这个工具只校验令牌本身。它不知道数据库里记录是否撤销、账号是否禁用，后续仍由 authenticate 查表确认。

### 三个容易混淆的操作

| 操作 | 本项目使用位置 | 是否能直接恢复原数据 |
| --- | --- | --- |
| Base64 编码 | 刷新令牌字符表示、密钥配置、JWT 编码 | 可解码，不能当作保密加密 |
| 哈希 | PBKDF2 密码、SHA-256 刷新令牌指纹 | 不提供反向恢复；密码验证用 matches |
| JWT 签名 | accessToken | 检测修改、确认签名；不隐藏 payload |

<a id="filters"></a>

## 请求到 Controller 之前：过滤器、上下文与异常响应

### 23. AuthFilter.java：登录与管理员权限的统一入口

文件：[AuthFilter.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/filter/AuthFilter.java)。

它 `extends OncePerRequestFilter`，由 Servlet 过滤器链调用，在正常请求分发中避免自身重复执行。`@Component` 注册组件；`@Order(HIGHEST_PRECEDENCE + 2)` 使它排在请求编号与 CORS 过滤器后面。

两个依赖分别是 AuthService（核验身份）和 ObjectMapper（在过滤器中直接输出 JSON）。静态常量 PUBLIC_AUTH_PATHS 保存四个认证入口，Set.of 创建固定集合，contains 判断某个路径是否在里面。

#### doFilterInternal(...)：按执行顺序读

参数 request 代表请求，response 代表响应，chain 代表剩余过滤器和后面的 Controller 处理链。throws ServletException/IOException 是过滤器方法的异常声明。

1. 先 `BaseContext.removeCurrentId()`，清掉当前线程可能留下的用户信息。
2. 从 request 获取路径：`getRequestURI()` 取 `/api/v1/me`，`getContextPath()` 取 `/api/v1`；substring 去掉前缀，得到 `/me`。Query 参数不在这里的 URI 路径中。
3. 调用 isPublic。公开请求直接继续；私有请求才做下面的认证。
4. `getHeader("Authorization")` 读取请求头，要求以 `Bearer ` 开头，并限制总长度不超过 4096。
5. `header.substring(7)` 去掉六个字母和一个空格，只把实际访问令牌传给 authenticate。
6. 认证成功得到 User；若路径是 `/admin` 或以 `/admin/` 开头，额外要求 role=ADMIN，否则 403。
7. 把可信 user.id 放入 BaseContext，再执行 `chain.doFilter(request, response)`，让请求进入后续处理。
8. 无论后续成功、失败或前面提前 return，finally 都清理用户 ID。

`regionMatches(true, 0, "Bearer ", 0, 7)` 表示忽略大小写，比较 header 从第 0 位开始的 7 个字符与 `Bearer `；它不区分 Bearer/bearer，但不会把 refreshToken 自动变成 accessToken。缺少正确头时提示“请先登录”，令牌本身无效时 authenticate 返回登录失效。

catch BusinessException 将认证失败写入响应并 return，不再进入 Controller。catch DataAccessException 在认证查表失败时返回 503，避免把数据库故障当成密码错误。日志带 requestId，方便定位请求。

finally 的意义尤其重要：Tomcat 会复用线程。当前请求完成后，不能让下一个请求继承上一个用户的身份。方法执行到 return 也会先执行 finally。

#### isPublic(String method, String path)：具体放行哪些请求

| 判断 | 为什么放行 |
| --- | --- |
| OPTIONS | 浏览器 CORS 预检不需要先登录 |
| 四个 /auth 路径 | 注册、登录、刷新、退出各有自身参数校验；刷新/退出需要能在 accessToken 过期时调用 |
| GET/HEAD /health | 服务检查 |
| GET/HEAD /categories、/wallpapers | 匿名浏览分类和商品 |
| GET/HEAD `/wallpapers/[^/]+` | 一层商品 ID 的详情查询 |
| GET/HEAD `/catalog/covers/[^/]+` | 公开商品封面 |

`[^/]+` 是“一个或多个非斜杠字符”；String.matches 匹配整个字符串，因此 `/wallpapers/123` 通过，`/wallpapers/123/download-grants` 不通过。详情传什么 ID 是否能找到商品，仍由 catalog 业务判断。

`/wallpapers/` 没有 ID，不符合公开路径模式，会落入默认认证检查。这解释了 Apifox 详情请求不填写 id 时可能先出现 401；不能因此推断正常商品详情必须登录。

其他路径默认保护。四个认证路径的放行判断没有限制请求方法，但 Controller 仍只接收指定 POST；过滤器放行不等于存在那个路由或支持所有 HTTP 方法。

公开路径不会调用 authenticate，即使携带过期访问令牌，也不会由这里阻止匿名浏览。未来公开商品接口需要返回个性化字段时，需另行设计可选身份处理，当前没有这一步。

#### writeError(...)：过滤器为什么自己写 JSON

它设置 HTTP status、application/json、UTF-8，再用 ObjectMapper.writeValue 把 ApiResponse.failure 写到 response.getWriter()。

过滤器的认证检查发生在 Spring MVC Controller 之前，这里的异常不会自动进入 Controller 的 `@RestControllerAdvice`，所以自行处理。它和全局异常处理使用同样的响应外壳，但处理的位置不同。

### 24. BaseContext.java：当前线程的临时身份

文件：[BaseContext.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/common/BaseContext.java)。

这个类对应苍穹外卖里常见的当前用户上下文。`ThreadLocal<Long>` 让每个线程分别保存一个 Long，不是所有用户共用一个全局 ID。

| 方法 | 动作 |
| --- | --- |
| setCurrentId | 把本次认证后的 ID 放入当前线程 |
| getCurrentId | Service 从当前线程取 ID |
| removeCurrentId | 请求处理完删除当前线程中的值 |

类是 final，不能继承；构造器 private，不能从外部 new；三个方法 static，直接通过类名调用。ThreadLocal 字段虽然 static，各线程内部保存的值仍独立。

它只在后端内存里保存本次处理所需的 ID，不会写 sessions 表，也不会发送给前端。当前 Servlet 同步调用链使用同一线程；以后若主动切换线程执行异步工作，不能默认认为普通 ThreadLocal 会自动传播。

### 25. RequestIdFilter.java：给每次 HTTP 请求编号

文件：[RequestIdFilter.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/common/RequestIdFilter.java)。

它排在最前面。doFilterInternal 生成 UUID，通过 `request.setAttribute(ATTRIBUTE, requestId)` 存在请求对象中，通过 `response.setHeader("X-Request-Id", requestId)` 写入响应头，之后继续 chain。

ATTRIBUTE 是属性名，使用类全名加 `.requestId` 避免和其他代码冲突。current(request) 从请求属性取出 Object，`(String)` 转回字符串，供 Controller、异常处理和日志使用。

setAttribute 是服务端在当前请求对象上存数据；setHeader 是发送 HTTP 响应头；二者不是同一件事。requestId 既不是 users.id，也不是 sessions.id，更不是登录令牌，只用于查找同一次请求的记录。

### 26. WebConfig.java：浏览器跨域处理

文件：[WebConfig.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/config/WebConfig.java)。

corsFilter 方法读取 `wallpaper.cors.allowed-origins`。split 把逗号分隔的来源拆开，for 循环跳过空项、strip 去两端空白，再逐项加入允许列表。

配置允许的方法包含 GET、POST、DELETE 等；允许请求头包含 Authorization、Content-Type；暴露 X-Request-Id 供浏览器 JavaScript 读取。这里只是 CORS 配置允许某种方法或头，不表示对应业务接口已经实现。

`allowCredentials(false)` 表示不启用跨域 Cookie 等浏览器凭据模式；当前由客户端显式设置的 Bearer 请求头仍可以使用，因为 Authorization 已列入允许头。`maxAge(600L)` 中 L 表示 long，600 秒是预检结果的缓存期限，不是令牌期限。

`registerCorsConfiguration("/**", configuration)` 对所有路径应用规则；FilterRegistrationBean 注册 CorsFilter，并设置顺序为 HIGHEST_PRECEDENCE+1，所以合法来源的认证 401/403 也能保留跨域响应头。

工作台 4173 和后端 8080 属于不同来源，需要这份配置。Apifox 桌面请求通常不受浏览器同源限制，不能只靠它证明浏览器跨域也正常。

正常请求进入顺序是：

```text
RequestIdFilter → CorsFilter → AuthFilter → Spring MVC → Controller → Service
```

CORS 预检可能由 CorsFilter 直接处理并返回，不一定走到后面的 Controller。

### 27. ApiResponse.java：业务数据外面的统一 JSON 外壳

文件：[ApiResponse.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/common/ApiResponse.java)。

现有基础类使用 Java record，声明四个字段 code、message、data、requestId，编译器生成构造器及访问方法。record 的访问方法是 `code()` 等，与普通类的 `getCode()` 命名不同；这里不需要 Lombok。

`<T>` 是泛型，表示 data 的类型由调用方决定。`ApiResponse<UserVO>` 的 data 是 UserVO，`ApiResponse<SessionVO>` 的 data 是 SessionVO。

success(data, requestId) 构造 code=OK、message="" 的响应；failure(code, message, requestId) 构造 data=null 的响应，`Void` 表示没有业务数据。它不决定 HTTP 状态：注册的 OK 可以对应 201，登录的 OK 对应 200，错误状态由外层代码设置。

### 28. BusinessException.java：能向用户说明的业务失败

文件：[BusinessException.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/common/BusinessException.java)。

它继承 RuntimeException，保存 HttpStatus 和业务 code；构造器 `super(message)` 将消息交给父类，因此能调用继承的 getMessage()。getStatus/getCode 是这里手写的读取方法。

例如 usernameExists 提供 409、USERNAME_EXISTS、中文说明；不是把数据库重复键异常直接返回。RuntimeException 的类型也决定默认事务回滚行为，所以 refresh 才需要特定例外规则。

### 29. GlobalExceptionHandler.java：Controller 流程的错误出口

文件：[GlobalExceptionHandler.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/common/GlobalExceptionHandler.java)。

`@RestControllerAdvice` 让它统一处理 MVC 请求中的异常。每个 `@ExceptionHandler(...)` 声明处理哪些异常，方法本身不是一个公开 HTTP 接口。

| 方法 | 处理的情况 | 返回 |
| --- | --- | --- |
| malformedJson | JSON 语法或字段类型不能读取，包含严格字符串解析失败 | 400 VALIDATION_ERROR |
| unsupportedMediaType | 请求体媒体类型不符合要求 | 415 VALIDATION_ERROR |
| business | Service 抛出的 BusinessException | 使用异常携带的状态、code、message |
| validation | DTO 校验、参数绑定、类型或缺少参数等错误 | 400 VALIDATION_ERROR |
| unavailable | 指定的数据库连接或资源访问异常 | 503 SERVICE_UNAVAILABLE |
| notFound | MVC 找不到资源 | 404 RESOURCE_NOT_FOUND |
| methodNotAllowed | 路径支持的 HTTP 方法不匹配 | 405 METHOD_NOT_ALLOWED |
| unexpected | 其他未处理异常 | 500 INTERNAL_ERROR |
| error | 上述方法共用的 private 辅助方法 | ResponseEntity 设置状态，ApiResponse.failure 设置 JSON |

validation 中的 `exception instanceof BindException binding` 同时判断类型并把它命名为 binding，随后读取错误清单。它的 Stream 可以按下面的动作读：

1. getAllErrors 取得全部校验/绑定错误，stream 开始遍历。
2. filter 排除 FieldError 中的底层绑定失败，保留可用的校验说明，避免直接回显原始输入。
3. map 取得每个错误的 defaultMessage；`ObjectError::getDefaultMessage` 是方法引用。
4. findFirst 只取第一个，orElse 在没有可用错误时使用通用消息。

逻辑上相当于“for 遍历，找到第一个合适的说明就用它”。这里是现有基础设施写法，本次只讲解，不改成另一套代码。

`ResponseEntity<ApiResponse<Void>>` 有两层：ResponseEntity 控制 HTTP 状态和响应，ApiResponse 控制 JSON 字段。日志记录详细异常并带 requestId，前端收到的是受控消息，不是 SQL、连接信息或堆栈。

<a id="tasks-tests"></a>

## 清理任务、建表脚本与测试代码

### 30. SessionCleanupTask.java：删除超过保留期限的记录

文件：[SessionCleanupTask.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/task/SessionCleanupTask.java)。

类上 @Component 注册任务对象，注入 SessionMapper、AuthProperties、Clock。唯一方法 cleanExpiredSessions 被下面的注解调度：

```java
@Scheduled(cron = "${wallpaper.auth.cleanup-cron:0 0 3 * * *}", zone = "UTC")
```

`${配置名:默认值}` 优先读配置，没有提供时用默认 cron。Spring cron 的六位是秒、分、时、日、月、星期，当前默认是每天 UTC 03:00，即北京时间 11:00。配置为 `-` 关闭调度，测试环境使用这个值。

方法取当前 UTC LocalDateTime，minusDays(retentionDays) 计算 cutoff，调用 deleteExpired。假设今天是 10 月 11 日同一时刻，保留 7 天，cutoff 为 10 月 4 日同一时刻；截止时间严格早于它的记录才删除，刚过期两天的仍保留。

它不调用 refresh，不创建新令牌，不删除用户。登录是否过期始终由请求时的校验决定，不必等每天清理执行。`cleanup-cron` 直接通过注解读取配置，因此 AuthProperties 没有相应 Java 字段也能使用它。

### 31. 002-create-sessions.sql：字段之外还有哪些约束

文件：[002-create-sessions.sql](E:/zlowallpaper_sever/database/002-create-sessions.sql)。现有 users 定义在 [001-create-business-tables.sql](E:/zlowallpaper_sever/database/001-create-business-tables.sql)，阅读时只需先关注 users 那部分。

001 中 users 的 `uk_users_username` 是用户名唯一约束，也是 register 捕获重复键的数据库依据；表的 `utf8mb4_0900_ai_ci` 排序规则使用户名比较不区分大小写。角色和状态列使用 ascii_bin，并分别通过 CHECK 限制为 USER/ADMIN、ACTIVE/DISABLED，和 Service 中的大写常量对应。

created_at、updated_at 提供 UTC_TIMESTAMP(3) 默认值，但注册 SQL 已显式传入时间，因此使用 Service 给出的值。updated_at 由应用维护，不是每次 SELECT 都自动改变。用户名长度也有 CHECK；它不能替代 DTO 的字符格式校验，两层负责的规则并不完全相同。

002 先 `USE zlowallpaper` 选择数据库，再 CREATE TABLE sessions。它是增量建表脚本，不含清空用户操作，也不会由应用自动反复执行；本机已经执行过，不必在阅读时再次运行。

| SQL 定义 | 目的 |
| --- | --- |
| BIGINT AUTO_INCREMENT、PRIMARY KEY(id) | 生成并唯一标识每一代记录 |
| family_id CHAR(36)、ascii_bin | 保存 UUID；按精确字符比较 |
| refresh_token_hash CHAR(64)、ascii_bin | 保存 64 位十六进制指纹；精确匹配 |
| DATETIME(3) | 保存毫秒精度时间，应用按 UTC 约定读写 |
| UNIQUE(refresh_token_hash) | 同一令牌指纹至多对应一条记录 |
| UNIQUE(replaced_by_id) | 非空的下一代编号不能被多个旧记录重复引用；MySQL 允许多条记录在此列为 null |
| INDEX(family_id, id) | 支持定位 family 并按 id 查最早记录 |
| INDEX(user_id, expires_at) | 支持按用户与期限定位记录 |
| INDEX(expires_at) | 支持清理时筛选截止时间 |
| FOREIGN KEY(user_id) → users(id)，ON DELETE RESTRICT | 会话必须属于存在的用户；有会话关联时不能直接删除用户 |
| CHECK(expires_at > created_at) | 截止时间必须晚于创建时间 |
| CHECK 撤销字段组合 | 未撤销时三个字段为空；撤销时须有时间和合法原因 |
| ENGINE=InnoDB | 使用支持事务和行锁的存储引擎 |

撤销 CHECK 中的 OR 允许两种状态。第二种状态不要求 replaced_by_id 必须有值：LOGOUT、REPLAY、DISABLED 不一定是在生成后继时发生。ROTATED 的后继关系由 Service 事务维护。

replaced_by_id 没有建自引用外键，这是当前设计选择：由应用维护链条，方便整组清理。它也不存下一代的明文令牌，存的是数据库记录编号。

### 32. ZloWallpaperApplication.java：为什么这些组件能被找到

文件：[ZloWallpaperApplication.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/ZloWallpaperApplication.java)。

main 调用 SpringApplication.run 启动工程。`@SpringBootApplication` 包含自动配置和组件扫描，主类位于 `com.zlo.wallpaper` 根包，其下的 auth、config、common 等组件可以被扫描。

因此不是 main 手工 new AuthController、AuthServiceImpl、JwtUtil，再逐一传递；Spring 根据注解和配置创建它们，并注入依赖。这就是普通 Java 对象变成框架管理的 Bean 的入口。

### 33. AuthIntegrationTest.java：真实各层加内存数据库的集成测试

文件：[AuthIntegrationTest.java](E:/zlowallpaper_sever/src/test/java/com/zlo/wallpaper/auth/AuthIntegrationTest.java)。

先看类顶部四个注解：

| 注解 | 在这份测试中的作用 |
| --- | --- |
| @SpringBootTest | 加载真实 Spring 应用组件；这里额外指定 auth 专用 H2 连接 |
| @AutoConfigureMockMvc | 提供 MockMvc，模拟 HTTP 请求经过过滤器和 MVC，无需另外启动监听端口 |
| @ActiveProfiles("test") | 使用测试配置，覆盖本机数据库和密钥来源 |
| @Sql(... BEFORE_TEST_CLASS) | 测试类开始前执行 auth-schema.sql，建立测试表 |

连接串里的 `mem:auth` 表示内存库，MODE=MySQL 尽量兼容 MySQL SQL 语法，DB_CLOSE_DELAY=-1 保持该内存库，LOCK_TIMEOUT=10000 设置锁等待超时。H2 不能完全代表 MySQL 的并发行为，所以还有下一份真实 MySQL 测试。

注入对象中，MockMvc 发请求，ObjectMapper 生成和读取 JSON，JdbcTemplate 直接核对表内数据，AuthService/JwtUtil/SessionCleanupTask 用于测试认证、特定令牌和清理分支。

clearDatabase 上的 `@BeforeEach` 在每项用例前清空这份 H2 测试库的 sessions、users，并清理线程上下文。先删子表避免外键阻止删用户。它不连接本机商城库；不能把这几行全表删除 SQL 当作开发库整理方法。

#### 每个测试方法验证什么

| 方法 | 请求/操作与检查 |
| --- | --- |
| registrationStoresHashAndOrdinaryRoleWithoutLoggingIn | 注册 201；响应和头的 requestId 相同；即使传 ADMIN 也只创建 USER；存哈希、不建会话；大小写重名 409 |
| loginAndMeMatchContractAndDoNotLeakInternalFields | 登录默认691200秒，JWT的exp−iat也为8天；用户ID为字符串；刷新令牌仅存64字符哈希；/me只返回三字段；请求结束上下文为空 |
| wrongPasswordUnknownAndDisabledAccountsCannotLogin | 三种失败均为 401 INVALID_CREDENTIALS，不产生会话 |
| invalidUsernamesAreRejected | @ValueSource 的四组无效用户名分别运行，全部 400 |
| invalidPasswordsAreRejected | 短密码与全空白密码两组，全部 400 |
| longUnicodePasswordsWorkAndPasswordWhitespaceIsPreserved | 128 个 emoji 可注册登录，129 个拒绝；带首尾空格的密码只能按原样登录 |
| missingAndMalformedJsonReturnSafeValidationErrors | 缺字段、坏 JSON、空令牌、数字密码/用户名、布尔令牌均被拒绝 |
| expiredAccessCanRefreshAndOldRefreshCanLogoutTheWholeFamily | 携带已过期访问令牌仍能用刷新接口；旧刷新令牌能退出整个 family |
| accessTokenCannotClaimAnotherUsersSession | 用户 ID 和 sid 所属账号不匹配时拒绝；两种令牌不能混用 |
| refreshRotatesBothTokensAndKeepsFixedFamilyExpiry | 两个令牌都改变；旧访问令牌失效；两条记录同 family、同截止时间，旧记录指向新记录 |
| replayRevocationCommitsDespite401AndDoesNotRevokeAnotherLogin | 重用旧刷新令牌返回 401，但整组撤销确实已提交；另一次独立登录仍有效 |
| logoutRevokesFamilyIsRetryableAndDoesNotNeedValidAccessToken | 退出后不能认证或刷新；重复/未知令牌退出 200；另一组登录不受影响 |
| expiredSessionAndJwtAndForgedTokenAreRejected | 分别验证 JWT 过期、签名被改、数据库会话过期与未知刷新令牌 |
| adminPermissionUsesCurrentDatabaseRoleAndStatus | 同一令牌对应当前数据库角色；USER 403，ADMIN 通过认证后因业务未实现而 404；禁用后的刷新撤销不会因重新启用恢复 |
| missingAuthAndCorsAndContextCleanupAreCorrect | 没令牌 401；合法工作台来源仍有 CORS 头；预检成功；上下文清理 |
| parallelRefreshCanNeverCreateTwoActiveDescendants | 两个线程同时刷新一个令牌；一个成功一个失败，重放导致整组无有效记录 |
| cleanupRetainsRotationHistoryUntilFamilyExpiryPlusRetention | 未到清理条件保留两代；过保留期删会话，保留用户 |

这里有 17 个测试方法，其中两个参数化方法分别跑 4 次和 2 次，因此共 21 次测试执行。`@Test` 跑一个用例，`@ParameterizedTest` 配合 `@ValueSource` 对每个值分别跑一遍。

#### 公共辅助方法怎样减少重复代码

| 方法 | 作用 |
| --- | --- |
| credentials(username, password) | Map.of 创建两字段请求数据 |
| refresh(JsonNode session) | 从登录数据取 refreshToken，构造刷新请求 |
| register(username) | 按标准密码发送注册并要求 201 |
| registerAndLogin(username) | 先注册再登录，返回登录数据 |
| login(username) | 发送登录请求并取响应 data |
| postJson(path, payload, expectedStatus) | 设路径前缀、contextPath、JSON 类型和请求体；检查 HTTP 状态，返回 MvcResult |
| getWithToken(path, token, expectedStatus) | 加 Authorization: Bearer 头发送 GET，检查状态 |
| body(result) | 把响应字节读成 JsonNode，便于逐字段断言 |
| assertCode(result, code) | 检查业务错误码和 data:null |
| concurrentRefresh(latch, token) | 等同步起跑后调用真实 Service；成功 true，SESSION_EXPIRED false |

`jsonPath("$.code")` 是检查 JSON 根对象的 code；JsonNode.path("data").path("user") 逐层取字段；asText/asInt 按类型读取；assertEquals/assertNotEquals/assertNull/assertThrows 分别断言相等、不相等、空值、会抛异常。`throws Exception` 是测试方法对请求和并发操作可能失败的声明。

#### 并发测试中的陌生类可以这样读

- ExecutorService 创建两个工作线程；submit 提交一段待执行的代码。
- CountDownLatch 初始为 1，两个任务 await 等待；主线程 countDown 把它降到 0，两者才开始争用同一个刷新令牌。
- Future<Boolean> 保存每个任务最终结果，get 最多等 15 秒取结果，避免失败时一直卡住。
- finally 中 shutdownNow 结束测试线程池，防止测试资源残留。

“一个刷新成功”不表示最后一定还有一个有效会话：第二个请求等待后识别旧令牌重放，把刚创建的后继也撤销。所以最后检查有效记录数为 0，和当前业务策略一致。

测试类没有用 `@Transactional` 包住整个测试再回滚；它需要验证 Service 的真实提交结果，尤其是返回 401 后 REPLAY 撤销仍然存在。

### 34. AuthMySqlIT.java：额外验证 MySQL 的事务与锁

文件：[AuthMySqlIT.java](E:/zlowallpaper_sever/src/test/java/com/zlo/wallpaper/auth/AuthMySqlIT.java)。

`@SpringBootTest(webEnvironment = NONE)` 加载 Service 和数据库组件，不启动 Web 服务器；`@ActiveProfiles("local")` 使用本机 MySQL；`@EnabledIfSystemProperty(...true)` 要求显式开关；额外配置关闭定时清理。

文件名以 IT 结尾，不属于当前默认 Surefire 的普通测试命名范围。前文命令同时指定 `-Dtest=AuthMySqlIT` 和 `-Dauth.mysql.tests=true`，才明确选择并启用这份真实库验证。

| 方法 | 内容 |
| --- | --- |
| registerOwnAccount | @BeforeEach 创建 UUID 命名的专属测试用户，保存 username/userId |
| removeOnlyOwnedFixtures | @AfterEach 先按 user_id 删自己的会话，再按 id 与 username 双条件删自己的用户 |
| roundTripAndReplayCommitOnMySql | 登录、刷新、旧令牌重放、独立登录不受影响、退出，检查真实提交结果 |
| concurrentRefreshHasOneWinnerAndRevokesReplayFamilyOnMySql | 同一旧令牌并发刷新，最终没有有效会话，确认 MySQL 上的锁顺序 |
| logoutRacingRefreshCannotLeaveActiveSessionOnMySql | 刷新与退出同时执行，无论先后，完成后不能留下有效后继 |
| refreshTogether | 等待同步起跑，调用刷新并转为成功/业务失败结果 |
| count | 用 JdbcTemplate 的参数化查询统计该 userId 的记录数 |
| login | 创建 LoginDTO、填写测试账号密码，直接调用 authService.login |

UUID 去掉横线并截取部分，拼出符合用户名规则的独立测试名，避免和业务账号撞名。删除用户的语句保留两个条件，避免只按一个可能误用的字段删除。

`assertThrows(BusinessException.class, () -> ...)` 表示执行括号中的调用并确认它抛出指定异常。这里出现 Lambda 和线程工具是为了构造并发测试情境，不是要求后续普通业务都采用这种写法。

### 35. auth-schema.sql：只供 H2 测试使用的表

文件：[auth-schema.sql](E:/zlowallpaper_sever/src/test/resources/auth-schema.sql)。

它先删除测试表，再建立 users、sessions，保留认证所需的字段、外键、唯一约束和状态检查。users 使用 H2 的 VARCHAR_IGNORECASE 模拟用户名大小写不敏感，时间用 TIMESTAMP(3)，family/id 索引支持锁相关查询。

它是 MySQL 结构的测试对应版本，不是正式库迁移脚本。DROP TABLE 的含义是删除表和数据，只能在这份明确的独立测试库流程中执行；本机已有表的升级使用 002。

### 36. application-test.yml：隔离自动测试环境

文件：[application-test.yml](E:/zlowallpaper_sever/src/test/resources/application-test.yml)。

它配置独立 H2、测试专用固定 JWT 密钥、关闭定时清理，并关闭 Redis repository 扫描。固定密钥用于重复测试，不能当成部署环境密钥。

通用测试数据源名是 mem:skeleton，AuthIntegrationTest 注解又把它覆盖为 mem:auth。因此看到两个不同名字并不是同时连接开发 MySQL。

### 37. 启动脚本与这份文档的边界

文件：[start-backend.ps1](E:/zlowallpaper_sever/scripts/start-backend.ps1)。它负责选择本机 Java/Maven、设置运行环境并启动后端，和 IDEA 点击运行主类是两种启动入口；注册与认证业务仍执行同一套 Java 代码。

修改密码、忘记密码重置、管理员账号置备尚未在这五个接口中实现。数据库里的密码哈希不能读回原密码；刷新令牌也不能用来改变账号密码。本导读解释现有实现，不把尚未开发的功能描述成已经具备。

<a id="request-traces"></a>

## 五条完整调用链：把各个文件接起来

### 注册：写 users，不写 sessions

```text
POST /api/v1/auth/register，JSON 包含 username/password
  → RequestIdFilter 生成编号，CORS 处理，AuthFilter 放行注册路径
  → Jackson 读取字符串，RegisterDTO 继承的规则做校验
  → AuthController.register
  → AuthServiceImpl.register
  → UserMapper.selectByUsername：检查重名
  → PasswordEncoder.encode：生成密码哈希
  → UserMapper.insert：保存 USER/ACTIVE，回填用户 ID
  → RegisterVO → ApiResponse → HTTP 201 JSON
```

### 登录：读 users，新增一个 family 的第一条 sessions

```text
POST /api/v1/auth/login，JSON 包含 username/password
  → 过滤器放行，LoginDTO 校验
  → AuthController.login → AuthServiceImpl.login
  → UserMapper.selectByUsername → PasswordEncoder.matches
  → TokenGenerator.generateRefreshToken
  → createSession → SessionMapper.insert：保存刷新令牌哈希
  → sessionVO → JwtUtil.createAccessToken：JWT 指向新 sessions.id
  → SessionVO → ApiResponse → HTTP 200 JSON
```

### 当前用户：读 sessions/users，不新增登录记录

```text
GET /api/v1/me，Header: Authorization: Bearer 实际 accessToken
  → AuthFilter → AuthServiceImpl.authenticate
      → JwtUtil.parseAccessToken：验签、期限、身份字段
      → SessionMapper.selectById：查是否撤销/过期、是否属于该用户
      → UserMapper.selectById：查当前账号状态
      → BaseContext.setCurrentId
  → UserController.getCurrentUser → UserServiceImpl.getCurrentUser
      → BaseContext.getCurrentId → UserMapper.selectById
  → UserVO → ApiResponse → HTTP 200 JSON
  → AuthFilter.finally 清理上下文
```

### 刷新：插入下一代，撤销上一代；重放则撤销整组

```text
POST /api/v1/auth/refresh，JSON 包含实际 refreshToken
  → AuthFilter 放行，不依赖 accessToken 是否有效
  → RefreshTokenDTO 校验 → AuthController.refresh
  → AuthServiceImpl.refresh（事务）
      → lockSession：算哈希、定位 family、锁首记录、重读当前代
      → 校验期限、撤销状态、账号、剩余秒数
      → createSession：保存新代，沿用整组截止时间
      → markRotated：旧代记录指向新代并撤销
      → sessionVO：签发新 accessToken，返回新 refreshToken
  → 提交 → SessionVO → ApiResponse → HTTP 200 JSON
```

识别 ROTATED 旧令牌的分支改走 `revokeFamily(REPLAY) → 抛业务异常 → 撤销提交 → 全局处理返回 401`，不执行新增后继。

### 登出：更新一组 sessions，不立即删除

```text
POST /api/v1/auth/logout，JSON 包含 refreshToken
  → 放行与 DTO 校验 → AuthController.logout
  → AuthServiceImpl.logout（事务）
      → lockSession → 查到则 revokeFamily(LOGOUT)，未知则直接结束
  → 提交 → Controller 包装空 Map → HTTP 200，data:{}
```

### 前端与后端各自负责什么

| 后端现有职责 | 客户端需要做的事 |
| --- | --- |
| 签发期限，返回 expiresIn，校验到期和会话状态 | 保存两个令牌和访问期限，给私有请求加 Bearer 头 |
| 提供 refresh 接口，校验并轮换 | 在需要时主动调用；成功后同时替换两个令牌 |
| 对旧刷新令牌重放撤销整组 | 避免多个请求同时刷新同一个旧令牌 |
| 撤销和清理会话 | 退出后清理本机保存的登录信息；失效后提示重新登录 |

后端没有定时替前端刷新登录。Apifox 的 Auth 配置能自动生成请求头，但只有环境变量保存了实际 accessToken 才有意义；刷新成功后若没有配置自动提取，需要手动更新 bearerToken 和 refreshToken。

### 读完后可以用这八个问题检查理解

1. 为什么注册的 DTO 有 password，User 有 passwordHash，而 UserVO 两个都没有？
2. UserMapper.insert 的返回 int 和插入后 user.getId() 有什么不同？
3. 为什么登录比较密码要用 matches，不能把 encode 结果再用 equals 比较？
4. 同一用户登录两次，userId、familyId、sessionId 哪些相同、哪些不同？
5. 哪一行使刷新后的整组期限不再增加 30 天？
6. 为什么返回 401 的刷新请求有时仍要提交数据库修改？
7. 为什么查询 /me 不收 userId，Service 却能拿到用户 ID？
8. 为什么 cleanup 清理掉过期记录之前，接口也能拒绝过期令牌？

如果卡住，直接指出文件名和方法名即可。优先从注册/登录的普通查询读起，再读当前用户，最后读刷新锁；不需要先掌握所有测试线程工具才能理解业务。

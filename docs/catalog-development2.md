# catalog 第二阶段：管理员分类管理开发与代码导读

2026-10-13 第二次调整：assets 表已并入 wallpapers，文件信息直接保存商品行；独立上传已停用，创建商品将一次提交资料、原文件及封面。商品写接口尚未实现。以 [两次修改清单](E:/zlowallpaper_sever/docs/catalog-refactor-changes.md) 为当前设计。

2026-10-13 更新：上传代码已并入 catalog，商品只关联当前原文件和封面，取消商品资源历史表及 resourceVersion。商品返回当前原文件 sha256，客户端按文件哈希识别下载。完整调整见 [商品文件管理说明](E:/zlowallpaper_sever/docs/catalog-resource-development.md)。

本阶段实现分类管理四个接口，继续使用现有 Spring Boot、MyBatis、Lombok、统一响应和 auth 权限检查。调用关系是 Controller → Service 接口 → ServiceImpl → Mapper → XML → MySQL。新模型使用普通 Java 类，新依赖注入使用 `@Autowired`，列表转换使用普通 for 循环。

这份文档用于对照源码阅读，示例 ID、账号和分类数据是教学示例。第一阶段的商城公开查询见 [catalog-development.md](E:/zlowallpaper_sever/docs/catalog-development.md)，认证过滤器的详细导读见 [auth-development.md](E:/zlowallpaper_sever/docs/auth-development.md)。

## 阅读导航

- [功能、契约与业务边界](#scope)
- [文件清单与推荐阅读顺序](#files)
- [DTO 与严格 JSON 参数校验](#dto)
- [名称处理、Entity、查询模型和 VO](#models)
- [Controller 与 Service 接口](#controller)
- [CategoryServiceImpl 每个方法](#service)
- [CategoryMapper 与 XML 每条 SQL](#sql)
- [权限、事务与并发规则](#permissions)
- [Apifox 与工作台联调](#integration)
- [测试文件和验证结果](#tests)

<a id="scope"></a>

## 1. 本阶段完成什么

基础地址为 `http://127.0.0.1:8080/api/v1`。四个管理接口都要求 `Authorization: Bearer 实际accessToken`，并且数据库中该账号当前角色是 ADMIN、状态是 ACTIVE。

| 方法与路径 | 输入 | 成功 HTTP | 响应 data |
| --- | --- | --- | --- |
| GET /admin/categories | 无业务参数，完整列表，不分页 | 200 | AdminCategoryVO 数组，包括停用分类 |
| POST /admin/categories | name；可选 sortOrder、enabled | 201 | 新分类的 AdminCategoryVO |
| PATCH /admin/categories/{id} | name、sortOrder、enabled 至少一项 | 200 | 修改后的 AdminCategoryVO |
| DELETE /admin/categories/{id} | 路径 ID，无请求体 | 200 | 空对象 `{}` |

所有 JSON 响应仍包含 code、message、data、requestId。成功 code 为 OK，错误 data 为 null，并保留实际 HTTP 错误状态。没有把删除改为 204。

```json
{
  "code": "OK",
  "message": "",
  "data": {
    "id": "12",
    "name": "风景",
    "sortOrder": 0,
    "enabled": true,
    "wallpaperCount": 0
  },
  "requestId": "本次请求编号"
}
```

### 业务规则

1. 名称去掉首尾空白后为 1–64 个 Unicode 码点；名称唯一，停用分类也参与判重，实际比较服从数据库排序规则。
2. 新建省略 sortOrder/ enabled 时默认 0/ true；显式传 null 不合法。
3. sortOrder 是 int32 整数，允许负数；enabled 是 JSON 布尔值，不能传 0/1 或字符串。
4. PATCH 只更新传来的字段，省略字段保留原值；空对象、未定义字段均拒绝。
5. 列表按 sortOrder 升序，同值按 id 升序，包含没有商品的分类。
6. wallpaperCount 统计所有商品状态的引用数，动态查询，不新增数据库列。
7. 有任何商品引用时不能删除，包括 DRAFT/PUBLISHED/WITHDRAWN；不级联删除，不把删除失败转成停用。
8. 停用只修改分类状态，不修改商品发布状态，也不影响订单、资源或购买权益。
9. 公开 GET /categories 保持匿名，只返回启用分类的字符串 id/name。

当前数据库已经有 categories 及 wallpapers.category_id 外键，无需迁移或增加表。商品创建和修改中“新选分类必须启用、旧停用分类可以保留”的校验属于下一阶段商品管理，当前还没有商品写接口。

<a id="files"></a>

## 2. 文件清单与阅读顺序

| 文件 | 本次作用 |
| --- | --- |
| [AdminCategoryController.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/controller/AdminCategoryController.java) | 新增四个管理 HTTP 入口 |
| [CategoryMutationDTO.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/dto/CategoryMutationDTO.java) | 创建/修改共用参数和校验 |
| [CategoryCreateDTO.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/dto/CategoryCreateDTO.java) | 创建必须提供 name |
| [CategoryUpdateDTO.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/dto/CategoryUpdateDTO.java) | 修改至少提供一项 |
| [CategoryFieldDeserializers.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/dto/CategoryFieldDeserializers.java) | 严格读取 String、Integer、Boolean，避免自动类型转换 |
| [CategoryNames.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/util/CategoryNames.java) | 统一去除名称首尾 Unicode 空白 |
| [Category.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/entity/Category.java) | categories 表实体 |
| [AdminCategoryRow.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/model/AdminCategoryRow.java) | 分类加动态引用数的 SQL 中间结果 |
| [AdminCategoryVO.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/vo/AdminCategoryVO.java) | 管理端五字段返回对象 |
| [CategoryService.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/service/CategoryService.java) | 在现有接口中增加管理方法 |
| [CategoryServiceImpl.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/service/impl/CategoryServiceImpl.java) | 实际业务步骤，普通 for 转 VO |
| [CategoryMapper.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/mapper/CategoryMapper.java) | 增加查询、插入、动态更新、删除方法 |
| [CategoryMapper.xml](E:/zlowallpaper_sever/src/main/resources/mapper/catalog/CategoryMapper.xml) | 新增方法的 SQL |
| [AdminCategoryIntegrationTest.java](E:/zlowallpaper_sever/src/test/java/com/zlo/wallpaper/catalog/AdminCategoryIntegrationTest.java) | 独立 H2 中验证完整请求与业务流程 |
| [admin-category-schema.sql](E:/zlowallpaper_sever/src/test/resources/admin-category-schema.sql) | 测试分类表，支持自增/唯一约束/时间字段 |
| [AdminCategoryMySqlIT.java](E:/zlowallpaper_sever/src/test/java/com/zlo/wallpaper/catalog/AdminCategoryMySqlIT.java) | 显式开启的真实 MySQL SQL、唯一键与外键检查 |

建议先看 Entity 和 VO，然后看 Controller，接着看 Service 的创建/修改/删除，最后读 XML 的 LEFT JOIN 和事务。JSON 严格解析、Unicode 空白工具与并发测试可以第二轮再读，不必一开始就掌握所有细节。

本次没有增加 PageHelper，也没有给完整分类列表套分页插件。现有公开查询使用的 record、构造器注入和 Stream 沿用原实现；新增管理代码采用更接近苍穹外卖的普通类与 for 写法。

<a id="dto"></a>

## 3. DTO：如何接收创建和修改参数

### 3.1 CategoryMutationDTO.java

Mutation 可以理解为“修改数据的请求”。两个具体 DTO 继承这个类，复用三个字段：

```java
private String name;
private Integer sortOrder;
private Boolean enabled;
```

`@Getter`、`@Setter` 由 Lombok 生成读写方法。Integer、Boolean 是包装类型，能为 null；int、boolean 则不能。对 PATCH 来说：

| DTO 中的值 | 含义 |
| --- | --- |
| sortOrder 为 null | 没有传这个字段，不修改排序 |
| sortOrder 为 0 | 明确要求排序为 0 |
| enabled 为 null | 没有传这个字段，不修改状态 |
| enabled 为 false | 明确要求停用 |

如果使用 int/boolean，省略字段也会变成 0/false，就很难区分用户到底想不想修改。

### 每个字段上两个 JSON 注解的作用

`@JsonSetter(nulls = Nulls.FAIL)` 拒绝 JSON 中显式写 null；缺少字段则仍保留 Java null。它不是普通业务 setter 的空值判断，只在 Jackson 读取 JSON 时应用。

`@JsonDeserialize(using = ...class)` 指定该字段怎么从 JSON 读成 Java 值。这里要求精准类型，例如排序传 `"1"` 或 `1.5` 会返回 400，不会自动转成整数 1。

因此 `{}` 中没有 enabled 可以保留原值，而 `{"enabled":null}` 是错误；`{"enabled":false}` 是合法修改。这一点和“所有 null 都代表不修改”不同。

### isNameValid()

这个方法由 `@AssertTrue` 参与 `@Valid` 校验：没有 name 时先返回 true，把必填规则留给具体 DTO；有 name 时，先调用 CategoryNames.normalize，再检查非空和码点长度不超过 64。

这里没有简单使用 `@Size(max=64)`，因为 Java String 长度按 UTF-16 编码单元计算，某些 emoji 占 2；接口按 Unicode 码点限制。64 个这样的 emoji 可以作为名称，65 个拒绝。

### rejectUnknownField(...)

`@JsonAnySetter` 接收未定义的 JSON 字段，方法直接抛 IllegalArgumentException。Jackson 将读取失败交给全局错误处理，返回 400。

例如 id 和 wallpaperCount 都由后端生成，前端不能写它们；拼错 sortOrder 或额外传 status 也应明确失败。这个检查只影响这两个分类 DTO，没有全局改变已有认证或商品接口的 Jackson 配置。

### 3.2 CategoryCreateDTO.java

`extends CategoryMutationDTO` 继承字段与基础校验。isNameProvided 上的 `@AssertTrue` 要求 name 不为 null；不要求另外两项必须传，因为 Service 会应用默认值。

### 3.3 CategoryUpdateDTO.java

isChangeProvided 返回三个字段中是否至少一个非 null。false 和 0 本身是非 null 的合法值，所以不会被误认为“没有修改内容”。

空 `{}` 返回 400；`{"sortOrder":0}` 和 `{"enabled":false}` 都能通过。继承的 isNameValid 同时检查提供的新名称。

### 3.4 CategoryFieldDeserializers.java

这个文件把三个很短的解析器放在一起，外层工具类的构造器 private，不能随意 new。三个内部类是 public static，Jackson 可以分别创建，不需要先创建外层对象。

| 内部类 | 检查的 JsonToken | 返回方式 |
| --- | --- | --- |
| StringValue | VALUE_STRING | parser.getText() 返回原字符串 |
| IntegerValue | VALUE_NUMBER_INT | parser.getIntValue()，同时检查 int32 范围 |
| BooleanValue | VALUE_TRUE 或 VALUE_FALSE | parser.getBooleanValue() 返回布尔值 |

每个类继承 `JsonDeserializer<对应类型>` 并重写 deserialize。类型不符合时调用 reportInputMismatch 抛解析异常；不是把错误消息作为正常字段值返回。throws IOException 是解析方法的异常声明。

这些组件对应的是前端契约对 JSON 类型的要求，不需要额外安装依赖，Jackson 已由 Web starter 提供。

<a id="models"></a>

## 4. 名称工具、数据库实体、查询模型和返回 VO

### 4.1 CategoryNames.java

normalize(String name) 用于 DTO 校验和 Service 写库，两处使用同一规则，避免“校验时的名称”和“保存时的名称”不同。

方法先处理 null，然后用 start/end 标记字符串两端。第一个 while 向右跳过首部空白，第二个 while 向左跳过末尾空白，最后 substring 返回中间部分。名称中间的空格会保留。

`codePointAt` 和 `codePointBefore` 读取一个 Unicode 码点，`Character.charCount` 返回它占几个 UTF-16 单元，让索引正确移动；不是逐字节扫描。

isWhitespace 把 Java 空白字符、空格分隔符（包括不换行空格）和 FEFF 视为需要去掉的首尾空白。普通 `String.trim()` 对 Unicode 空白覆盖不足，而浏览器输入可能包含这些字符。这个工具只做首尾处理，不统一大小写、不改变中间字符。

### 4.2 Category.java

| Java 字段 | 数据库列 | 作用 |
| --- | --- | --- |
| Long id | id | 自增 ID，插入后由 MyBatis 回填 |
| String name | name | 规范化名称 |
| Integer sortOrder | sort_order | 排序值 |
| Boolean enabled | enabled | Java true/false 映射数据库 1/0 |
| LocalDateTime createdAt | created_at | UTC 创建时间 |
| LocalDateTime updatedAt | updated_at | UTC 修改时间 |

实体用 `@Getter`、`@Setter`，无额外业务逻辑。XML 明确列出表名和列名，不依靠 BaseMapper 自动生成 SQL，因此无需 @TableName。

同一个实体还被用来保存更新字段：未提供的属性保持 null，由 XML 判断是否更新。查询实体代表完整记录，更新实体代表本次改变的字段，阅读变量名 existing/changes 可以区分用途。

### 4.3 AdminCategoryRow.java

它继承 Category，并增加 Long wallpaperCount。父类六个字段来自 categories，额外字段来自 SQL COUNT 聚合。

这是 Mapper → Service 的中间结果，既不是新表，也不是要向 categories 加一列。继承只用于复用分类属性，查询 count 由这一类接收。

### 4.4 AdminCategoryVO.java

它只有 id、name、sortOrder、enabled、wallpaperCount，使用普通类和 Lombok。

id 转为 String 与既有契约保持一致；没有输出 createdAt/updatedAt；wallpaperCount 是 JSON 数字，enabled 是 JSON 布尔值。公开 CategoryVO 仍仅有 id/name，管理 VO 不取代公开 VO。

<a id="controller"></a>

## 5. Controller 与 Service 接口

### 5.1 AdminCategoryController.java

`@RestController` 声明返回 JSON 数据；`@RequestMapping("/admin/categories")` 定义共同路径；`@Autowired` 注入 CategoryService。`/api/v1` 是配置中的部署前缀，不必在每个注解里重复。

getAdminCategories 通过 `@GetMapping` 接收完整列表请求，调用 Service，返回 `ApiResponse<List<AdminCategoryVO>>`。当前没有分页 DTO。

createCategory 使用 `@PostMapping`、`@ResponseStatus(CREATED)`，通过 `@Valid @RequestBody` 读取并校验 CategoryCreateDTO。只有校验成功才进入业务方法，成功状态为 201。

updateCategory 使用 `@PatchMapping("/{id}")`；`@PathVariable String id` 接收 URL 中的编号；`@RequestBody` 读取 JSON 中的变化项。路径 ID 和 JSON DTO 是两份独立输入。

deleteCategory 使用 `@DeleteMapping("/{id}")`，没有请求体 DTO。Service 返回 void，Controller 用 `Map.of()` 构造空对象 data，仍返回 200。

四个方法都从 HttpServletRequest 取得 RequestIdFilter 生成的编号。request 由框架传入，前端无需提供 JSON 字段 request。

Controller 不直接查表，不判断名字重复，也不手写 SQL。权限由入口 AuthFilter 检查，业务规则交给 Service。

### 5.2 CategoryService.java

原有 listEnabled() 保留，新增四个方法：

```java
List<AdminCategoryVO> getAdminCategories();
AdminCategoryVO createCategory(CategoryCreateDTO dto);
AdminCategoryVO updateCategory(String id, CategoryUpdateDTO dto);
void deleteCategory(String id);
```

接口定义调用约定，CategoryServiceImpl 提供实现。方法命名直接描述操作，不通过一个 save 方法混合处理新建和编辑。

<a id="service"></a>

## 6. CategoryServiceImpl.java：逐个方法读

类上 `@Service`，注入 CategoryMapper 和 Clock。Clock 复用 auth 中的 UTC 时间 Bean，没有增加新的时间库。

### 6.1 listEnabled：保留公开分类查询

它继续调用原 selectEnabled，仅返回启用分类的 CategoryVO。现有 Stream 转换沿用；本次没有批量重写公开查询代码。

### 6.2 getAdminCategories：完整管理列表

先调用 selectAdminCategories 得到 `List<AdminCategoryRow>`，创建 ArrayList，再 for 遍历，每一行调用 toAdminVO，最后返回 List。

没有结果时，ArrayList 为空，序列化后是 `[]`，不是 null。查询与排序在 SQL 中完成，Service 不再把全部商品下载到 Java 中统计。

### 6.3 createCategory：创建与默认值

1. normalize 名称，去除首尾空白。
2. checkNameAvailable(name, null) 检查重名，没有排除任何分类。
3. new Category 并设置 name；未传 sortOrder 时设置 0，未传 enabled 时设置 true。
4. now() 获取 UTC 毫秒时间，createdAt/updatedAt 初始相同。
5. insert，数据库生成 id 并回填到 Category。
6. 按新 id 查询管理结果，转换 VO 返回。

先查重不能单独防并发：两个请求可能都查到不存在。catch DuplicateKeyException 把最终唯一键冲突转换为 409 CATEGORY_NAME_EXISTS。

没有允许前端指定 ID，引用数也没有参与 INSERT。创建的分类尚未被商品引用时，返回 wallpaperCount=0。

### 6.4 updateCategory：只改传来的字段

先 getCategoryForUpdate 校验 ID、找到分类并锁定，得到 existing。创建新的 changes 对象，只设置目标 id 和要修改的字段。

如果 name 非 null，先规范化，再 checkNameAvailable(name, existing.id)。排除当前 id 是为了让“保留自己的名称”不被误判为重名。

sortOrder/ enabled 直接取 DTO 值；省略时仍为 null，XML 将跳过对应列。false 和 0 不会被跳过。updatedAt 始终更新，createdAt 不改。

update 执行后按 existing.id 重新查询 VO。捕获并发重名的 DuplicateKeyException。它没有更新 wallpapers，因此分类停用、改名和排序不会偷偷改变商品状态。

为什么不复制 existing 的全部字段再整体 UPDATE？只写变化项能清楚表达 PATCH 语义，也防止两个编辑请求修改不同字段时覆盖对方没想修改的值。

### 6.5 deleteCategory：检查引用，然后删除分类

1. getCategoryForUpdate 找到并锁定分类，不存在返回 404。
2. countWallpapers 查询当前引用数，大于 0 返回 409 CATEGORY_IN_USE。
3. 调用 deleteById，只删除 categories 中这一行，影响行数必须为 1。
4. 若数据库仍报告引用完整性冲突，捕获 DataIntegrityViolationException 并返回 CATEGORY_IN_USE。

代码没有读取前端列表中的 wallpaperCount 作为授权依据，也没有 DELETE wallpapers/assets/resources。分类引用计数和外键都保留；任一商品状态都阻止删除。

重复删除不是本模块的“成功重试”：分类已经不存在时返回 404 CATEGORY_NOT_FOUND，这是契约要求，与 auth 登出的可重复 200 不同。

### 6.6 getCategoryForUpdate：ID 校验与记录锁

复用 [CatalogIds.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/service/CatalogIds.java)。validatePathId 检查外部字符串格式，错误为 400；findDatabaseId 尝试转为本库正数 BIGINT，无法对应本库编号时视为不存在，返回 404。

例如 `wp-1000` 符合外部 ID 字符范围，但本库生成数字 ID，因此视为不存在；超出 long 范围的数字也为 404。正常前端应使用列表返回的实际 id。

selectByIdForUpdate 在事务中锁住目标分类。修改与删除同一分类按顺序处理；数据库外键检查也配合这条记录锁，避免删除期间凭空产生有效商品引用。

### 6.7 checkNameAvailable

调用 selectByName(name, excludeId)。查到记录就抛 nameExists；新建 excludeId=null，修改 excludeId=自身编号。

数据库没有按 enabled 筛选，所以停用分类仍占用名字。比较规则来自 categories 的排序规则，本机 utf8mb4_0900_ai_ci 除大小写不敏感，也包含重音不敏感等规则，不要把它理解为仅 Java lowercase 后比较。

### 6.8 toAdminVO

new VO，逐项 setId/setName/setSortOrder/setEnabled/setWallpaperCount。id 的 Long.toString() 保持前端字符串契约；其他类型按原值返回。

这是显式字段选择，目的和苍穹外卖的 Entity → VO 转换相同。这里字段不多，手写 set 更容易看清每个返回值的来源，没有引入新的复制插件。

### 6.9 now 与三个错误辅助方法

now 用 clock.instant 获取时刻，truncatedTo(MILLIS) 截到毫秒，再按 UTC 转 LocalDateTime，对齐 DATETIME(3)。

nameExists、categoryInUse、notFound 分别创建 409 CATEGORY_NAME_EXISTS、409 CATEGORY_IN_USE、404 CATEGORY_NOT_FOUND 的 BusinessException；调用处 `throw ...()` 才真正抛出。

<a id="sql"></a>

## 7. CategoryMapper 与 XML：先读普通 CRUD，再读关联统计

Mapper 的 `@Mapper` 让 MyBatis 生成实现；方法上的 `@Param` 给简单参数命名，XML 中通过 `#{...}` 绑定，不用字符串拼接。

namespace 对应 CategoryMapper 全类名，SQL 标签的 id 对应 Java 方法名。查询 resultType 是一行结果的 Java 类型，即使返回 List，也不是把 resultType 写成 List。

### 7.1 原 selectEnabled

原有 `@Select` 查询保留：只选 id/name，WHERE enabled=1，ORDER BY sort_order/id。这个方法没有在 XML 重复定义，避免同一方法注册两份 SQL。

### 7.2 selectAdminCategories：LEFT JOIN 与 COUNT

展开复用片段后的核心 SQL 是：

```sql
SELECT c.id, c.name, c.sort_order, c.enabled, c.created_at, c.updated_at,
       COUNT(w.id) AS wallpaper_count
FROM categories c
LEFT JOIN wallpapers w ON w.category_id = c.id
GROUP BY c.id, c.name, c.sort_order, c.enabled, c.created_at, c.updated_at
ORDER BY c.sort_order ASC, c.id ASC
```

c 是 categories 的别名，w 是 wallpapers 的别名。连接条件 `w.category_id = c.id` 把商品连到它所属的分类。

假设有两条分类和三条商品：

| categories.id | name | wallpapers.id | publication_status |
| --- | --- | --- | --- |
| 1 | 自然 | 100 | PUBLISHED |
| 1 | 自然 | 101 | DRAFT |
| 1 | 自然 | 102 | WITHDRAWN |
| 2 | 未引用 | null | null |

LEFT JOIN 保留左表分类，即使右边没有商品也留下一行。INNER JOIN 会把“未引用”分类漏掉，不适合完整管理列表。

COUNT(w.id) 只数非 null 商品编号：分类 1 得到 3，分类 2 得到 0。若改成 COUNT(*)，分类 2 的那一行也会计为 1，所以这里必须数商品 id。

GROUP BY 把同分类的多行合成一组，COUNT 为每组统计。把非聚合列明确列入分组，使 SQL 在 MySQL 的严格分组规则下也清楚有效。

没有 `WHERE w.publication_status='PUBLISHED'`：删除保护需要知道所有状态的商品引用。也没有 enabled 过滤：管理列表要包含停用分类。

这只是普通 SQL 关联与分组，没有 MyBatis-Plus 分页 COUNT 改写，不需要此前讨论的分页 JOIN 优化开关。

### 7.3 selectAdminById

复用相同 LEFT JOIN 与 COUNT，增加 `WHERE c.id = #{id}`，然后分组。用于创建/修改后返回完整管理 VO，包含最新引用数。

### 7.4 selectByIdForUpdate

只查 categories 六列，并加 `WHERE id = #{id} FOR UPDATE`。它不连商品表，也不做 COUNT，明确锁定即将修改或删除的分类记录。

### 7.5 selectByName

按 `WHERE name = #{name}` 查询，若 excludeId 非 null，增加 `AND id != #{excludeId}`。`<if test="excludeId != null">` 是 MyBatis 动态 SQL，根据参数决定是否生成这一段。

没有排除停用分类，不使用 LIKE。数据库唯一索引最终保证两个并发请求不能存同名分类。

### 7.6 countWallpapers

```sql
SELECT COUNT(*) FROM wallpapers WHERE category_id = #{categoryId}
```

这里只有商品表，没有 LEFT JOIN 产生的空行，因此 COUNT(*) 正确。返回 long，0 表示当前没有任何商品引用。

### 7.7 insert

插入 name、sort_order、enabled、created_at、updated_at，省略 id。`useGeneratedKeys="true" keyProperty="id"` 把生成的数据库编号回填实体。

Mapper 返回 int 影响行数，生成的 ID 在 category.getId() 中，不要把二者混淆。Boolean 参数通过 JDBC/MyBatis 映射到本机 TINYINT 的 1/0。

### 7.8 update：动态 SET

```xml
<set>
    <if test="name != null">name = #{name},</if>
    <if test="sortOrder != null">sort_order = #{sortOrder},</if>
    <if test="enabled != null">enabled = #{enabled},</if>
    updated_at = #{updatedAt}
</set>
```

`<set>` 生成 SET 并处理逗号；每个 `<if>` 只加入已提供字段。检查是非 null，不是检查真假，所以 false 可以更新。

例如只传 enabled=false，实际 SQL 类似 `UPDATE categories SET enabled=?, updated_at=? WHERE id=?`；不会同时把 name 清空或排序置零。

所有情况都按目标 id 更新，created_at 没有参与。相同值再次 PATCH 仍正常返回，不把“值没有改变”当作分类不存在。

### 7.9 deleteById

`DELETE FROM categories WHERE id = #{id}` 只删除分类一行。数据库的 ON DELETE RESTRICT 仍生效，不改成 CASCADE。

`<sql>`/`<include>` 在本 XML 中复用列清单、关联查询和分组，不会自己发起额外查询。生成键与映射规则可对照 [MyBatis 官方说明](https://mybatis.org/mybatis-3/sqlmap-xml.html)。

<a id="permissions"></a>

## 8. 权限、事务和并发：为什么不只是四条 SQL

### 权限使用现有 auth

[AuthFilter.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/auth/filter/AuthFilter.java) 在 /admin 路径核验访问令牌、会话、用户状态和当前 ADMIN 角色。普通用户返回 403，未登录/失效返回 401。Controller 不接受 role 或 isAdmin，不根据用户名是不是 admin 判断权限。

### 写操作使用 @Transactional

create/update/delete 都是事务方法，读写和锁在同一事务内完成。正常返回提交，未处理的运行时异常回滚。这里没有像 auth.refresh 那样设置 noRollbackFor：重名/引用冲突时应该回滚本次写操作。

读列表不加锁，wallpaperCount 是当次查询的快照，不是承诺将来仍为 0。

### 并发修改

两个请求修改同一分类时，FOR UPDATE 让它们依次读取并修改。动态 UPDATE 只写变化项，因此一个改名、一个改排序，最后两个变化都能保留；若两者都改名称，后执行者覆盖名称是普通顺序更新行为，当前未引入版本号冲突机制。

### 并发新增与改名

Service 先查重给出清楚提示，数据库唯一约束做最终裁决。遇到 DuplicateKeyException 转为 409，不产生两条同名分类。

### 删除保护

先锁分类，再查询引用数。已有引用返回 409；删除语句还受外键约束。另一个事务新增商品引用时也要经过数据库的父表外键检查：若删除先完成，商品不能指向已经不存在的分类；若商品引用先完成，分类不能删掉。

保留外键有独立价值，不能因为“代码已经查了一次 count”而取消它。MySQL 的 RESTRICT 规则可对照 [外键文档](https://dev.mysql.com/doc/refman/8.4/en/create-table-foreign-keys.html)。

后续商品管理仍需自行校验启用分类，不能把“分类存在”当成“允许新建商品选择它”。

<a id="integration"></a>

## 9. Apifox 与管理员工作台联调

### 启动与账号

重新启动后端或运行最新打包结果，正在运行的旧进程不会因为文件写好了就自动加载新增接口。仍使用项目已有的 Java 21/Maven 启动方式。

本次没有在开发库创建永久管理员或修改现有用户角色。公开注册只产生 USER；实际工作台联调须准备受控的管理员账号，再用普通登录接口取得令牌。测试中的管理员只存在独立测试库，不是实际工作台账号。

### Apifox 示例

将 Auth 设为 Bearer Token，使用保存了实际访问令牌的变量。下面 POST/PATCH 的 Body 选择 JSON，GET/DELETE 不发请求体。

1. GET `/admin/categories`：应返回包含停用分类的数组；没有分类时 `[]`。
2. POST `/admin/categories`：

   ```json
   {"name":"风景","sortOrder":0,"enabled":true}
   ```

   成功 201，记下响应 data.id。

3. PATCH `/admin/categories/实际id`：

   ```json
   {"enabled":false}
   ```

   成功 200，name/sortOrder 保持原值；公开 /categories 不再显示它。

4. PATCH 同一分类，`{"name":"山水","sortOrder":-1}`：检查改名和排序。
5. DELETE `/admin/categories/实际id`：未被商品引用时 200，data 为 `{}`；再次删除为 404。

### 应重点测试的失败情况

| 输入/场景 | 预期 |
| --- | --- |
| POST 省略 name，或名称全空白/超长 | 400 VALIDATION_ERROR |
| PATCH `{}`，或任一字段显式 null | 400 VALIDATION_ERROR |
| sortOrder 为字符串、浮点数、超出 int32 | 400 VALIDATION_ERROR |
| enabled 为数字或字符串 | 400 VALIDATION_ERROR |
| 传 id、wallpaperCount 或未定义字段 | 400 VALIDATION_ERROR |
| 使用另一个分类的名称，包括停用分类 | 409 CATEGORY_NAME_EXISTS |
| 删除被任一状态商品引用的分类 | 409 CATEGORY_IN_USE |
| 合法字符串编号无法找到分类 | 404 CATEGORY_NOT_FOUND |
| 非法路径 ID 字符 | 400 VALIDATION_ERROR |
| 未登录或过期，普通用户 | 分别 401、403 |

工作台现有分类页面已经消费这四个契约，不需要前端为了本次实现换路径或字段。浏览器 DELETE 的预检由现有 WebConfig 处理。自动测试通过不等于已经完成浏览器真实工作台验收；实际联调还需要启动新后端和真实管理员凭证。

<a id="tests"></a>

## 10. 测试文件：各自在检查什么

### 10.1 AdminCategoryIntegrationTest.java

它使用独立 mem:admin-category H2、真实 Mapper SQL、实际 Controller 和 AuthFilter，不连接商城开发库。@SpringBootTest 加载组件，@AutoConfigureMockMvc 提供模拟 HTTP，@ActiveProfiles(test) 使用测试配置。

@TestInstance(PER_CLASS) 让该测试类共用一个测试实例。@BeforeAll 在独立库创建两个测试账号并登录，一个 ADMIN 一个 USER，避免每个参数化输入都重新做密码哈希。@BeforeEach 重置分类、商品夹具和测试管理员状态，使每项检查独立。

SQL 初始化依次使用 auth-schema.sql、admin-category-schema.sql、catalog-schema.sql。新分类测试表有完整六字段；已有 catalog 测试脚本为其他查询表提供结构。每个测试方法都不包在总回滚事务中，并发线程各自使用 Service 的真实事务。

| 测试方法 | 检查 |
| --- | --- |
| adminListIncludesDisabledSortedCategoriesAndCountsEveryProductState | 完整分类、排序、停用、零引用、全部状态引用数、五字段、requestId 和公开响应兼容 |
| emptyAdminListIsAnArray | 无分类返回空数组 |
| creationTrimsNameAppliesDefaultsAndReturnsGeneratedStringId | 规范化、默认值、自增回填、字符串 ID |
| creationAcceptsFalseZeroAndInt32Limits | false/0、排序上下界合法 |
| duplicateNamesIncludeDisabledAndUseTrimmedCaseInsensitiveComparison | 包括停用分类判重、修改失败不改数据、自身同名允许 |
| unicodeLengthIsMeasuredAfterTrimmingAndAllows64Emoji | Unicode 长度和首尾特殊空白 |
| patchOnlyUpdatesProvidedFieldsAndAcceptsFalseAndZero | 省略字段保持原值，false/0 生效 |
| disablingCategoryDoesNotWithdrawOrHidePublishedWallpaper | 停用不下架，公开详情仍可读取原分类 |
| unusedCategoryDeleteReturnsEmptyObjectAndRepeatIsNotFound | 未引用可删，空对象和重复 404，不删商品/文件 |
| anyProductStatePreventsDeletion | 三种商品状态分别阻止删除 |
| staleZeroCountIsNotPermissionToDelete | 列表显示 0 后新增引用，删除仍 409，重读得到新计数 |
| invalidCreationIsRejectedWithoutInserting | 多组非法创建输入均 400，表不多一行 |
| invalidPatchDoesNotChangeCategory | 多组非法修改均拒绝，原值不变 |
| validButUnmappedIdsAreNotFound | 非数字合法 ID、超大编号、0、不存在编号 |
| malformedIdAndMalformedJsonAreBadRequests | 错误路径字符与坏 JSON |
| allFourRoutesRequireCurrentAdminRole | 四接口都受保护，角色/状态变化立即生效 |
| deleteCorsPreflightSucceedsAndForbiddenResponseKeepsCorsHeader | DELETE 预检、403 保留跨域头 |
| sqlCharactersInNameAreBoundAsData | SQL 特殊字符只是名字数据 |
| concurrentPatchesPreserveBothIndependentChanges | 并发改名/排序都保留 |
| concurrentCreatesCannotInsertDuplicateNames | 并发创建同名最多一个成功 |

createTogether 配合 CountDownLatch 同时开始创建；ExecutorService 两线程执行，Future 收集结果，finally 关闭线程池。insertWallpaper 创建商品夹具；registerAndLogin 准备测试身份；request 封装路径、JSON、Bearer 头及状态断言；body 把响应读成 JsonNode；assertError 检查业务错误码与 data:null。

### 10.2 admin-category-schema.sql

这是独立 H2 的 categories 定义：自增 BIGINT、大小写不敏感名称唯一、排序默认值、启用 0/1 约束、两个时间列。H2 的 VARCHAR 长度按 UTF-16 单元计，这份测试表用 128 容纳 64 个补充平面码点；请求校验仍严格限制 64 码点，正式 MySQL utf8mb4 VARCHAR(64) 保持原定义。真实库另有用例验证 64 个 emoji 可以保存。此文件不用于更新 MySQL，不能作为开发库重建脚本。

测试把自增序列重置到 1000，固定夹具使用 1–4，避免新建时编号和夹具撞在一起。正式 MySQL 自增序列不由这个测试改变。

### 10.3 AdminCategoryMySqlIT.java

显式开关 category.mysql.tests=true 才启用。@ActiveProfiles(local) 使用本机 MySQL，@Transactional 将每项测试的夹具回滚。负数测试 ID 和 UUID 名称用于与真实数据隔离；不运行 DDL，不插入永久管理员，不推进自增 ID。

五项检查分别验证：管理 LEFT JOIN/COUNT 和停用零引用；动态更新的 null/false/0；实际 MySQL 排序规则与唯一约束；实际外键拒绝被引用分类删除、允许未引用分类删除；utf8mb4 VARCHAR(64) 保存 64 个补充平面字符。

| 方法 | 具体操作 |
| --- | --- |
| insertRollbackFixtures | 每项测试前插入带 UUID 名称的负数用户/分类和三条草稿；这些数据与真实账号隔离，最后回滚 |
| adminJoinReturnsDisabledZeroCountAndCompleteReferenceCount | 对照管理查询的三条引用、零引用与 Boolean；for 查找夹具分类 |
| dynamicUpdateKeepsMissingFieldsAndWritesFalseAndZero | 仅填 enabled=false、sortOrder=0、updatedAt，检查名称和引用数仍保留 |
| duplicateNameLookupAndUniqueKeyIncludeDisabledRows | 大写名称仍能查到停用分类；排除自身后为空；直接改成另一个名字由唯一键拒绝 |
| realForeignKeyRejectsReferencedDeletionAndAllowsUnusedDeletion | 绕过 Service 直接执行 DELETE，确认数据库本身仍保护引用；未引用分类可以删除 |
| utf8mb4ColumnStores64SupplementaryUnicodeCharacters | 把夹具名称改为 64 个 emoji，读取后逐字符串比较 |

JdbcTemplate 的问号参数绑定负责插入夹具，真正待验证的查询/更新/删除使用 CategoryMapper。assertThrows 验证指定数据库异常出现，并不把这些异常当作接口返回值；接口的异常转换已经在 H2 完整流程中验证。

这份测试主要验证 MySQL Mapper 和约束；完整 HTTP 权限与参数流程由前面的集成测试覆盖。测试直接调用 Mapper 时可以使用负数夹具编号，公开接口仍只认本库正常正数 ID。

### 10.4 运行命令

```powershell
$env:JAVA_HOME = 'D:\Codex\software\jdk-21.0.12.1+1'
& 'D:\Codex\software\apache-maven-3.9.16\bin\mvn.cmd' -B -ntp '-Dmaven.repo.local=D:/Codex/software/maven-repository' verify
```

默认 verify 运行内存库测试并打包。显式 MySQL 检查：

```powershell
& 'D:\Codex\software\apache-maven-3.9.16\bin\mvn.cmd' -B -ntp '-Dmaven.repo.local=D:/Codex/software/maven-repository' '-Dtest=AdminCategoryMySqlIT' '-Dcategory.mysql.tests=true' test
```

2026-10-11 本次验证结果：

- 全量 verify：114 项通过，0 失败、0 错误；其中分类管理 58、auth 21、公开 catalog 32、基础检查 3；可执行 JAR 打包成功。
- 显式真实 MySQL：AdminCategoryMySqlIT 的 5 项全部通过，包括实际唯一约束、外键和 64 个补充平面字符保存；夹具使用负数 ID，测试事务回滚。随后只读检查测试标记的用户/分类数量均为 0。
- 文档文件链接、导航、方法覆盖与 Markdown 结构另行核对。未将上述自动检查描述为真实浏览器工作台验收。

本次 Codex 受限执行环境中，测试工具自动附加 Java agent 曾停滞，运行测试时改用启动加载现有 Byte Buddy agent；没有修改工程依赖或正式启动参数。如果同类环境卡在测试初始化，可在 Maven 命令的 verify/test 前附加以下参数（路径以本机已安装版本为准）：

```powershell
'-DargLine=-javaagent:D:/Codex/software/maven-repository/net/bytebuddy/byte-buddy-agent/1.17.8/byte-buddy-agent-1.17.8.jar'
```

首次检查还暴露 H2 对 VARCHAR 长度的 UTF-16 计数差异，修正测试表后，上述全量检查通过；正式 MySQL 的 VARCHAR(64) 保持原设计，并由真实库测试验证。

## 11. 把一个 PATCH 请求完整串起来

```text
PATCH /api/v1/admin/categories/12，Body: {"enabled":false}
  → RequestIdFilter：生成请求编号
  → CorsFilter：处理浏览器来源
  → AuthFilter：检查 accessToken、sessions、账号状态和 ADMIN 角色
  → Jackson：读取准确布尔类型；拒绝显式null和未知字段
  → @Valid：检查至少一项修改
  → AdminCategoryController.updateCategory
  → CategoryServiceImpl.updateCategory（事务）
      → 校验路径ID，selectByIdForUpdate锁定分类
      → 构造changes，只有id/enabled/updatedAt需要写入
      → CategoryMapper.update，XML生成局部SET
      → selectAdminById读取分类与最新引用数
      → toAdminVO
  → 事务提交 → ApiResponse → HTTP 200 JSON
  → AuthFilter清理线程上下文
```

列表、新建、删除都沿用这条层次关系，只是对应的 Service 判断与 SQL 不同。读代码时可以先在 Controller 找入口，再按方法名进入 Service，最后对照 Mapper/XML 中同名的语句。

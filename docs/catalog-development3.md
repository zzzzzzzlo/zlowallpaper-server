# catalog-development3：壁纸商品管理与一次保存

更新：2026-10-14。本篇对应已经实现的商品管理代码。分类管理仍看 `catalog-development2.md`；两次改表的历史看 `catalog-refactor-changes.md`。现在没有独立 assets 表、商品历史版本，也没有前端独立上传调用。

阅读顺序：先打开 `AdminWallpaperServiceImpl.createWallpaper` 或 `updateWallpaper`，这个方法直接展示检查、上传、事务保存、清理和返回；需要了解数据库操作时，再打开 `WallpaperPersistenceService` 的同名业务方法。普通辅助检查都在这两个文件内部，不再单独跳到 `WallpaperRules`，也没有共用 `save` 二次分流。

本次整理保持路径、multipart字段、字符串ID、HTTP状态、数据库结构不变。两次分类检查、商品锁及上传限流保留；原因写在代码的“【并发复查】”“【并发控制】”旁边。

2026-10-15旧接口清理：删除RetiredUploadController和只验证410兼容提示的RetiredUploadIntegrationTest；从OpenAPI移除/admin/uploads。商品创建/修改继续调用内部FileUploadService；管理员访问旧地址由统一异常处理返回404，未登录/无管理员权限仍先由AuthFilter处理。

本次清理验证：后端 `clean verify` 193项，192通过、1项浏览器条件测试跳过，无失败或错误；管理端33项测试通过。后端与桌面端OpenAPI副本完全一致，均通过YAML解析及内部引用检查。

## 1. 本次完成什么

所有路径都接在 `/api/v1` 后，且统一要求有效 ADMIN 账号。管理员和普通用户的区分仍由 `AuthFilter` 完成。

| 方法与路径 | 参数 | 返回 |
| --- | --- | --- |
| GET `/admin/wallpapers` | URL 中的 page、pageSize | 200：所有状态的商品分页 |
| POST `/admin/wallpapers` | multipart 资料、原文件、封面 | 201：新建 DRAFT 商品 |
| PATCH `/admin/wallpapers/{id}` | multipart 变化资料、可选的新文件 | 200：保存后的商品 |
| PUT `/admin/wallpapers/{id}/publication` | JSON 的 status | 200：上架或下架后的商品 |
| GET `/admin/wallpapers/{id}/cover` | 路径商品 ID | 图片字节，用于管理员预览所有状态的封面 |

最后一个是封面辅助入口，不是新增一种商品管理操作。公开封面 `/catalog/covers/{id}` 仍只允许读取上架商品。否则草稿图片也能被匿名访问。普通用户不能调用管理端封面入口。

成功 JSON 仍是 `{code:"OK",message:"",data:...,requestId:...}`。错误保留实际 HTTP 状态，`data:null`。图片响应不套 JSON。

商品没有 DELETE 接口；下架使用 WITHDRAWN，后续订单和权益仍能关联商品。本次没有实现交易及下载授权，也没有改 Qt 按 URL 缓存封面的逻辑。

## 2. 先理解一次创建的完整过程

管理员填写标题、分类、价格并选择两份文件，此时前端只在内存里保留 File，使用 Blob URL 预览封面。点击“创建草稿”后才发一次 POST。

```text
工作台 ApiClient.create
  → multipart/form-data 请求
  → AuthFilter：校验登录与ADMIN，将用户ID放入BaseContext
  → AdminWallpaperController：绑定表单，拒绝未知/重复字段
  → AdminWallpaperServiceImpl：校验资料与分类，协调文件和数据库
      → FileUploadServiceImpl：封面检查与上传，返回可信元数据
      → ProductFileCleanupService：记录这份新文件，尚未关联商品
      → FileUploadServiceImpl：原文件检查与上传
      → ProductFileCleanupService：记录第二份新文件
      → WallpaperPersistenceService.create：短数据库事务
          → 再查管理员、锁定并检查分类
          → AdminWallpaperMapper.insert：一行商品和两组文件信息
          → 提交事务
      → 删除新文件的待清理记录（文件已经有商品引用）
      → 将Entity转换成AdminWallpaperVO
  → Controller：ApiResponse.success，HTTP201
```

前端没有调用两个接口，也不需要管理员点两次上传按钮。后端内部仍会分别上传封面和原文件到 OSS：一次 HTTP 商品操作里包含多次内部存储操作。

先处理封面，因为它较小。封面不合格时可以尽早失败；如果封面已上传而原文件失败，回收本次封面。文件检查或上传期间不持有商品数据库锁。

## 3. DTO：接收的是什么

### AdminWallpaperQueryDTO.java

普通 Lombok `@Data` 类，`page` 默认1、`pageSize` 默认12。`@NotNull`、`@Min`、`@Max`配合 Controller 的 `@Valid` 拒绝非法分页，最大单页50条。管理列表目前不接收 keyword 和 categoryId。

### WallpaperMutationDTO.java

创建和修改共用一个表单 DTO，只有七个字段：

| 字段 | Java类型 | 创建 | 修改 |
| --- | --- | --- | --- |
| title | String | 必填，去首尾空白后1–120个Unicode字符 | 省略保留 |
| description | String | 可省略，默认空串，最多4000字符 | 空串清空；省略保留 |
| categoryId | String | 必填、启用分类 | 省略保留；允许保留原停用分类 |
| priceCents | Integer | 必填，0–100000000整数分 | 省略保留；0确实更新成免费 |
| creator | String | 可省略，默认空串，最多120字符 | 空串清空；省略保留 |
| originalFile | MultipartFile | 必填，最大10GiB | 省略保留；提交则替换 |
| coverFile | MultipartFile | 必填，最大4MiB、仅图片/GIF | 省略保留；提交则替换 |

`MultipartFile` 是 Spring 对收到的文件部分的封装，可以读取文件名、大小、输入流。DTO 不接收 MIME、宽高、SHA、Bucket、对象键、上传者、type、发布状态或旧资产 ID。这些数据由服务器产生或通过专门的上下架接口修改。

multipart 的价格部分实际是文本，例如 `29`。Controller 明确转换成 Integer，拒绝小数、科学计数法、负号、空串和整数溢出。创建表单没有 JSON 请求体，因此用 `@ModelAttribute` 而不是 `@RequestBody`。

### WallpaperPublicationDTO.java

这里请求体很小，使用 JSON 与 `@RequestBody`：

```json
{"status":"PUBLISHED"}
```

`@NotBlank`、`@Pattern` 只允许 PUBLISHED、WITHDRAWN。沿用 `StrictStringDeserializer` 防止数字被自动转成字符串；`@JsonAnySetter` 拒绝未知 JSON 字段。DRAFT 只能通过新建获得，不能把上下架接口当成任意状态修改接口。

## 4. Entity、VO、中间结果

### Wallpaper.java

Entity 表示商品资料和当前两份文件。`original` 和 `cover` 是两个 `UploadedFile` 对象，但数据库仍是一张 wallpapers 表，两组属性分别映射到 `original_*` 和 `cover_*` 列。嵌套 Java 对象不代表又建了两张文件表。

`categoryName` 是查询关联 categories 后得到的显示名称，不是 wallpapers 的新增列。真正保存关联的是 `categoryId`。

### UploadedFile.java

内部文件元数据：文件名、存储方式、Bucket、对象键、云对象版本ID、MIME、扩展名、大小、SHA、宽高、帧率、状态、上传者与时间。`FileUploadService` 产生它；Mapper 也用它装载商品行的文件字段。

`purpose` 用于内部上传分支，不单独存成商品字段；原文件或封面由字段前缀决定。对象版本ID仅帮助在开启多版本的 Bucket 中精确删除当前对象，不是已经取消的商品资源历史版本，不返回前端。

### AdminWallpaperVO.java

给工作台显示商品资料、状态与文件名。ID 与 sizeBytes 是字符串，避免 JavaScript 大整数精度问题。显示 width、height、fps、sha256 等服务器检测结果；不返回原文件位置、Bucket 或上传者。

历史手工草稿可能没有文件：相关媒体字段可以是 null，文件名/封面URL为空。这样的商品不能上架。通过新创建接口保存的商品要求两份文件完整。

管理员 coverUrl 指向 `/admin/wallpapers/{id}/cover`。公开商品 VO 仍生成 `/catalog/covers/{id}`。两者身份要求不同。

### WallpaperSaveResult.java

只用于两个 Service 之间传递“已保存商品”和“被替换的旧文件”。它既不是数据库表，也不是 HTTP 返回对象。旧文件必须等事务提交后再删除，所以需要把它们带回外层。

## 5. Controller 为什么这样写

`AdminWallpaperController` 用 `@Autowired` 注入 Service，按接口方法接收参数、返回统一结果。权限没有在每个方法中重复实现，由过滤器统一检查。

`configureBinding` 是唯一的 `@InitBinder` 回调，统一处理绑定来源、资料表单字段白名单和价格转换规则。它由 Spring 在绑定 DTO 前调用，不是创建接口手动调用的前置业务函数。仅 objectName 为 mutation 的商品表单应用字段与价格规则。新版 Spring 的表单绑定器也可能读取请求头：本接口关闭请求头绑定，避免标题、价格等被请求头填入。路径 ID 使用 `@PathVariable`，不属于表单资料。

`checkForm` 检查实际参数名、文件部分名称及重复提交。参数必须来自五个资料字段，文件必须来自 originalFile/coverFile。比如传两个 coverFile 或用一个文件部分冒充 title 都返回400。未知嵌套字段也不能修改 Entity。

列表使用 `@Valid @ModelAttribute` 绑定 query；另外检查 URL 中只有 page/pageSize 且各出现一次。创建返回201，修改与上下架返回200。封面使用 `ResponseEntity<Resource>` 指定真实媒体类型、大小、nosniff 和 no-store。

## 6. ServiceImpl：协调操作，不把上传塞进事务

`AdminWallpaperService` 是接口，声明四项商品业务操作；`AdminWallpaperServiceImpl` 是实现。

`getWallpapers` 创建 MyBatis-Plus Page，调用 Mapper，将 Entity 逐条用普通 for 循环转换成 VO。分页沿用项目原方案，没有加入 PageHelper。

`createWallpaper` 先校验表单和分类；`updateWallpaper` 还会按 ID 获取旧商品，留作上传期间并发修改的比较依据。普通字段错误尽量在上传前返回。

两个入口各自展开流程，不再先调用 `save`，然后根据快照是否为 null 分流。

创建方法依次执行：

1. `validateMutation(dto, true)`：本类检查创建必填、字符串和金额。
2. `persistence.checkCategoryBeforeUpload`：第一次普通 SELECT 检查分类；不持有数据库锁。
3. `uploader.uploadFile`：封面、原文件顺序检查及上传；每份上传后登记清理任务。
4. `persistence.create`：独立 Spring Bean 开启短事务，重新检查分类及权限，INSERT 商品。
5. `cleanup.completeUpload`：事务提交后移除新文件清理任务。
6. `toVO`：本类组织响应，交给 Controller 包装 HTTP 201。

修改方法独立展示相同阶段，但只上传本次选择的文件，用 `persistence.update` 锁定并修改最新商品，提交后再通过 `retireFile` 清理替换下来的旧文件。只改资料时不调用上传组件。

故意保留两段很短的上传/回收代码，让读者直接看到每项操作的实际顺序。业务主流程不再为减少几行重复代码而增加一层跳转。

try/catch 只围绕上传和事务保存。数据库已经提交后的清理与返回放在外面，避免把提交后的异常误读成“商品保存已经回滚”。

捕获 RuntimeException 时，把本次已经成功上传的文件交给清理服务。清理服务先查数据库引用，数据库不可用或提交结果不确定时保留任务，不贸然删除。

`toVO` 是显式转换，把私有存储字段留在后端。这里没有直接复制整个 Entity，因为内部有嵌套文件对象，也有不能对外返回的数据。

## 7. 普通检查现在在哪里

原 `WallpaperRules` 已移除，方法按实际使用位置归并：

| 方法 | 现在的位置 | 检查时机 |
| --- | --- | --- |
| validateMutation | AdminWallpaperServiceImpl 私有方法 | 上传前，普通资料检查一次 |
| databaseId | AdminWallpaperServiceImpl 私有方法 | 管理端路径ID转为数据库Long |
| checkCategory | WallpaperPersistenceService 私有方法 | 上传前普通SELECT；保存时锁定读复查 |
| checkReady / ready | WallpaperPersistenceService 私有方法 | 上架前，以及修改已上架商品后 |
| sameFile | WallpaperPersistenceService 私有方法 | 修改事务中比较上传前后文件位置 |
| mediaType | WallpaperPersistenceService 私有方法 | 从可信原文件扩展名得到商品类型 |

`validateMutation` 负责创建必填、修改至少一项、文字字符数、金额范围和分类ID格式。在同一个文件里就能查看主流程及这些条件。

ID 仍接收字符串，保留原来的外部ID和错误状态约定；价格是 multipart 文本，Controller 绑定时转换为 Integer。它们的目标分别是 Long 和 Integer，不会为了统一转换而把数据库BIGINT变成int。

文件本身的非空、大小、格式、媒体解析集中在上传组件。创建使用它刚生成的可信文件元数据，删除了 INSERT 前再次逐项检查完整文件对象的调用。上架则读取数据库中的当前文件信息，所以仍保留 `checkReady`，历史不完整草稿不能因此直接上架。

## 7.1 为什么分类检查保留两次

第一次检查是为尽早发现无效分类，避免上传几GB后才报错。第二次检查属于**并发复查**：上传期间另一个管理员可能停用或删除分类，之前通过并不代表现在通过。

第二次在 `create` / `update` 的事务里执行 SELECT FOR UPDATE，读取最新分类并持有行锁直至事务提交。第一遍不加锁，否则整个长上传期间其他分类操作都会等待。

停用分类仍可由原商品保留；新增或迁移到其他分类必须启用。修改事务以拿到商品锁后的最新原分类为准。

## 8. WallpaperPersistenceService：具体事务与锁

这个独立 Spring Bean 的 create、update、publish 标有 `@Transactional`。`checkCategoryBeforeUpload` 不开启事务，只用于上传前预检查。所有具体数据库状态检查和文件快照比较也在本文件内部。外层 Service 调用它时经过 Spring 代理，因此事务生效；不能简单在同一个类里用 `this.create()` 调用事务方法。

创建：再次确认账号仍是 ACTIVE/ADMIN，锁定分类，构造 DRAFT 商品并 INSERT 一行。代码用“【并发复查】”明确标出这次数据库状态重读，不再执行普通 DTO 或新文件的重复检查。管理员权限重读用于发现上传期间已经发生的停用/降级；没有对用户行加锁，并不承诺阻止读取之后的所有角色变化。两份文件元数据与商品资料一起写入。写库异常时事务回滚，外层回收新上传文件。

修改：用 `selectByIdForUpdate` 锁定当前商品并读取最新数据。两个修改请求同时到达时，后一个在这里等待，拿到锁后读取前一个提交的商品。只改变 DTO 中非 null 的资料字段；其他字段取最新记录，避免覆盖别人的资料修改。

如果本次要替换的文件与上传前快照不同，返回409 PRODUCT_CHANGED，让管理员刷新后决定；本次新文件会回收。只修改资料不需要这个文件比较。

更新记录时保持原发布状态。收集旧文件信息，提交后外层才删除旧对象。如果先删旧文件，再写库失败，数据库还指向已经被删掉的文件。

上下架：同样锁定商品，上架先检查文件完整性。状态变成 PUBLISHED 时更新上架时间，WITHDRAWN 保留之前的上架时间。同状态重复调用不改时间。它不进行 OSS 上传。

锁由数据库维护，在事务提交或回滚时释放。此处锁商品行和必要分类行，不是锁整个商品表。大文件传输、媒体检查、OSS 删除都不放在这些事务里。

## 9. Mapper 与 XML 怎么读

`AdminWallpaperMapper` 声明查询和更新方法；SQL 在 `mapper/catalog/AdminWallpaperMapper.xml`。`@Param` 命名多个参数，让 XML 的 `#{wallpaper.title}`、`#{replaceOriginal}` 等能找到对应值。所有输入值通过 `#{}` 绑定，没有拼接用户输入。

`resultMap` 先映射商品基本字段，再用两段 `<association>` 把原文件与封面列装入 UploadedFile。`notNullColumn` 表示对象键为空时不要创建一个全空的文件对象。它没有执行额外关联查询，只组装当前结果的一行。

查询的关联只有：

```sql
FROM wallpapers w
JOIN categories c ON c.id = w.category_id
```

例如商品 category_id=1，分类 id=1、name=自然，结果就获得 category_name=自然。文件信息在 w 的列中，不 JOIN 已删除的 assets。管理列表不按上架状态筛选，排序为 created_at降序、id降序。

`selectByIdForUpdate` 只查询 wallpapers，不做分类 JOIN，锁定商品用于修改或上下架。分类校验另外查询，职责更清楚。

`insert` 明确列出资料与两组文件列。`useGeneratedKeys=true`、`keyProperty=id` 让数据库自增 ID 回填到 Entity，随后返回字符串商品 ID。

`update` 使用 `<set>` 生成 SET。资料取已合并好的最新 Entity；两个 `<if>` 仅在确实替换文件时更新对应15列。没有传文件就不改那组列，发布状态也不在这条更新SQL里。

`updatePublication` 只修改状态、上架时间和更新时间。`countFileReferences` 查某个 OSS Bucket/对象键是否仍出现在任意商品的原文件或封面列，包括草稿和下架商品，不能只查公开列表。

分页插件另行添加 LIMIT 和执行 COUNT。沿用 `setOptimizeJoinOfCountSql(false)`，是让分页计数保留关联关系，和上述 JOIN 功能、数据库执行优化不是一件事。

## 10. 文件失败清理为什么还有一个Service

OSS 不参加 MySQL 事务，数据库回滚不会自动删除云文件。`ProductFileCleanupService` 用补偿操作处理这一点：

| 时机 | 做什么 |
| --- | --- |
| 新文件上传成功 | 写入本机待清理JSON；当前请求使用期间暂不执行删除 |
| 商品提交成功 | 移除新文件任务，文件留在OSS并由商品引用 |
| 上传/保存失败 | 查引用，无引用就删本次新文件 |
| 替换提交成功 | 为旧文件写任务，无其他商品引用再删 |
| OSS删除失败/数据库不可用 | 保留任务，默认每5分钟重试 |
| 服务重启 | 读取持久任务，重新查引用再决定是否删除 |

任务位于 `wallpaper.storage.root/.catalog-cleanup`，每批最多64项。任务只存 Bucket、对象键与精确删除所需的云对象ID；没有图片字节、密码或商品历史记录，不新增数据库表。JSON文件名由对象位置的哈希得到，写入先用临时文件再移动。

清理只处理本应用 UUID 上传路径下的 OSS 对象。仍被商品引用的对象不删除；LOCAL 或未知导入路径不自动删除。`OssStorage.deleteObject` 在有云版本ID时删具体版本，否则删普通对象；错误映射成业务异常，以便保留任务重试。

新文件任务不能落盘时保存失败，尝试立即回收文件。服务器配置的存储目录需要可写并持久保留；成功替换后若旧文件任务写盘和立即删除都失败，会记录需人工处理的错误。

这不是跨MySQL和OSS的分布式事务：在云上传成功但尚未记下结果就断电、云写超时而结果未知等极小窗口，仍可能留下对象。它覆盖普通失败、删除重试及已经登记任务的进程中断，不承诺所有云对象永远零残留。多实例部署需把任务协调机制升级；当前按本机单实例开发。

## 11. 沿用的媒体与OSS能力

`FileUploadServiceImpl` 在服务器检查文件名、扩展名、非空和大小，流式复制到临时文件并计算 SHA-256，不把10GiB文件读入内存。`MediaInspector` 检查文件签名，调用 ffprobe 获取真实媒体信息，用 ffmpeg 验证解码。文件名和客户端 Content-Type 不能证明文件安全有效。

实际文件名由服务器 UUID 生成，存入私有 Bucket。小文件普通上传，大文件分片；失败会处理未完成的分片。服务器临时文件在 finally 清理。上传并发槽位限制为2；繁忙返回429。该限制按单个后端进程的正在处理文件数计算，不是两个完整创建请求，也不是集群全局限制；槽位在 finally 释放。没有新增SDK或第二套分页插件。

本次复用已有 OSS_REGION/ENDPOINT/BUCKET/ACCESS_KEY 配置与 ffprobe/ffmpeg 路径，不在启动时预上传验证文件。RAM权限需允许目标前缀的写入、读取封面与删除对象；开启Bucket多版本时，精确删除使用 DeleteObjectVersion 权限。

## 12. 前端本次对应改动

`contracts.js`：移除两个资产ID要求，校验当前文件名与发布状态；建立表单字段白名单，文件必须是实际 File。旧版本字段会被拒绝。

`api.js`：create/update 把资料与 File 放入 FormData，不手写 multipart Content-Type，浏览器生成 boundary。保留Bearer、统一响应检查和失败状态，不自动重放写请求。保存超时为至少30分钟，大文件受网络、网关和服务器配置共同限制；超时后先刷新目录确认结果，再决定重试。

`app.js`：文件只在本页选择，点击保存一次提交；编辑只发送变化资料和新选文件。取消丢弃文件；保存中禁用重复提交。响应失败保留资料与选择，供管理员处理。

管理员封面由 ApiClient 带Bearer fetch，得到图片Blob后显示；凭证只发给当前后端的管理封面路径，不发到任意 coverUrl。列表刷新与编辑器关闭会释放Blob URL。匿名 `<img src>` 不能直接访问私有管理封面。

`mock-api.js` 与测试夹具同步改为 FormData，无 assets Map或独立上传路由。模拟媒体宽高/帧率是样例；模拟大文件SHA只处理前64MiB用于页面演练，不能当作真实下载校验值。真实后端计算完整文件哈希。

## 13. Apifox怎么测

1. 重启后端使新Controller生效；刷新工作台页面加载新JS。登录后在Auth中使用最新accessToken。
2. GET `/admin/wallpapers?page=1&pageSize=12`，初始空库应返回 items空数组、total=0。
3. POST `/admin/wallpapers`，Body选 form-data，title/categoryId/priceCents为文本，originalFile/coverFile为文件。priceCents填`29`代表0.29元。不要选JSON，不要填旧资产ID。
4. 记录返回的商品ID。PUT `/{id}/publication` 用JSON `{"status":"PUBLISHED"}`，此时公开列表与详情可见。
5. PATCH `/{id}` 用form-data，只传description可以修改介绍；只传originalFile会替换原文件并保留封面与发布状态。
6. PUT publication为WITHDRAWN，公开详情和封面404，管理端仍可查看和编辑。
7. 草稿封面用管理端coverUrl带Bearer请求。原文件下载仍等下一模块。

常见返回：400非法资料/重复字段，401登录失效，403非管理员，404商品或分类不存在，409分类停用、文件不完整或并发替换，413过大，415格式不支持，422媒体损坏，429上传繁忙，503存储/媒体工具/数据库暂不可用。意外数据库错误回滚并返回500统一错误；错误详情不会直接返回浏览器。

## 14. 验证如何对应代码

`AdminWallpaperIntegrationTest` 使用独立H2，真实Controller/过滤器/Service/事务/Mapper：验证创建、部分修改、上下架、三状态分页、草稿封面权限、非法表单、上传失败回收、写库失败回滚、删除重试、上传期间分类/角色变化、并发文件冲突与引用保护。云上传和读取用测试替身。

`AdminWallpaperMySqlIT` 使用回滚事务核对真正MySQL的43列插入、ID回填、嵌套结果映射、部分更新、公开可见性及CHECK约束。MySQL CHECK错误3819可能被Spring包装为UncategorizedSQLException，测试核对底层错误码，不假定异常包装类。

`src/test/admin-wallpaper-browser.cjs` 用Playwright真实浏览器访问独立Tomcat/H2后端，覆盖一次创建、授权预览、上架、multipart修改、取消无写入及下架。按需用 `-Dwallpaper.browser.tests=true` 开启；需本机工作台源码、Node与已安装的Playwright/MS Edge，14173端口供隔离静态页面使用。默认测试不要求浏览器。

工作台Node测试包含真实HTTP夹具的FormData与Bearer封面，保留价格、分类、401/403、取消、超时和响应协议检查。测试中的云替身通过不等于真实OSS配置已经联调通过；本次没有往真实Bucket写测试对象。

2026-10-13初次开发结果（不代表本次整理结果）：默认全量 `clean verify` 共166项，165通过、1个浏览器条件用例跳过；另行运行MySQL3项与浏览器1项，全部通过。工作台Node33项与格式检查通过。真实数据库中的测试账号已回滚，分类和商品仍保持原有空数据状态，没有新DDL迁移。

2026-10-14调用链整理验证：专项120项，119通过、1项浏览器条件测试跳过；随后清理旧编译产物执行完整 `clean verify`，195项中194通过、1项浏览器条件测试跳过，失败及错误均为0。商品集成测试覆盖上传期间分类停用/角色降级、并发文件冲突、数据库回滚与失败文件回收。云存储使用测试替身，本次没有上传真实OSS对象、修改数据库结构或重测两端前端。新的类/方法/参数/返回注释通过JDK语法树检查。

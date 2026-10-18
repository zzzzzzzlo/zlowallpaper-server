# catalog 公开查询开发说明

2026-10-13 第二次调整：assets 表已并入 wallpapers，文件信息直接保存商品行；独立上传已停用，创建商品将一次提交资料、原文件及封面。商品写接口尚未实现。以 [两次修改清单](E:/zlowallpaper_sever/docs/catalog-refactor-changes.md) 为当前设计。

2026-10-13 更新：上传代码已并入 catalog，商品只关联当前原文件和封面，取消商品资源历史表及 resourceVersion。商品返回当前原文件 sha256，客户端按文件哈希识别下载。完整调整见 [商品文件管理说明](E:/zlowallpaper_sever/docs/catalog-resource-development.md)。

实现日期：2026-10-10。依据 `docs/frintend-docks/openapi.yaml` 和 `BACKEND-HANDOFF.md`。本阶段完成分类查询、商品分页、商品详情；管理员分类/商品写操作、上传及购买下载不属于本次实现。

2026-10-11 补充：管理员分类管理四个接口已实现，见 [catalog-development2.md](E:/zlowallpaper_sever/docs/catalog-development2.md)。本文件保留第一阶段公开查询的说明与当时验收记录；商品管理写接口待实现，上传现已实现并移入 catalog。

## 从哪里读代码

源码根目录：`src/main/java/com/zlo/wallpaper`。

| 文件 | 职责 |
| --- | --- |
| catalog/vo/CategoryVO.java | 分类公开响应，只含字符串id、name |
| catalog/vo/WallpaperVO.java | 单张商品响应，列表与详情共用；不含原文件URL |
| common/vo/PageVO.java | 通用分页响应items/page/pageSize/total |
| catalog/dto/WallpaperQueryDTO.java | URL查询参数的默认值、类型和约束 |
| catalog/controller/CategoryController.java | 分类请求入口，包装ApiResponse |
| catalog/controller/WallpaperController.java | 分页与详情入口，通过@ModelAttribute接收查询DTO |
| catalog/service/impl/CategoryServiceImpl.java | 分类查询结果转VO |
| catalog/service/impl/WallpaperServiceImpl.java | 整理筛选条件、调用分页Mapper、组装商品VO及封面URL |
| catalog/mapper/CategoryMapper.java | 简单分类SQL，按sort_order/id升序 |
| catalog/mapper/WallpaperMapper.java | 声明商品分页、详情和只读封面查询方法 |
| src/main/resources/mapper/catalog/WallpaperMapper.xml | 实际商品联表SQL，分页由插件生成LIMIT/count |
| config/MybatisPlusConfig.java | 配置PaginationInnerInterceptor，单页最多50条，不回退到第一页 |
| common/BusinessException.java、GlobalExceptionHandler.java | 业务错误、校验错误和服务错误的统一HTTP/JSON响应 |

`model` 包里的 `CategoryRow`、`WallpaperRow` 是SQL查询结果对象，供Mapper到Service之间传数据。尤其WallpaperRow跨多张表，不把它伪装成单张wallpapers表的实体。后续商品写操作需要时再增加完整表实体。

VO和查询结果使用Java 21的record：自动提供构造器和读取方法，如id()，不需要Lombok；DTO使用普通类与getter/setter，方便Spring绑定URL参数。

调用路径：Controller → Service接口 → ServiceImpl → Mapper → SQL → 数据库。查询后的数据反向返回，Service转换成VO，Controller放入统一ApiResponse。

## 查询规则

- GET /categories：无需业务参数，只读enabled=1的分类；空结果是data:[]。
- GET /wallpapers：默认page=1/pageSize=12，page至少1，pageSize为1–50；非法类型或范围返回400 VALIDATION_ERROR。
- keyword最长100个Unicode字符；省略、空字符串、纯空白表示不搜索。标题搜索将百分号和下划线当作普通字符，而不是SQL通配符。所有SQL值使用参数绑定。
- categoryId省略或空白表示全部分类；指定不存在的分类得到空列表。外部ID契约是字符串，数据库生成十进制BIGINT；不能对应本库分类ID的字符串同样返回空列表。
- 只查询PUBLISHED商品及其READY封面、READY当前原文件资产。原文件连接条件是 original.id = w.original_asset_id，直接读取当前文件。
- 停用分类不隐式隐藏它的已上架商品。商品排序为published_at DESC/id DESC。
- total按全部筛选条件统计；超出末页返回空items并保留正确total与请求页码。
- GET /wallpapers/{id}：先校验契约要求的ID长度/字符范围；格式错误返回400。格式合法但不存在、不能对应本库ID、草稿、下架或资源未就绪均返回404 PRODUCT_NOT_FOUND。
- JSON返回ID/categoryId/sizeBytes字符串，priceCents为整数分，fps为数值；sha256 为当前原文件哈希。
- 成功与错误响应均使用code/message/data/requestId，requestId与X-Request-Id一致。错误保留实际HTTP状态，data为null。

## 封面文件

为使商品coverUrl可直接读取，补充内部只读入口 `/catalog/covers/{assetId}`。它不改变三个公开JSON接口结构，也不是资产管理或原文件下载接口。

查询先确认资产为READY/COVER且被至少一个PUBLISHED商品引用，再读取LOCAL存储文件。只接受图片媒体类型，校验大小与数据库记录一致且不超过4 MiB。路径规范化及真实路径检查阻止越出存储根目录；不把整个存储目录作为静态资源公开。成功返回图片字节、Content-Type和nosniff，无重定向、无需登录；失败用统一错误响应。

配置项：

- `wallpaper.storage.root` / 环境变量 `WALLPAPER_STORAGE_ROOT`：本机文件存储根目录。
- `wallpaper.public-base-url` / `WALLPAPER_PUBLIC_BASE_URL`：生成封面URL的API基础地址，默认http://127.0.0.1:实际配置端口/api/v1；远程部署时改为最终HTTPS地址。
- 文件位置是存储根目录加assets.object_key，例如 `covers/example.png`。本阶段没有自动上传文件，也没有写入演示数据。
- 封面现已支持私有 OSS，文件不存在返回404，存储服务不可用返回503。

## Apifox验证

先从项目根目录执行 `./scripts/start-backend.ps1`，或运行ZloWallpaperApplication，随后发送：

```http
GET http://127.0.0.1:8080/api/v1/categories
GET http://127.0.0.1:8080/api/v1/wallpapers
GET http://127.0.0.1:8080/api/v1/wallpapers?page=1&pageSize=12&keyword=星空
GET http://127.0.0.1:8080/api/v1/wallpapers?pageSize=51
GET http://127.0.0.1:8080/api/v1/wallpapers/999
```

GET无JSON请求体；筛选字段放Query参数。pageSize=51应返回400；不存在的商品返回404。有数据时，使用列表返回的真实id测试详情，再打开coverUrl检查图片。

数据库当前没有业务数据，所以分类数组、商品items为空且total=0是正常成功结果。没有假商品，也没有自动初始化管理员。

## 自动化验证

默认构建与H2测试：

```powershell
$env:JAVA_HOME = 'D:\Codex\software\jdk-21.0.12.1+1'
$env:MAVEN_USER_HOME = 'D:\Codex\software\maven-user-home'
.\mvnw.cmd -B -ntp -Dmaven.repo.local=D:/Codex/software/maven-repository verify
```

33项catalog集成检查真实执行Mapper SQL，覆盖分类状态、分页与总数、筛选、稳定排序、当前原文件和哈希、超大ID/字节数字符串、错误、CORS、实际封面字节和原文件访问限制。另有3项已有健康/CORS测试。

显式开启本机MySQL查询检查（默认verify不运行此类）：

```powershell
.\mvnw.cmd -B -ntp -Dmaven.repo.local=D:/Codex/software/maven-repository -Dcatalog.mysql.tests=true -Dtest=CatalogMySqlIT test
```

该检查使用local数据源，在Spring回滚事务中插入负数测试ID；不创建/修改表结构、不推进AUTO_INCREMENT、不留下用户或商品。测试中的临时管理员只是外键夹具，不能登录，且会随事务回滚。若更换数据库环境，执行前确认local确实指向本机开发库。

实现验收：默认35项测试和1项显式MySQL检查通过；打包JAR实际启动后，分类、默认商品列表及带参数列表返回200，非法分页返回400，未知商品和封面返回404。六个HTTP请求的requestId均与响应头一致，并有正确CORS头。七张表测试前后均为0行，AUTO_INCREMENT均为1。临时18080服务已停止，未留下后台进程。HTTP记录位于已忽略的 `.local-setup/catalog-http-result.json`。

分页插件参考：https://baomidou.com/plugins/pagination/

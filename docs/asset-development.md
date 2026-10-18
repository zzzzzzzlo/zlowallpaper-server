# asset 素材上传模块开发说明（历史）

> 历史文档（2026-10-15归档说明）：以下保留早期资产模型和独立上传的实现过程，不是当前接口使用指南。旧独立上传及其410提示路由均已删除；当前上传随商品创建/修改一次提交，见 [catalog-development3](catalog-development3.md)。

2026-10-13 后续实施更新：商品列表、一次multipart创建/修改、上下架和管理员封面已完成；旧资产模型、独立上传和待开发描述仅作历史记录。当前实现与失败清理以 [catalog-development3](E:/zlowallpaper_sever/docs/catalog-development3.md) 为准。

2026-10-13 第二次调整：assets 表已并入 wallpapers，文件信息直接保存商品行；独立上传已停用，创建商品将一次提交资料、原文件及封面。商品写接口尚未实现。以 [两次修改清单](E:/zlowallpaper_sever/docs/catalog-refactor-changes.md) 为当前设计。

2026-10-13 更新：上传代码已并入 catalog，商品只关联当前原文件和封面，取消商品资源历史表及 resourceVersion。商品返回当前原文件 sha256，客户端按文件哈希识别下载。完整调整见 [商品文件管理说明](E:/zlowallpaper_sever/docs/catalog-resource-development.md)。

完成日期：2026-10-12。本阶段实现管理员上传接口及已有公开封面接口的 OSS 读取支持。
代码保持 Controller → Service 接口 → ServiceImpl → Mapper 的结构，使用普通 Java 类、Lombok、@Autowired 和 Mapper XML。

## 1. 本次完成的业务

`POST /api/v1/admin/uploads` 接收 `multipart/form-data`，成功 HTTP 201，返回现有 OpenAPI Asset。

| 参数 | 类型 | 含义 |
| --- | --- | --- |
| file | 文件 | 真正上传的文件内容 |
| purpose | 字符串 | COVER 为封面，ORIGINAL 为壁纸原文件；区分大小写 |

文件最大值：封面 4 MiB（4194304 字节），原文件 10 GiB（10737418240 字节）。
原文件支持 jpg/jpeg/png/bmp/webp/gif/mp4/webm/mov/avi；封面支持上述图片和 GIF，不支持视频。
不能通过改扩展名或伪造浏览器 Content-Type 把 HTML、SVG、脚本或其他格式作为壁纸上传。

本阶段在当前请求中读取媒体信息、检查首帧，检查通过后上传原始字节并返回 READY。
不生成缩略图、不转码、不返回 PROCESSING、不增加未评审的资产轮询接口。
这明确了首期实现与交接文档中“未来部分媒体检查后台执行”的区别；以后加入耗时后台任务时仍需补齐状态查询契约。

注意：READY 表示本阶段规定的签名、元数据及首帧检查通过，不保证整个视频的每一帧都已解码验证。
大文件全片解码、转码、异步审核需要后续单独设计，不能把首帧检查描述成完整视频验收。

## 2. 一次上传如何经过各层

```text
客户端：POST multipart(file, purpose)，Authorization: Bearer accessToken
    ↓
RequestIdFilter：为请求生成/记录 requestId
    ↓
AuthFilter：验证登录状态、数据库当前角色 ADMIN，设置 BaseContext 用户 ID
    ↓
AssetController：绑定 DTO、校验参数，调用 AssetService
    ↓
AssetServiceImpl：校验用途和文件名，流式保存临时文件并计算真实大小/SHA-256
    ↓
MediaInspector：真实签名 → ffprobe 元数据 → ffmpeg 首帧解码
    ↓
OssStorage：小于等于 8 MiB 普通上传，更大文件按 8 MiB 分片上传
    ↓
AssetPersistenceService：短事务调用 AssetMapper.insert，提交资产记录
    ↓
AssetServiceImpl：Entity 转 VO、清理临时文件
    ↓
AssetController：返回 HTTP 201 + ApiResponse<AssetVO>
```

身份校验只有 AuthFilter 一处。上传 Service 读取 BaseContext 取得上传者，不接收前端指定的 uploadedBy。
本次没有额外向 OSS 发“小文件预验证”请求，也没有启动时 Bucket 检查。
OSS 配置、权限或网络问题在真实上传请求里返回可读错误；编译与自动测试仍正常执行。

## 3. 文件导航和建议阅读顺序

| 顺序 | 文件 | 负责的事情 |
| --- | --- | --- |
| 1 | [AssetUploadDTO.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/dto/AssetUploadDTO.java) | 接收表单参数 |
| 2 | [AssetVO.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/vo/AssetVO.java) | 规定前端得到哪些字段 |
| 3 | [Asset.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/entity/Asset.java) | 对应 assets 表 |
| 4 | [AssetController.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/controller/AssetController.java) | 声明路由和返回状态 |
| 5 | [AssetService.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/service/AssetService.java) | 声明上传业务方法 |
| 6 | [AssetServiceImpl.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/service/impl/AssetServiceImpl.java) | 串联整个上传流程 |
| 7 | [MediaInspector.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/media/MediaInspector.java) | 检查真实文件与媒体信息 |
| 8 | [OssStorage.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/storage/OssStorage.java) | 调用 OSS SDK |
| 9 | [AssetPersistenceService.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/service/AssetPersistenceService.java) | 管理数据库短事务 |
| 10 | [AssetMapper.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/mapper/AssetMapper.java) | 声明数据库操作 |
| 11 | [AssetMapper.xml](E:/zlowallpaper_sever/src/main/resources/mapper/catalog/AssetMapper.xml) | 编写 INSERT/SELECT SQL |

## 4. DTO、VO、Entity 和中间模型

### AssetUploadDTO

`MultipartFile file` 是 Spring 对上传文件的封装，可取得文件流、客户端文件名和大小。
这里通过文件流读内容，不使用 getBytes 把一个 10 GiB 文件装进 byte[]。
`@NotNull` 检查是否提交；`@Pattern` 检查 purpose 只能为 ORIGINAL/COVER。
空文件需要 Service 进一步检查，@NotNull 不能证明文件里面有字节。
Getter/Setter 供 Spring 表单绑定使用，没有手写重复的 get/set。

### AssetVO

只有十个约定字段：id、purpose、status、mimeType、fileExtension、sizeBytes、sha256、width、height、fps。
id/sizeBytes 是字符串，避免 JavaScript 大整数精度问题并保持既有接口契约。
不返回 Bucket、对象键、上传者、密钥、版本 ID 或原文件可访问地址。
上传返回的是资产句柄，不代表用户获得付费原文件下载权限。

### Asset Entity

属性对应已有 assets 表，MyBatis 把 snake_case 列名映射为 camelCase Java 属性。
文件本体放 OSS，表内只保存位置和元数据。
storageProvider 固定写 OSS，bucket 是配置的 Bucket，objectKey 是后端生成的 UUID 路径。
数据库原默认 LOCAL 保持不变；上传 SQL 显式写 OSS，既有本地资产仍能读取。
uploadedBy 从当前认证上下文取得；createdAt/updatedAt 使用项目现有 UTC Clock。
本次已有表能满足要求，没有新增迁移 SQL，也没有重建表。

### MediaMetadata 与 StoredObject

[MediaMetadata.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/model/MediaMetadata.java) 保存媒体检查得出的 MIME、宽高、fps。
这是处理中间结果，先交给 Service 再填入 Entity，不直接返回给前端。

[StoredObject.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/model/StoredObject.java) 保存 OSS 返回的 bucket/objectKey/versionId。
`@AllArgsConstructor` 生成全参数构造器，`@Getter` 生成取值方法。
versionId 是云存储自身的对象版本，用于清理本次上传版本；它仅用于云端失败补偿，商品历史版本已取消。
每次上传使用新 UUID 对象键，商品换资源时不会覆写旧对象。

## 5. Controller 逐段阅读

`@RestController` 让返回对象转换成 JSON。
`@Autowired AssetService` 让 Spring 注入实现类。
`@PostMapping(value="/admin/uploads", consumes=MULTIPART_FORM_DATA_VALUE)` 指定 POST 和请求类型。
`@ResponseStatus(CREATED)` 指定成功 HTTP 201。
`@Valid @ModelAttribute AssetUploadDTO dto` 把 multipart 文件和文本字段绑定到 DTO，随后校验。
上传文件不是 JSON 对象，因此这里不使用 @RequestBody。
`HttpServletRequest request` 仅用于取 requestId，和 auth/catalog 的统一响应方式相同。
Controller 调用 Service 后使用 ApiResponse.success 包装结果，没有写 SQL 或 SDK 操作。

## 6. AssetServiceImpl 各方法

### uploadAsset：主流程

1. 取 BaseContext 用户 ID；缺少上下文时拒绝调用，不自行从客户端参数取身份。
2. 判断用途、空文件和声明大小；COVER/ORIGINAL 使用不同限制。
3. 整理原文件名并检查扩展名。
4. 取得 Semaphore 上传名额，再创建服务器随机命名的临时文件。
5. 流式复制并计算 SHA-256，检查实际大小；调用媒体检查工具。
6. 填 Entity：文件位置、真实 MIME/宽高/fps、READY、上传者和时间。
7. 调用 OssStorage 上传，取得 Bucket/版本结果。
8. 调用独立 Bean 的 saveAsset，事务提交成功后转换 VO。
9. 若数据库写入或提交失败，调用 deleteAfterFailure 清理刚上传对象，再抛出原异常。
10. finally 无论成功失败都会尝试删除临时文件并归还上传名额。

Semaphore 可以理解为两个停车位：同一后端进程同时最多执行两个上传业务，拿不到名额返回 429。
它用于限制检查/上传任务数量，不是数据库事务锁，也不能跨多个后端实例统一限流。
Spring/Tomcat 在进入 Controller 前仍可能缓存 multipart，因此它不能完全限制请求接收阶段的临时磁盘使用。

### copyAndHash：边读、边写、边计算

用 64 KiB buffer 从 MultipartFile 的 InputStream 读取内容，同时写临时文件、累计 size、更新 MessageDigest。
这样即使文件较大，也不需要相同大小的内存。
超过对应上限立即抛出 413，不只相信 getSize 返回的声明值。
实际字节数必须非零并且和 MultipartFile 大小一致。
HexFormat 把 SHA-256 的 32 字节结果转换为 64 位小写十六进制字符串。
这里的 SHA-256 用于校验文件字节，与 auth 中慢速密码哈希的业务用途不同。

### filename / extension

filename 去掉 Windows/Linux 路径部分，只保留最后的名字；拒绝空值、控制字符和超过255 Unicode字符的名字。
它只保存作展示，不参与 OSS 对象键生成。
extension 读取最后一个点后的内容，用 Locale.ROOT 转成小写，再按允许列表判断。
扩展名通过不代表真实格式通过，还要交给 MediaInspector。

### toVO / validation / tooLarge

toVO 显式设置十个返回字段，转换 Long ID 和字节数为 String。
validation 创建 HTTP 400 的 BusinessException。
tooLarge 创建 HTTP 413、FILE_TOO_LARGE 错误。
Service 抛异常，ControllerAdvice 按现有响应包装处理，前端不会拿到异常堆栈。

## 7. MediaInspector 为什么需要两个媒体工具

ffprobe 读取容器、编码、尺寸、时长和帧率；ffmpeg 实际解码首帧，拦截部分只有文件头而内容损坏的文件。
本机已有工具，不需要下载安装，也不在此阶段压缩、转码或改动上传字节。
静态图片的 fps 固定0，GIF/视频从 avg_frame_rate 读取，必要时尝试 r_frame_rate。
例如 30000/1001 得到约29.970；DECIMAL(10,3) 保留三位小数。

### inspect

先拒绝视频作封面，再检查签名，随后执行 ffprobe。
选择非 attached_pic 的视频流，防止把音频文件附带的封面误当视频主体。
要求宽高大于0，像素总数不超过5000万，控制首帧解码内存。
视频还要求正时长和正fps；确认容器/编码与扩展名相符后执行首帧解码。
返回服务器实际测出的 MediaMetadata。

### checkSignature / starts / ascii

只读文件开头最多4096字节。
PNG/JPEG/GIF/BMP 各有自己的魔数；WebP 和 AVI 都是 RIFF，但后面的 WEBP/AVI 标识不同。
MP4/MOV 进一步区分 ftyp 和 QuickTime 品牌；WebM 要包含 EBML 签名及 webm 标识。
starts 做无符号字节比较，ascii 比较某个偏移处的 ASCII 标记。
这些只是第一道判断，真正的元数据和解码检查在后面。

### verifyFormat / frameRate / mimeType

verifyFormat 将 ffprobe 返回的容器/编码与扩展名交叉验证。
frameRate 解析“分子/分母”，防止除零，并用 BigDecimal 保留三位小数。
mimeType 返回服务端确定的标准媒体类型，不使用浏览器发来的 Content-Type。

### execute / deleteTemporary

ProcessBuilder 逐个传递参数，不通过 cmd/PowerShell 执行命令，不使用客户端文件名构建命令行。
输入只使用服务器临时文件，protocol_whitelist 限制 file/pipe，阻止媒体播放列表拉取网络资源。
进程输出和错误写入临时文件，避免管道塞满造成等待；输出 JSON 最大1 MiB。
每次工具调用最多20秒，超时结束进程并拒绝媒体，缺少程序返回503。
错误详情不原样返回给前端；finally 清理进程输出临时文件。
两个工具依次调用，媒体处理阶段最多约40秒，云上传另有网络耗时。

## 8. OSS 配置与 SDK 调用

### 配置文件

[OssProperties.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/config/OssProperties.java) 绑定 wallpaper.oss：endpoint、region、bucket、accessKeyId、accessKeySecret。
只用 Getter/Setter，不生成包含密钥的 toString。为空时允许应用启动，真实上传请求返回配置错误。

[UploadProperties.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/config/UploadProperties.java) 绑定工具路径和超时时间。
[AssetConfig.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/config/AssetConfig.java) 使用 EnableConfigurationProperties 注册这两个配置 Bean。

application.yml 从环境或 local 导入的 .env 读取配置：

```dotenv
OSS_BUCKET=zlowallpaper-oss
OSS_REGION=cn-guangzhou
OSS_ENDPOINT=https://oss-cn-guangzhou.aliyuncs.com
OSS_ACCESS_KEY_ID=本机填写
OSS_ACCESS_KEY_SECRET=本机填写
# 可选：默认从 PATH 查找
FFPROBE_PATH=D:/MYSY2/mingw64/bin/ffprobe.exe
FFMPEG_PATH=D:/MYSY2/mingw64/bin/ffmpeg.exe
```

本次规范了本机 .env 的 Region/Endpoint 写法，并填入已安装的 ffprobe/ffmpeg 绝对路径，不打印或替换用户密钥。
修改 .env 后重启后端；.env.example 是不含真实凭证的模板。

### OssStorage.getClient

第一次真实请求才创建客户端，使用同步方法防止并发创建多个实例，后续请求复用。
检查必要配置，Region 不能写成 oss-cn-guangzhou；Endpoint 使用阿里云 HTTPS 域名。
缺少协议的 Endpoint 会补 https://。
采用 OSS SDK V1 3.18.4，与苍穹外卖常见写法同一系列，开启 V4 签名并填写 Region。
连接超时5秒、套接字超时60秒；关闭 SDK 写请求自动重试，避免未确认成功的上传被无意重放。
close 使用 PreDestroy，在应用关闭时释放客户端连接资源。

### upload / metadata

8 MiB以内使用 putObject，InputStream 读取已校验文件，ObjectMetadata 设置真实 MIME、大小和禁止覆盖标记。
请求返回 versionId；对象键每次使用 UUID，因此不会主动覆盖已上传资源。
多版本 Bucket 中禁止覆盖标记本身不生效，仍依靠新对象键避免覆盖旧对象。

### uploadMultipart

先 initiateMultipartUpload 取得 uploadId，然后按8 MiB循环：
FileChannel.position(offset)定位文件 → uploadPart → 收集 PartETag。
最后 completeMultipartUpload 按序合并。
10 GiB最多1280片，低于 OSS 10000片上限。
当前是服务器到OSS分片，前端仍一次multipart请求；不代表前端已经支持断点续传或字节进度。
失败时尝试 abortMultipartUpload，补偿失败记录对象键供排查，同时保留原始业务失败原因。

### deleteAfterFailure

数据库保存失败时清理刚上传对象。
未开启多版本时调用 deleteObject；返回了versionId时调用 deleteVersion，精确删除本次版本。
多版本 Bucket 仅普通 deleteObject 通常会创建删除标记，历史字节仍保留。
因此 RAM 策略需要补充 oss:DeleteObjectVersion；没有该权限时正常上传仍可成功，但失败补偿可能留下孤立版本。
清理失败会记录 bucket/objectKey/versionId，不向用户返回 SDK 原始消息或密钥。

### translate

AccessDenied → 503 OSS_PERMISSION_ERROR，表示后端云权限不足，不是前端用户没有管理员权限。
无效密钥、签名、Bucket或地域配置 → 503 OSS_CONFIGURATION_ERROR。
网络或其他存储问题 → 503 OSS_UNAVAILABLE。
日志仅记录 OSS errorCode/requestId，便于排查，不打印原始授权请求。

### readCover

仅供已有封面业务调用，检查允许大小后 getObject，限制读取最多4 MiB并核对真实字节数。
找不到对象返回404，其他云存储失败保持503。
这个方法本身不提供 HTTP 任意对象读取接口。

## 9. SQL 和事务

AssetMapper.insert 在 XML 中列出16个要写入的字段。
参数使用 #{} 绑定，MyBatis 不把文件名等用户文本拼进 SQL。
useGeneratedKeys=true/keyProperty=id 让数据库自增ID回填到同一个 Asset 对象。
selectById 读取明确字段列表，主要用于已验证的 OSS 封面找到原存储 Bucket。
上传入库本身没有 JOIN，不需要分页插件。

AssetPersistenceService.saveAsset 使用 @Transactional 包住插入和结果检查。
它是独立 Spring Bean：ServiceImpl调用该Bean，才经过事务代理；不能在同一对象内自调用另一个@Transactional方法期待自动生效。
事务结束之后才返回上传 Service，提交失败也会被外层捕获，执行云补偿。
媒体检查和OSS上传不在长数据库事务里运行，不长时间占用数据库连接。

MySQL事务只能回滚表中的修改，不能替我们撤销已经上传到云端的文件。
当前使用失败补偿：上传成功 → 入库失败 → 清理云对象。
这不是跨系统原子事务。若后端进程中途崩溃、云请求超时但服务端实际成功，或清理失败，可能留下孤立文件/版本。
长期运行时还需设计孤立资产扫描、未完成分片生命周期和持久化补偿任务；本次没有假装这些功能已经完成。

## 10. 已有封面接口如何读取 OSS

[CoverService.java](E:/zlowallpaper_sever/src/main/java/com/zlo/wallpaper/catalog/service/CoverService.java) 继续先调用 selectPublicCover。
该SQL必须满足：资产purpose=COVER、status=READY，并且存在上架商品引用它。
检查通过后，LOCAL仍按原目录限制读取；OSS则读取资产记录中的Bucket，并通过OssStorage取得最多4 MiB字节。
原 CoverController 继续返回图片字节与真实 Content-Type，不返回 JSON，不重定向到临时签名地址。
未被商品引用、草稿引用、原文件资产都不能通过这个接口公开读取。
商品管理还没实现，所以仅上传封面后直接访问 coverUrl 得到404是正确行为。

## 11. 统一错误响应

GlobalExceptionHandler 新增 multipart 大小与格式异常处理，保持现有 ApiResponse 外壳。

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | 缺参数、空文件、错误用途或文件名、损坏multipart |
| 401 | SESSION_EXPIRED | 登录凭证无效 |
| 403 | FORBIDDEN | 当前角色不是ADMIN |
| 413 | FILE_TOO_LARGE | 文件或请求超过限制 |
| 415 | UNSUPPORTED_FILE_TYPE / VALIDATION_ERROR | 不支持文件扩展名、视频作封面或请求类型不符 |
| 422 | INVALID_MEDIA / MEDIA_CHECK_TIMEOUT | 真实格式、元数据或首帧检查失败、媒体检查超时 |
| 429 | UPLOAD_BUSY | 当前两个上传业务名额已占用 |
| 503 | OSS_CONFIGURATION_ERROR | 云存储配置问题 |
| 503 | OSS_PERMISSION_ERROR | RAM权限不足 |
| 503 | OSS_UNAVAILABLE | 云存储网络/服务失败，结果可能未确认 |
| 503 | MEDIA_SERVICE_UNAVAILABLE / UPLOAD_IO_ERROR | 工具或本地临时文件不可用 |
| 503 | SERVICE_UNAVAILABLE | 数据库暂不可用 |
| 500 | INTERNAL_ERROR | 其他未预期错误 |

失败 data=null，保留 requestId。超时不代表服务端一定没保存，前端不应自动重放上传请求。

## 12. RAM 策略补充

当前私有Bucket保持私有和阻止公共访问；无需为了显示封面改为公共读。
针对本项目专用Bucket的策略可以使用：

```json
{
  "Version": "1",
  "Statement": [{
    "Effect": "Allow",
    "Action": [
      "oss:PutObject",
      "oss:GetObject",
      "oss:DeleteObject",
      "oss:DeleteObjectVersion",
      "oss:AbortMultipartUpload",
      "oss:ListParts"
    ],
    "Resource": ["acs:oss:*:*:zlowallpaper-oss/*"]
  }]
}
```

编辑现有自定义策略，补上 DeleteObjectVersion 后保存新版本并设置为默认生效版本。
应用不通过SDK自行修改RAM授权，不使用主账号密钥。

## 13. Apifox 联调步骤

1. 重启后端，保持local环境加载根目录.env；把ffprobe/ffmpeg加入后端进程PATH，或在.env填写绝对路径。
2. 登录项目的管理员账号，取accessToken；阿里云RAM用户不是项目登录账号。
3. 请求地址 http://127.0.0.1:8080/api/v1/admin/uploads，方法POST。
4. Auth选择Bearer Token，填写当前令牌变量；Body选择form-data。
5. 添加file行，类型选File，选择真实图片；添加purpose行，类型Text，填写COVER。
6. 由Apifox自动生成multipart Content-Type和boundary，不手工填application/json。
7. 发送真实上传，预期201、code=OK、status=READY，记录data.id。
8. 上传原文件时purpose改ORIGINAL，分别保留原文件和封面ID，后续商品创建使用。

示例响应字段（ID/哈希由实际上传产生）：

```json
{
  "code": "OK",
  "message": "",
  "data": {
    "id": "21",
    "purpose": "COVER",
    "status": "READY",
    "mimeType": "image/png",
    "fileExtension": "png",
    "sizeBytes": "12345",
    "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "width": 1920,
    "height": 1080,
    "fps": 0
  },
  "requestId": "请求追踪编号"
}
```

如果项目还没有ADMIN用户，可先注册专用管理测试账号，再由你在本机数据库明确提升该账号角色；程序不会把注册用户自动提升为管理员。
只给指定账号改role，不要批量修改所有用户。过滤器每次读取当前角色，不需要为了角色变更重新签发令牌，但accessToken本身仍须有效。

在数据库GUI中选择商城库，替换下面的用户名，先查询确认账号，再执行仅针对该用户名的更新：

```sql
SELECT id, username, role FROM users WHERE username = '你指定的管理测试账号';
UPDATE users SET role = 'ADMIN', updated_at = UTC_TIMESTAMP(3)
WHERE username = '你指定的管理测试账号' AND role = 'USER';
```

模块开发验收时没有自动置备ADMIN。后续按用户明确要求将userzlo设为ADMIN并重置密码，密码由本机私密文件交付；MySQL测试用户及资产残留均为0。

2026-10-12工作台联调修复：前端登录后通过已实现的GET /admin/categories验权，再独立加载商品目录。当前GET /admin/wallpapers尚未实现，目录显示404不影响进入工作台和分类管理。刷新页面重新登录即可加载修复后的前端脚本。

错误排查：403看项目用户role；503 OSS_PERMISSION_ERROR看RAM策略；503配置错误看Bucket/Region/Endpoint/密钥；503媒体工具错误看进程PATH。
客户端上传超时目前120秒，大原文件经本机中转可能超过此窗口。虽然服务端字节上限10 GiB，本次没有实测10 GiB云上传耗时，也不保证弱网大文件在120秒内完成。

## 14. 验证结果和复现

已通过默认153项测试（已有114项 + 本次39项），mvn verify打包成功。
本次39项：AssetUploadIntegrationTest 19项，OssStorageTest 8项，MediaSignatureTest 12项。
它们不访问真实OSS、不读取本机云密钥：测试真实过滤器/Controller/Service/SQL，替代外部OSS和媒体执行。
验证权限撤销、严格用途/文件名、空文件、字节上限、真实SHA、UUID键、云失败、数据库失败补偿及公开封面限制。

另有显式开启的真实媒体测试MediaToolsIT，共13项，覆盖十种扩展名、中文空格路径、首帧、坏文件、视频作封面和缺失程序。
真实MySQL测试AssetMySqlIT，共3项，验证自增ID、Unicode文件名、属性映射、上传者外键、READY约束和精确字节边界。
MySQL记录全部回滚；自增ID计数出现间隙是数据库正常行为。
按用户要求，没有额外执行真实OSS预上传或云资源清理测试；云凭证和RAM权限仍需首次真实请求验证。

```powershell
$env:JAVA_HOME = 'D:/Codex/software/jdk-21.0.12.1+1'
# 当前受限执行环境显式加载测试用Byte Buddy agent，避免Mockito自附加等待。
$agentOption = '-DargLine=-javaagent:D:/Codex/software/maven-repository/net/bytebuddy/byte-buddy-agent/1.17.8/byte-buddy-agent-1.17.8.jar'
& 'D:/Codex/software/apache-maven-3.9.16/bin/mvn.cmd' -B -ntp '-Dmaven.repo.local=D:/Codex/software/maven-repository' $agentOption verify
& 'D:/Codex/software/apache-maven-3.9.16/bin/mvn.cmd' -B -ntp '-Dmaven.repo.local=D:/Codex/software/maven-repository' $agentOption '-Dtest=MediaToolsIT' '-Dmedia.tools.tests=true' test
& 'D:/Codex/software/apache-maven-3.9.16/bin/mvn.cmd' -B -ntp '-Dmaven.repo.local=D:/Codex/software/maven-repository' $agentOption '-Dtest=AssetMySqlIT' '-Dasset.mysql.tests=true' test
```

官方资料：[OSS SDK V1](https://github.com/aliyun/aliyun-oss-java-sdk)、[分片上传](https://help.aliyun.com/zh/oss/developer-reference/java-multipart-upload)、[删除对象版本权限](https://help.aliyun.com/zh/oss/developer-reference/deleteobject)、[ffprobe](https://ffmpeg.org/ffprobe.html)。

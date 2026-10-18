# 我的壁纸与下载授权开发说明

2026-10-16补充：下文开发当时暂缓的前端适配已完成，包括个人信息/余额、壁纸币付款、已购封面Bearer、停售详情与封面缓存。当前状态见[前端统一修复说明](frontend-reconciliation.md)。

本次实现两个业务接口及一个配套封面入口。按当前约定，“我的壁纸”放在catalog，下载授权放在download；不新增数据库表或字段，不恢复assets、商品历史版本或resourceVersion。

## 1. 接口归属和返回内容

| 接口 | 模块 | 成功结果 |
| --- | --- | --- |
| GET /me/wallpapers | catalog | HTTP 200，当前账号拥有的WallpaperVO分页（2026-10-17更新） |
| GET /me/wallpapers/{id}/cover | catalog | HTTP 200，已购商品当前封面图片字节 |
| POST /wallpapers/{id}/download-grants | download | HTTP 200，DownloadGrantVO，包含短时原文件下载URL |

三个入口都由AuthFilter验证Bearer登录，普通用户可访问自己的内容，不要求管理员。没有身份或账号禁用返回401。

已购列表包含停售商品，也不因为分类停用而消失。数据来自当前商品和当前文件；订单列表来自历史成交快照，两者用途不同。退款接口尚未实现；未来删除权益后，新列表、封面访问和下载申请都会失去相应权限。

## 2. 我的壁纸：一条完整调用链

`AuthFilter → WallpaperController.getMyWallpapers → WallpaperServiceImpl.getMyWallpapers → WallpaperMapper.selectOwnedWallpapers → toVO → ApiResponse`

1. AuthFilter验证令牌、会话及当前账号状态，把用户ID放入BaseContext。
2. Controller不接收用户ID，调用WallpaperService。前端添加userId查询参数也不能改变查询账号。
3. Service读取可信用户ID，调用WallpaperMapper新增的已购查询。多读一条用于识别业务上限。
4. Mapper只查询该用户权益关联的商品，不添加“必须上架”条件。
5. Service使用普通for循环，生成已购封面入口，再复用商品字段转换toVO。
6. Controller包装统一code/message/data/requestId，返回数组；没有权益返回空数组。

2026-10-17更新：已购查询改为分页，默认每页12项、最多50项，不限制总数量；data为items/page/pageSize/total。取消原200项上限及OWNED_LIST_LIMIT_EXCEEDED错误，完整调用链及客户端适配见 [已购分页](owned-pagination.md)。订单列表继续沿用既有1000项上限。下文旧数组示例和200项测试记录属于初版历史记录，以本段及当前OpenAPI为准。

## 3. WallpaperMapper：公开查询与已购查询分开

原有selectPublishedPage、selectPublishedById、selectPublicCover及其上架判断保持不变。没有增加一个对任意人开放的“查询未上架商品详情”方法。

新增的已购列表SQL结构如下，实际字段复用XML里的publicColumns片段：

```sql
SELECT w.id, w.title, w.description, w.category_id, c.name AS categoryName,
       w.type, w.price_cents, w.currency, w.original_width, w.original_height,
       w.original_fps, w.original_sha256, w.original_size_bytes, w.creator
FROM entitlements e
JOIN wallpapers w ON w.id = e.wallpaper_id
JOIN categories c ON c.id = w.category_id
WHERE e.user_id = #{userId}
ORDER BY e.granted_at DESC, e.id DESC
LIMIT #{limit}
```

- e关联w：e.wallpaper_id对应w.id，只取当前账号已拥有的商品。
- w关联c：w.category_id对应c.id，读取分类名称，不用分类启停状态剥夺权益。
- userId从BaseContext取得，参数用#{}绑定，不拼接输入。
- 排序依据权益取得时间，时间相同再按权益ID倒序。
- selectOwnedWallpapers仅由WallpaperServiceImpl.getMyWallpapers调用，公开列表和详情不会误用。

例如，甲拥有商品8、乙拥有商品9，且两件商品都停售：甲的查询只关联出商品8，乙只关联出商品9；匿名公开列表两件都不展示。

## 4. 为什么还要配套已购封面入口

公开/catalog/covers/{id}通过selectPublicCover限制PUBLISHED，不能直接用来展示已购停售商品封面。

本次已购列表返回的coverUrl形如：

```text
http://127.0.0.1:8080/api/v1/me/wallpapers/8/cover
```

调用链为：`AuthFilter → CoverController.readOwned → CoverService.readOwned → WallpaperMapper.selectOwnedCover → readFile`。

selectOwnedCover同时限制e.user_id、商品ID以及READY封面，不能读取其他人的未购商品。readFile复用已有OSS/本地封面读取、实际大小与路径校验，不重复实现文件传输。无权益或封面不可用返回404，匿名返回401。

该响应直接输出图片，不包JSON，带Cache-Control: no-store。读取这张图片必须携带当前账号Bearer；只登录获取列表不代表匿名图片请求自动具备身份。Qt图片加载后续集中改造，本次未修改界面。

## 5. 下载授权：完整业务顺序

`AuthFilter → DownloadController.createDownloadGrant → DownloadServiceImpl.createDownloadGrant → DownloadMapper.selectOwnedDownloadFile → OssStorage.createDownloadUrl → DownloadGrantVO`

DownloadServiceImpl的入口方法按以下顺序直接写出主流程：

1. 从BaseContext取当前用户ID，使用现有CatalogIds检查和转换路径ID。
2. 一次联合查询读取本账号权益对应的当前原文件。没有权益、不存在或无法映射的商品统一返回403 NOT_OWNED，不暴露内部文件信息。
3. 文件未READY或缺少必要元数据时返回409 FILE_NOT_READY；遗留LOCAL原文件返回503 STORAGE_NOT_SUPPORTED，不伪造OSS链接。
4. 确定统一UTC过期时间：当前时间加300秒，即5分钟。调用OssStorage，传入数据库记录的Bucket、对象键、可选存储版本与过期时间。
5. 组装DownloadGrantVO，返回商品ID、URL、过期时间、扩展名、十进制字符串大小和SHA-256。

主流程没有额外save分流、持久化服务或跨模块Controller调用。这里不上传文件、不改订单或权益，也不需要新事务或行锁。

## 6. 下载SQL与内部模型

```sql
SELECT w.original_storage_provider AS storageProvider,
       w.original_bucket AS bucket,
       w.original_object_key AS objectKey,
       w.original_object_version_id AS objectVersionId,
       w.original_file_extension AS fileExtension,
       w.original_size_bytes AS sizeBytes,
       w.original_sha256 AS sha256,
       w.original_status AS status
FROM entitlements e
JOIN wallpapers w ON w.id = e.wallpaper_id
WHERE e.user_id = #{userId} AND e.wallpaper_id = #{wallpaperId}
```

Entitlements的用户/商品唯一约束保证最多一条结果。一次SELECT同时读取权益及原文件，避免分别查询权益、文件时出现两个读取时点。没有publication_status或分类enabled过滤，停售仍保留已有下载权。

DownloadFileRow只是MyBatis查询中间模型，包含内部存储位置，不序列化返回前端。DownloadGrantVO是实际返回对象，沿用既有6字段协议。

```json
{
  "wallpaperId": "8",
  "url": "https://示例Bucket.oss-cn-guangzhou.aliyuncs.com/示例原文件?签名参数",
  "expiresAt": "2026-10-15T14:05:00Z",
  "fileExtension": "mp4",
  "sizeBytes": "35755786",
  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
}
```

URL为示例，实际响应仍包在ApiResponse.data中。扩展名、大小和SHA直接使用上传时已验证的当前文件元数据，不在每次下载申请时重新读取整份文件计算哈希。

## 7. OSS签名与文件传输

OssStorage.createDownloadUrl复用现有延迟创建的OSS客户端，用GeneratePresignedUrlRequest指定GET和过期时间；有objectVersionId时加入versionId查询参数。SDK在本地生成签名，不代表云端读取权限已验收；真正GET时OSS才校验权限。[阿里云SDK说明](https://www.alibabacloud.com/help/en/oss/developer-reference/download-using-a-presigned-url)

签名URL本身具备短时读取能力。前端直接GET它，不能把本系统账号Authorization发往OSS。URL应直接返回HTTP 200文件字节，不返回JSON、不重定向，也不对文件执行压缩/转码后沿用旧SHA。

这里的objectVersionId是OSS存储标识，绑定本次实际对象，并不恢复商品历史版本、assets表或resourceVersion。商品换文件后，新申请读取新对象和新SHA；旧对象被清理时，旧URL可能失效，重新申请即可。

【并发边界】本次查询只读已提交数据，不锁住权益或商品。签发后删除权益，不会提前撤销已经发出的5分钟URL；下一次申请会被拒绝。要立即撤销所有已发URL需要额外下载代理等机制，本项目当前不引入。

Controller对授权JSON设置Cache-Control: no-store；生产代码与测试均不打印完整签名URL或Secret。

## 8. RAM权限与当前云端验证

真实OSS返回AccessDenied时，不能当作本系统NOT_OWNED。前者是云端访问配置问题，后者是数据库里没有用户权益。

本次只读验证已明确得到：`403 AccessDenied; AuthAction=oss:GetObjectVersion; EC=0003-00000001`。原因是下载指定OSS版本还需要该动作。补充策略见同目录oss-download-policy.json，资源仅限定`zlowallpaper-oss/zlowallpaper/originals/*`，包含GetObject与GetObjectVersion。可创建独立自定义策略并授权给后端RAM用户，保留现有上传策略。

用户补充RAM权限后已经复验通过：OSS直接GET返回HTTP 200，完整流式读取35,755,786字节，SHA-256与数据库匹配。测试没有上传或删除云文件。代码不调用RAM管理API，也没有扩大Bucket公开访问范围。

用户本次选择授权AliyunOSSFullAccess，实际验证已可读取。若后续缩小权限，可保留原上传策略并另加oss-download-policy.json中的限定原文件目录读取策略；本次没有自动修改云权限。

## 9. 文件与函数清单

| 文件 | 作用及关键函数 |
| --- | --- |
| catalog/controller/WallpaperController | getMyWallpapers：接收已购列表请求；公开路由保持原路径 |
| catalog/service/WallpaperService与实现类 | getMyWallpapers：可信身份、查询、上限及转换；toVO重载复用字段转换 |
| catalog/mapper/WallpaperMapper与XML | selectOwnedWallpapers：只有已购Service调用；selectOwnedCover：只查当前账号已购封面 |
| catalog/controller/CoverController | readOwned：返回已购封面图片字节，禁止缓存 |
| catalog/service/CoverService | readOwned：身份与商品ID处理；复用readFile读取封面 |
| catalog/storage/OssStorage | createDownloadUrl：GET签名及可选存储版本，复用现有配置和客户端 |
| download/controller/DownloadController | createDownloadGrant：请求入口及统一响应、禁止缓存 |
| download/service/DownloadService与实现类 | createDownloadGrant：集中体现整个下载授权业务顺序 |
| download/mapper/DownloadMapper与XML | selectOwnedDownloadFile：本账号权益与当前原文件一次联合查询 |
| download/model/DownloadFileRow | SQL内部结果，包含文件位置，不直接返回前端 |
| download/vo/DownloadGrantVO | 当前文件的短时下载URL及校验信息 |

新类和显式方法均注明职责、实例化/注入位置、参数、返回及调用位置。没有批量重写已有业务类。

## 10. Apifox测试顺序

1. 启动/重启后端，导入本次openapi.yaml。登录并把有效accessToken写到本机环境变量，Auth引用该变量。
2. 确认本账号已经通过免费领取或壁纸币付款取得商品权益。仅有PENDING订单还不算拥有。
3. GET /me/wallpapers，Body选none；校验200和Product数组。改用另一个账号，不能看到原账号专属的已购商品。
4. GET返回的coverUrl，请求带本账号Bearer；即使商品停售也应返回图片。
5. POST /wallpapers/{id}/download-grants，Body选none，带Bearer；用自己未购商品ID应得到403 NOT_OWNED。
6. 有权益时得到200和DownloadGrant。补好RAM权限后，用返回url新建GET请求，Auth选No Auth，直接得到原文件。不要把完整URL贴到公共日志或截图。
7. 退款删除权益的行为本次由测试夹具模拟，不代表已提供退款接口；旧链接过期后也要重新申请。

两份OpenAPI与交接说明同步。本次没有修改工作台或桌面端界面；ZLC余额/付款界面、已购封面携带Bearer、桌面封面缓存仍按此前安排留待统一适配。

## 11. 验证用例

OwnedDownloadIntegrationTest：认证隔离、仅当前权益可见、停售与分类停用、权益时间/ID排序、当前元数据、公开查询不放宽、已购封面权限、未购403、5分钟与可信签名参数、删除权益、替换当前文件、异常分支、200/201项边界。OSS使用替身。

OssStorageTest：真实SDK离线V4签名、中文对象名、GET/过期时间/存储版本参数、Secret不出现在URL及缺少可信位置的错误。

OrderMySqlIT新增：真实MySQL已购/封面与下载联合查询。可通过显式download.oss.tests开关只读下载已有真实文件，流式核对全部字节大小和SHA，不上传或删除云文件；每次只清理独立数据库夹具。

本次完整后端回归248项：247通过、1项浏览器条件测试跳过；真实MySQL与OSS新增3项全部通过。已购列表上限调整为200后另外重跑对应22项测试，并重新打包；具体最终日志见.local-setup/owned-download-final-package.log。

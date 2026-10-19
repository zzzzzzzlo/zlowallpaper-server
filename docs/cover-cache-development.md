# Redis 封面缓存与可读性调整

完成日期：2026-10-18。接口路径、请求参数、图片响应和数据库结构保持现有约定。

## 执行顺序

```text
公开 / 管理员 / 我的已购封面 Controller
  → CoverService.read / readAdmin / readOwned
      → 各自的 WallpaperMapper 可见性 / 权益查询
      → readFile：检查 MIME、4 MiB 上限与存储方式
          → CoverCacheService.getCover(key)
              ├─ 命中且长度一致 → 直接返回图片
              └─ 未命中或缓存故障 → ObjectStorage.readCover
                  → CosStorage / OssStorage 读取并核对大小
                  → CoverCacheService.putCover(key,bytes)：SET 同时设置 TTL
                  → 返回图片
```

`CoverCacheService` 只负责两个缓存方法；调用顺序集中在 `CoverService`，没有再增加缓存接口、缓存实现、缓存持久化等业务层。`RedisConfig.coverRedisTemplate` 配置字符串键、原始 `byte[]` 值；不把 Resource、Java 对象序列化头或 Base64 文本写入缓存。Spring Data Redis 3.5.13 通过 Lettuce 6.6.0.RELEASE 连接独立 Redis 进程。

## 缓存键与权限

键：`cover:v1:{provider}:{bucket}:{objectKey}`，例如同一个云封面可以在三个已获准入口复用字节。默认从写入起 21600 秒（6 小时）过期，读取不会续期。替换封面上传新 UUID 对象键，因此使用新缓存；旧键等待 TTL 或内存淘汰，不会展示成新封面。

每次请求仍先查询数据库，不缓存“有权查看”结论。下架后的公开请求和未购账号，即使 Redis 已有字节，也无法取到图片；已购账号仍可读自己的停售商品。不能把“缓存图片”理解为“跳过权限”。`Cache-Control: no-store` 继续控制客户端与 HTTP 中间缓存，不妨碍后端在鉴权后共享 Redis 字节。

Redis 读失败按未命中处理，写失败仍返回已经读出的云图片；异常只写 debug 日志。local 连接与命令超时均为 500ms，故障回源可能增加两次缓存操作的等待时间。关闭 `COVER_CACHE_ENABLED` 后完全不访问 Redis。健康接口仍只检查 MySQL。

第一版不实现分布式锁、并发请求合并、预热或后台刷新：同一图片首次被多个请求同时读取时，可能各自访问 COS。缓存只存封面，不缓存原视频、不替代下载文件复用，也不减少后端到客户端的图片传输量。

## 本机启动和配置

本机已准备 Redis 8.10.2 的 Windows 社区便携版，来自 [redis-windows 发布页](https://github.com/redis-windows/redis-windows/releases/tag/8.10.2)，不是 Redis 官方 Windows 发行版。解压位置：`D:\Codex\software\redis-8.10.2\Redis-8.10.2-Windows-x64-msys2`，不安装系统服务。下载 ZIP 的 SHA-256 已核对为发布页所列的 `7C8CEBD50347EAA1D9E784DA842ED47A4F33394637835531D6614777B950EE85`。

在项目根目录运行：

```powershell
.\scripts\start-redis.ps1
```

脚本使用 [redis-local.conf](../scripts/redis-local.conf)：只监听 127.0.0.1:6379，最大 256 MiB，`allkeys-lru` 在内存不足时淘汰较少使用的键。缓存可以回源，因此关闭 RDB/AOF 落盘；进程停止后再次启动会重新积累缓存。脚本不会启动后端或占用 8080。

默认本机配置：

| 配置 | 默认值 | 用途 |
| --- | --- | --- |
| REDIS_HOST | 127.0.0.1 | Redis 主机 |
| REDIS_PORT | 6379 | Redis 端口 |
| REDIS_PASSWORD | 空 | 本机仅回环地址；远程实例按实际认证配置 |
| COVER_CACHE_ENABLED | true | 封面缓存开关 |
| COVER_CACHE_TTL_SECONDS | 21600 | 写入后过期秒数，应配置为正整数 |

`.env.example` 已补齐这些配置。更换安装目录可用 `start-redis.ps1 -RedisDirectory '实际目录'`。远程部署时用部署环境提供的 Redis，256 MiB 和淘汰策略由对应 Redis 实例配置；不要把本机便携程序视为线上部署方案。

## 其余代码调整

`WallpaperServiceImpl.page` 与 `getMyWallpapers` 均为：整理筛选参数 → 创建 Page 并调用 Mapper → for 循环生成当前场景封面 URL → 单层 `toVO(row,coverUrl)` → PageVO。`detail` 也直接调用同一个 toVO。公开与已购各自的 SQL 条件、分页结果和封面路径不变。

`CoverService` 的本地旧文件去掉逐段符号链接/真实路径检查，保留规范化路径、根目录范围、普通文件存在、大小匹配检查；MIME 白名单与 4 MiB 上限继续生效。本地存储目录由后端管理，不接受客户端提供任意服务器路径。云文件依然来自当前商品的 READY 元数据。

## 验收

- Maven：278 项测试，0 失败、0 错误、1 项跳过。新增 10 项覆盖缓存读写、TTL、序列化、关闭/故障回源、三个入口共享、权限查询、文件替换键、异常缓存长度和本地越界路径。
- 真实 Redis + MySQL + HTTP + COS：读取 771093 字节的现有封面，连续四次成功请求（公开两次、管理员一次、已购一次）只发生一次 COS 读取，Redis 中原始字节一致，TTL=21600 秒。
- 已暖缓存后：商品下架公开 404、其他账号已购封面 404、匿名已购 401、本账号已购 200。缓存没有放宽权限。
- 仅将测试缓存组件指向未监听的本机端口，真实验证 Redis 不可用时仍返回 200，字节与 COS 一致；总计两次 COS 读取、1542186 字节，没有云上传/删除。
- 临时商品、账号、会话、订单和权益已清理；既有用户余额与云对象未更改。测试用 8081 已关闭，本机 Redis 保留供开发使用。

验收原始输出在忽略提交的 `.local-setup/cover-cache-tests.log`、`redis-cover-http.log`、`redis-cover-http-result.json`。完整接口调用链继续维护 [原文档](api-call-chains.md)，没有另建一份过期的汇总。

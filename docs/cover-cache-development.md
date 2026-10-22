# 封面缓存与并发回源

## 执行顺序

```text
公开/管理员/已购 Controller
  → CoverService.read / readAdmin / readOwned
      → 各自的商品可见性或权益SQL
      → readFile
          → 检查媒体类型、大小与存储位置
          → CoverCacheService.getCover
              → 命中且大小一致：返回字节
              → 未命中/关闭/Redis故障：按封面键登记共享任务
                  → 已有加载者：等待同一结果
                  → 当前请求成为加载者：并发复查Redis
                      → 仍未命中：ObjectStorage.readCover
                      → 写入Redis并完成共享结果
                      → finally移除本次任务
```

CoverCacheService只读写Redis。RedisConfig配置字符串键和原始byte[]值，不保存Base64、Resource或Java序列化对象。调用顺序集中在CoverService.readFile，没有额外缓存业务分流层。

## 缓存键与权限

键为 cover:v1:{provider}:{bucket}:{objectKey}。默认写入起21600秒过期，读取不续期。替换封面生成新UUID对象键，使用新缓存；旧键等待TTL或淘汰，不需要清空全部缓存。

公开请求只读上架商品，已购请求按当前账号权益查询，管理员请求检查角色。每次都先执行数据库条件，不缓存权限结论；Redis已有字节不代表用户可以读取。

图片仍按各Controller的HTTP缓存响应头返回。后端Redis缓存和浏览器缓存是两件事，不能把页面复用图片算成Redis命中。

## 合并回源

ConcurrentHashMap<String, CompletableFuture<byte[]>>仅保存**正在进行**的同键加载任务。putIfAbsent选出加载者，它在当前请求线程读取云文件；其他请求共享结果，不再各自读取同一对象。

登记成功后再次读Redis，注释标明“并发复查”：第一次查询后，前一批请求可能已填好缓存并移除任务，这次读取避免重复回源。

等待者最多等待wallpaper.cover-cache.wait-seconds，默认10秒；超时或中断返回503，不取消加载者、不删除其记录，也不自行发起新回源。加载失败把原异常传给等待者，finally移除任务，后续请求可以重试。不同键可并行。

## 故障与限制

Redis读取失败按未命中处理，写入失败仍返回已获得的云图片。COVER_CACHE_ENABLED=false会跳过Redis读写，但保留同JVM重叠请求的回源合并。连接和命令超时各500ms；Redis故障时不等同于主动关闭缓存，可能增加超时等待。

此方案只合并同一JVM的同键请求，不是分布式锁，不限制所有不同封面的回源总量，也不能证明可以承受任意并发。Redis小容量时仍会淘汰并产生COS读取。现阶段没有CDN、跨实例合并、预热或浏览器/Qt封面磁盘缓存。

Redis内存容量和淘汰策略由运行实例配置，见 [开发环境](development-environment.md)。健康接口只检查MySQL。原文件/视频不缓存到Redis。

## 验证与测量

CoverCacheServiceTest、CoverServiceTest、CoverServiceConcurrencyTest覆盖字节读写、关闭和故障、权限、替换键、同键共享、不同键并行、超时和失败清理。

页面测试需要区分浏览器缓存、Redis命中、并发回源合并、云端网络和Redis故障超时。比较“启用Redis”和“主动关闭缓存”时，应保留相同数据、浏览路径及合并逻辑；关闭Redis进程会引入额外故障等待，不是同一个对照条件。

历史临时压测脚本和实验产物不随仓库发布，不把旧实验数据当作当前封面尺寸、网络或真实上线吞吐量的结论。

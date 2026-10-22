# 上传封面压缩

## 调用顺序

```text
AdminWallpaperServiceImpl.createWallpaper / updateWallpaper
  → FileUploadServiceImpl.uploadFile
      → 获取Semaphore文件处理槽位
      → copyAndHash：流式落盘并检查大小
      → MediaInspector.inspect：真实格式/媒体信息/首帧检查
      → 仅COVER：MediaInspector.compressCover(source,target)
          → 复用execute启动FFmpeg
          → 复用inspect检查生成结果
      → 按最终文件重算大小、SHA-256和媒体信息
      → ObjectStorage.upload
      → finally清理两份临时文件、释放槽位
  → 原有跟踪、并发复查和短数据库事务
```

媒体处理类只增加compressCover一个方法。修改时只有重新选择封面才处理；壁纸原文件不进入压缩分支。

## 当前规则

| 项目 | 规则 |
| --- | --- |
| 上传源封面 | 最大10 MiB，同时限制声明大小和实际字节 |
| 输出尺寸 | 长边最多1920像素，保持比例、不裁剪、不放大小图 |
| 输出格式 | JPEG，FFmpeg q:v=2；不是2%画质 |
| 透明图片 | 白底合成 |
| 动态封面 | 可正常解码时取首帧 |
| 存储/读取 | 仍最大4 MiB，保留数据库和读取上限 |

application.yml和UploadProperties的默认值一致。cover-max-edge允许128～2048，cover-jpeg-quality允许2～31，数值越小画质越高。输出体积随画面内容变化，没有固定压缩率或目标文件大小承诺。

cover_filename保留输入展示名；实际扩展名、MIME、UUID对象键后缀对应生成的JPEG。cover_size_bytes、sha256、宽高和fps也来自最终文件。商品VO的原壁纸元数据仍来自original字段。

## 并发、失败和缓存

压缩在现有Semaphore(2)槽位内、数据库事务外完成。工具缺失、超时或输出无效时返回媒体错误，不静默上传未处理图片。源/输出临时文件在finally清理，后续云上传/商品保存失败继续使用既有补偿机制。

替换封面使用新对象键，缓存读取自然使用新键，旧键等待TTL/淘汰。改变默认参数不会自动重算旧文件，需从源图片重新上传；不能把旧JPEG连续压缩当作从源图生成。

## 验证范围

FileUploadServiceTest验证10 MiB边界、实际输出元数据、原文件不压缩和失败清理。MediaToolsIT使用真实FFmpeg验证多种图片、透明像素、奇数尺寸、首帧、小图和大图。

本地一次30条商品封面验收：源封面按各商品计共67028772字节，生成9150821字节，体积减少86.35%；该数据只代表这组素材，不是页面延迟指标，也不是所有图片都能达到的比例。文件元数据、公开/私有可见性及原壁纸未变化均做过核对。素材和一次性重传脚本不随仓库发布。

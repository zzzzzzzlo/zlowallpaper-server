# 客户端下载与本地资料库存储修复

2026-10-16。

## 原因

下载的MP4已经通过大小与SHA-256校验，但写入本地library.json失败。Windows打包应用环境将C盘AppData下已有的library.json重定向到D盘Codex缓存，而其父目录仍解析为C盘。临时文件和最终文件不在同一实际磁盘，原子替换报“系统无法将文件移到不同的磁盘驱动器”。这与购买权益、OSS权限无关。

## 当前目录

以本机部署为例：

```text
E:\zlowallpaper\
  ZloWallpaper.exe
  loaded_wallpaper\
    library.json
    thumbnails\
      <本地壁纸ID>.png
    <账号范围SHA-256>\
      <商品ID与文件SHA-256组合的散列>.<扩展名>
```

下载临时文件在对应账号目录内，资料库临时文件在library.json旁边，成功时同盘替换；失败或取消时清理临时文件、保留原文件。账号仍按API地址和用户ID隔离。

本地手动导入的壁纸继续引用用户选定的原路径，不擅自搬动原始素材；其资料库记录和新生成的缩略图也保存到loaded_wallpaper。

账号凭证、普通设置、播放器代理缓存等不属于本次壁纸下载资料，沿用原位置。AtomicFile同时修复为优先解析已有文件的真实路径，防止已有凭证等文件遇到相同的文件级重定向。

## 改动位置

- WallpaperLibrary::defaultStorageDirectory：唯一的默认目录定义，程序目录/loaded_wallpaper。
- WallpaperLibrary::filePath/save：资料库记录及保存临时文件使用新目录。
- DownloadManager::defaultDownloadDirectory：复用资料库默认目录，不再使用wallpapers。
- WallpaperLibrary::thumbnailDirectory与ThumbnailGenerator::create：缩略图随资料库保存，后台生成时显式传入目录。
- WallpaperLibrary::load：发现缺失或位于旧目录的缩略图后重新生成，保留本地条目ID。
- AtomicFile：已有目标先解析文件自身的物理路径，尚无目标才解析父目录，保留QSaveFile的原子保存机制。

## 本机已有数据

本次部署已备份旧library.json，校验并搬迁运行目录下的已有wallpapers文件，同时更新记录路径。对已经下载但登记失败的孤立文件，仅在内容SHA-256、大小、账号目录、商品文件名与数据库权益全部匹配时恢复资料库记录；不会新增订单、扣币或修改数据库。其他无法匹配的文件仅搬迁保留，不伪造商品归属。

这是本次本机迁移操作，程序日常运行只读写新目录。程序安装目录需要可写，不能写时返回实际错误，不偷偷切回AppData。以后变更整个运行目录时应保留loaded_wallpaper；现有记录使用绝对路径，需要同步定位文件。

## 验证

回归覆盖下载、SHA校验、取消、账号隔离、资料库失败回滚及重载；新增同目录原子替换/取消保留旧文件、缩略图位置测试。真实部署后核对迁移文件哈希、恢复记录及重启保存行为。


实际验收：Qt回归18通过、0失败，1个需独立购买夹具的用例未开启；额外在E盘目录执行同目录替换和重载测试。已迁移并恢复1份Fate·举剑视频（35,755,786字节），内容SHA-256一致。真实E盘客户端启动后生成loaded_wallpaper/thumbnails缩略图，并成功以同目录临时文件保存library.json；退出后再次启动仍能读取该条记录。新客户端已更新并重新打开。

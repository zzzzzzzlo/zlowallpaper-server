# 测试说明

## 默认测试

根目录执行：

```powershell
.\mvnw.cmd test
```

JUnit、Spring Boot Test、Mockito 和 H2 覆盖认证与会话、商品和分类、订单付款、权益隔离、下载授权、缓存读写、同键回源合并及媒体签名。测试使用test profile、独立内存数据库和存储替身，不要求启动本机MySQL/Redis，不读写真实商城文件。

src/test 是项目正式自动化测试，随业务维护。一次性压测、截图、迁移和素材重传脚本不属于这些测试，已从仓库移除。

## 真实媒体工具测试

安装FFmpeg/FFprobe后显式运行：

```powershell
.\mvnw.cmd '-Dtest=MediaToolsIT' '-Dmedia.tools.tests=true' test
```

真实生成图片/视频素材，核对格式、解码、尺寸、封面透明底、首帧处理、不放大小图和最终文件元数据；云上传仍用替身接收真实处理后的字节，不消耗COS流量。

## MySQL专项

*MySqlIT.java 通过 wallpaper.mysql.tests 系统属性显式开启，使用专门准备的数据库。默认Maven测试不会自动运行这些专项。

运行前阅读各测试类的连接、清理及数据隔离设置。它们不能视为对任意业务库都安全的通用命令，不应直接把开发商城数据库当作隔离测试库。真实云下载相关测试还有单独开关。

## 接口与前端联调

导入 [OpenAPI](api/openapi.yaml)，设置/api/v1根地址。依次确认注册登录、商品展示、下单与付款、已购、下载授权；管理接口使用管理员账号。

演示模式或mock通过不代表真实接口通过。核对HTTP状态、业务码、requestId和数据库结果；订单PENDING不算已购。商品停售后的公开/已购差异也需要检查。

## 报告

Maven报告位于target/surefire-reports。Windows本机若默认临时目录因权限或编码导致JUnit清理失败，可创建项目内临时目录并给测试JVM传入java.io.tmpdir；不要为解决目录问题而跳过断言。

文档不固定宣称测试数量或吞吐量。每次变更以本次实际报告为准；单元测试通过不代表真实设备、网络和云权限已经验收。

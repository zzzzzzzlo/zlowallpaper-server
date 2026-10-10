# 壁纸商城：每个模块完成后的联调流程

准备日期：2026-10-09。本文件是联调方案，尚未代表真实 Spring Boot 后端或测试环境已启动。

## 已有客户端可以直接使用

实际前端是 Qt/C++ Windows 桌面客户端，源码位于 D:\Codex\software\zlowallpaper\frontend。
可运行程序：E:\zlowallpaper\ZloWallpaper.exe。
它已提供真实 API 模式，默认 API 根地址 http://127.0.0.1:8080/api/v1。
不需要额外构建 Vue/React 测试前端。

已有接口约定：

- D:\Codex\software\zlowallpaper\frontend\docs\openapi.yaml
- D:\Codex\software\zlowallpaper\frontend\docs\BACKEND-HANDOFF.md

这些文件用于了解客户端实际请求和响应格式；本后端源码位置按用户指定的 E:\zlowallpaper_sever 保持。
演示模式使用客户端内存服务器，不访问新安装的 MySQL，因此演示通过不能作为 Spring Boot 联调通过。
真实 Qt 客户端请求不受浏览器 CORS 限制；将来新增浏览器管理页面时再按实际来源配置代理或 CORS。

## 当前环境还缺什么

MySQL 8.4 服务和商城数据库已就绪；当前目录没有 pom.xml、Spring Boot 主程序或模块代码。
因此后端尚不能启动。系统也尚未发现可用 Docker 命令，Redis 启动还需完成安装和验证。
JDK 21 的项目配置和 Maven Wrapper 尚需随工程初始化核实。
本次没有安装 Apifox，也没有生成业务工程或启动壁纸客户端。

## 三种测试互相补充

| 测试方式 | 验证内容 | 执行时机 |
| --- | --- | --- |
| Apifox 接口调试 | 请求参数、HTTP 状态码、JSON 字段、登录令牌与错误响应 | 一个接口实现后 |
| 已有 Qt 客户端 | 页面交互、字段显示、账号会话、订单、下载和入库流程 | 一个模块可用后 |
| Spring Boot 自动测试 | 权限、数据库持久化、事务、重复请求与已有功能回归 | 关键逻辑变动后 |

Apifox 可导入已有 openapi.yaml，将 base URL 设置为 http://127.0.0.1:8080/api/v1，
以实际请求最终 URL 为准，避免 /api/v1 被重复拼接。
每个模块保存正常、异常场景请求及断言，后续复用。
可选接入 springdoc-openapi 的 Swagger UI，在浏览器查看并试调用接口。
springdoc 的依赖需匹配实际 Spring Boot 版本，不直接复制最新主页中的依赖版本。

## 推荐开发与联调顺序

| 阶段 | 后端模块 | 客户端验收 |
| --- | --- | --- |
| 1 | 分类、商品列表、搜索、分页、详情 | 显示种子商品、封面、价格，分类和搜索结果正确 |
| 2 | 注册、登录、刷新、登出 | 新用户能注册登录，错误密码被拒绝，登出与会话失效正确 |
| 3 | 创建订单、免费领取、开发测试付款、已购 | PENDING 不算已购，付款后 PAID，重复请求不重复下单或授权 |
| 4 | 下载授权、文件服务 | 未拥有时拒绝下载；拥有后下载、校验、入库；取消/损坏文件处理正确 |
| 5 | 管理上传、编辑、上下架 | 管理员能操作，普通用户被拒绝，上下架结果反映到客户端 |

每完成一个模块，只验收对应已实现功能；未完成接口不应该返回虚假的成功。
管理员功能目前以接口工具验证，桌面客户端不等于已经提供完整后台管理界面。
首期仅开发环境模拟付款，不接真实收款渠道。

## 每次联调的固定步骤

1. 启动 MySQL；当模块依赖 Redis 时同时启动 Redis。
2. 在 IDEA 启动 Spring Boot，使用预先配置的 local 开发配置，监听 8080。
3. 用 Apifox 运行该模块请求和必要的异常场景，确认真实接口可用。
4. 启动桌面客户端：

   ```powershell
   Set-Location E:\zlowallpaper_sever
   .\scripts\start-integration-client.ps1
   ```

   此脚本只在 8080 可连接时启动客户端，不传 --demo。TCP 可达不等于业务接口正确。
   如果客户端原本已经运行并处于演示模式，需要在“连接设置”切换为真实 API 模式，确认上述 API 根地址。

5. 按模块验收表操作，遇到错误记录接口路径、实际状态码和 requestId，对照后端日志排查。
6. 用 DBeaver 核对用户、订单和权益等数据确实写入数据库。
7. 运行相关的自动测试及受影响的已有用例，再把该模块标记为完成。

客户端登录使用注册接口创建的商城用户，不能填 MySQL 的 wallpaper_app 或 root 账号。

## 自动测试与测试数据隔离

工程初始化时加入 spring-boot-starter-test，针对重要行为编写可重复执行的测试。
后续使用 test profile 与独立的 zlowallpaper_test 数据库及测试账号，避免清理日常联调数据。
目前测试库和测试账号尚未创建，profile 也尚未配置；测试脚本不得使用 root 作为业务连接。
Redis 联调与测试使用不同的键前缀，删除测试数据时只清理测试前缀，不执行全实例清空。

测试最少覆盖：错误参数、未登录、普通用户访问管理接口、账号之间的数据越权、
重复创建订单、付款状态与权益写入的一致性、无权益下载、文件授权过期。
未来工程带有 Maven Wrapper 后使用 `.\mvnw.cmd test` 执行；指定测试 profile 时还需确保相应配置和数据库已就绪。

一台电脑就可以完成这套联调，不必先租云服务器。后期需要持续验收部署时，再搭建独立的 staging 环境。

## 参考

- Apifox：https://docs.apifox.com/
- Spring Boot 测试：https://docs.spring.io/spring-boot/3.5/reference/testing/index.html
- Spring Boot Profiles：https://docs.spring.io/spring-boot/3.5/reference/features/profiles.html
- springdoc-openapi：https://springdoc.org/

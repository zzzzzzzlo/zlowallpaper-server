# ZloWallpaper Spring Boot 后端

当前阶段：可启动的工程骨架，七个业务模块仍待逐个开发。

## 工具链

| 项目 | 配置 |
| --- | --- |
| Java | 21；本机 Temurin 21.0.12.1，D:\Codex\software\jdk-21.0.12.1+1 |
| Spring Boot | 3.5.16 |
| Maven | Wrapper 锁定3.9.16，并配置分发包SHA-256校验 |
| MyBatis-Plus | 3.5.17，Spring Boot 3 starter |
| 本机依赖缓存 | D:\Codex\software\maven-repository |
| MySQL | 127.0.0.1:3306，zlowallpaper，wallpaper_app |
| 后端地址 | http://127.0.0.1:8080/api/v1 |

现有 JDK 25 保留，没有修改系统全局 JAVA_HOME。Java21与Maven下载包已经按官方校验和验证。

## 启动

项目根目录已有 .env，应用在 local 配置中显式读取其中 MYSQL_PASSWORD。工作目录必须为项目根目录；其他环境可以通过进程环境变量传入密码。没有将真实密码写入源码或提交配置。

在 PowerShell 中执行：

```powershell
Set-Location E:\zlowallpaper_sever
.\scripts\start-backend.ps1
```

脚本在当前终端运行服务，Ctrl+C停止。它仅为启动过程选用本机JDK21、Maven缓存目录和local环境，不修改系统变量。自定义端口使用 `-Port 8081`，自定义JDK目录使用 `-JavaHome <目录>`。

浏览器或Apifox请求 `GET http://127.0.0.1:8080/api/v1/health`。MySQL就绪时：

```json
{"code":"OK","message":"","data":{},"requestId":"由服务器生成"}
```

健康接口通过应用数据源执行SELECT 1；数据库不可用返回HTTP503、SERVICE_UNAVAILABLE、data:null，不返回连接详情。当前Redis尚未纳入健康检查；后续业务依赖Redis时补充其就绪策略。

## IDEA 打开方式

1. 打开根目录pom.xml，选择作为Maven项目加载；已经打开此文件夹时，可右键pom.xml选择“添加为Maven项目”。
2. Project Structure → SDKs 添加 `D:\Codex\software\jdk-21.0.12.1+1`；Project SDK选择该JDK21。Maven Importing / Runner也使用JDK21。
3. Maven home选择项目Wrapper，Local repository选择 `D:\Codex\software\maven-repository`。
4. 运行 `src/main/java/com/zlo/wallpaper/ZloWallpaperApplication.java` 的main方法；工作目录保持 `E:\zlowallpaper_sever`。未指定环境时默认local。

现有IDEA文件夹配置此前指向JDK25；没有手动伪造IDEA全局SDK注册信息。选择新JDK后由IDEA保存正确设置。

## 新增文件的用途

| 文件/目录 | 用途 |
| --- | --- |
| pom.xml | 项目信息、依赖、Java版本、打包插件 |
| mvnw、mvnw.cmd、.mvn/wrapper | 官方Maven Wrapper；统一构建版本，无须系统PATH里有mvn |
| src/main/java | Java源码；com.zlo.wallpaper为根包 |
| ZloWallpaperApplication.java | 应用入口与组件扫描起点 |
| common/ApiResponse.java | 与现有接口一致的响应外壳 |
| common/RequestIdFilter.java | 为请求生成requestId，写入请求属性与X-Request-Id响应头 |
| common/HealthController.java | 基础MySQL就绪检查，无商城业务 |
| config/WebConfig.java | 允许指定管理端来源的CORS，支持预检，不携带cookie凭证 |
| 七个模块的package-info.java | 说明模块职责，保留目录；没有控制器、实体或业务实现 |
| src/main/resources/application.yml | 端口、API前缀、通用配置、MyBatis映射等 |
| src/main/resources/application-local.yml | 本机数据库、Redis连接参数、显式.env导入和预留开发配置 |
| src/test | 自动测试；使用test环境，不读取商城密码或改商城表 |
| target | 构建输出与测试报告，已被Git忽略 |

local内的文件存储目录和测试支付开关只是未来业务模块读取的预留参数；当前没有上传、支付或下载接口。服务仅绑定127.0.0.1，本阶段用于本机开发。

数据库初始化设置为never；不会在启动时重复执行database中的建表SQL，也不会重建现有七张表。暂时没有Mapper，启动时的“No MyBatis mapper was found”提示表示尚未开发持久层。

## 构建与测试

```powershell
Set-Location E:\zlowallpaper_sever
$env:JAVA_HOME = 'D:\Codex\software\jdk-21.0.12.1+1'
$env:MAVEN_USER_HOME = 'D:\Codex\software\maven-user-home'
.\mvnw.cmd -B -ntp -Dmaven.repo.local=D:/Codex/software/maven-repository verify
```

生成 `target/zlowallpaper-server-0.0.1-SNAPSHOT.jar`。测试覆盖健康响应及requestId、数据库失败的503响应与CORS、允许/拒绝来源的预检。真实MySQL连接另通过实际启动应用验证，模拟数据源测试不能替代这项验证。

## 后续开发

七个业务包为auth、catalog、asset、order、payment、entitlement、download。下一阶段先做catalog公开浏览接口与种子数据；模块内controller/service/mapper/entity/dto随实现添加。用户仍亲自完成各模块开发。

Redis已加入连接依赖和本机参数，但尚未验证服务可用性。会话、请求幂等、文件存储及异步资产查询方案仍待设计；没有新增待定表，也没有提前实现这些业务。

设计文档位于docs；数据库SQL位于database；前端接口依据为E:\zlowallpaper\docs\openapi.yaml。

官方参考：[Spring Boot 3.5要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[MyBatis-Plus安装](https://baomidou.com/getting-started/install/)、[Spring配置导入](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)、[Maven Wrapper](https://maven.apache.org/tools/wrapper/)。

# 壁纸商城后端：Windows 开发环境

更新日期：2026-10-10。项目位于 `E:\zlowallpaper_sever`。

Spring Boot 工程骨架已完成，当前启动方式、文件说明与测试命令见根目录 README.md。

## 已完成：重装 MySQL 与图形客户端

按用户要求，旧 MySQL 8.0.18、Workbench、Shell、旧 Connector 和 Installer 已卸载，
MySQL80 服务与 C 盘的旧 MySQL 安装、数据目录已删除。用户已确认旧库没有需要保留的数据。

新安装采用官方 Windows ZIP 分发包：MySQL 8.4.11 LTS + DBeaver Community 26.2.2。
MySQL 官方 MD5 校验匹配；MySQL 与 DBeaver 可执行程序的数字签名均有效。

| 项目 | 配置 |
| --- | --- |
| MySQL 软件 | D:\Codex\software\mysql-8.4.11-winx64 |
| MySQL 配置 | D:\Codex\software\mysql-8.4.11-winx64\my.ini |
| 数据目录 | D:\Codex\software\mysql-data |
| Windows 服务 | ZloMySQL84，自动启动 |
| 数据库地址 | 127.0.0.1:3306，仅监听本机 |
| 商城数据库 | zlowallpaper，utf8mb4 |
| 商城账号 | wallpaper_app，仅授权商城数据库 |
| DBeaver | D:\Codex\software\dbeaver-ce-26.2.2\dbeaver\dbeaver.exe |
| DBeaver 工作区 | D:\Codex\software\dbeaver-workspace |
| 新密码文件 | E:\zlowallpaper_sever\.env |

.env 内 MYSQL_ROOT_PASSWORD 是管理员 root 的密码，MYSQL_PASSWORD 是 wallpaper_app 的密码。
密码自动生成，文件已限制为当前用户、SYSTEM 和管理员可访问，并列入 .gitignore。
不要覆盖此文件，不要把它提交到 Git。修改文件内容不会自动改变数据库账号密码。

已验证 MySQL 新程序版本、服务运行、商城账号登录、数据库访问，并实际执行了建表、插入、查询和删表。
测试表已清理。安装过程记录保存在项目 .local-setup 目录中，该目录也已忽略。

## 使用图形界面

桌面和开始菜单均已创建“DBeaver - 壁纸商城”快捷方式。
快捷方式会优先切回已经运行的 DBeaver，避免重复启动造成工作区占用提示。
程序默认工作区也已统一到 D:\Codex\software\dbeaver-workspace。
若旧的 Can't lock workspace 对话框仍在，点击“中止”，然后使用上述快捷方式。
启动时已预置名为 Zlo Wallpaper Local 的商城连接，不在快捷方式中写入密码。

首次连接操作：

1. 如果出现欢迎页，关闭欢迎页；在左侧找到 Zlo Wallpaper Local。
2. 编辑连接或双击连接，确认 Host 127.0.0.1、Port 3306、Database zlowallpaper、User wallpaper_app。
3. 从本地 .env 复制 MYSQL_PASSWORD 等号后的值到密码框，并可勾选保存密码。
4. 如果提示下载 MySQL JDBC 驱动，允许下载。
5. 点击测试连接，或打开 SQL 编辑器执行 `SELECT VERSION(), DATABASE();`。

本地连接已设置 allowPublicKeyRetrieval=true 和 useSSL=false，仅适用于此本机开发环境。
如果客户端没有保留预置连接，按上面的参数手动新建 MySQL 连接即可。
DBeaver 程序已启动；服务和 SQL 已通过命令行验证，图形界面的首次登录仍需填写密码。

需要管理员操作时使用 root 登录；Spring 应用和日常商城数据操作使用 wallpaper_app。

命令行也可以使用：

```powershell
& 'D:\Codex\software\mysql-8.4.11-winx64\bin\mysql.exe' -h 127.0.0.1 -P 3306 -u wallpaper_app -p zlowallpaper
```

密码输入时不显示字符，属正常行为。
管理员 PowerShell 可以使用 `Stop-Service ZloMySQL84` 和 `Start-Service ZloMySQL84` 停止、启动服务。

## 已完成：JDK 和 Maven 工具链

现有 Oracle JDK 25.0.4 保留在 D:\Java，没有修改系统全局 JAVA_HOME。
项目新增 Temurin JDK 21.0.12.1：D:\Codex\software\jdk-21.0.12.1+1。
项目编译目标Java21；启动脚本使用新JDK21。官方JDK下载包SHA-256已验证。

在 IDEA 的 Project Structure → SDKs 添加上述JDK目录，再选择项目SDK21。
项目SDK、语言级别以及 Maven Importing / Runner 使用同一JDK21。
IDEA 自己使用的 JetBrains Runtime 不等于项目的 JDK。

IDEA Settings 搜索 Maven：

- Maven home path 使用项目 Maven Wrapper，固定Maven3.9.16。
- Local repository 建议 D:\Codex\software\maven-repository。
- .mvn/wrapper/maven-wrapper.properties 配置了分发包SHA-256校验。

本机Maven安装在D:\Codex\software\apache-maven-3.9.16；依赖缓存在D:\Codex\software\maven-repository。
Wrapper缓存位于D:\Codex\software\maven-user-home，启动脚本会使用这些路径。
已验证Wrapper版本。终端直接使用Wrapper时设置JAVA_HOME指向项目JDK21。

## 待完成：Redis

MySQL 现在是原生 Windows 服务，项目 compose.yaml 仅启动 Redis，不再创建第二个 MySQL。
目前 Docker / WSL 尚未安装，Redis 尚未启动验证。

管理员 PowerShell 执行 `wsl --install --no-distribution`，按提示重启，再执行 `wsl --update`。
按照官方文档安装 Docker Desktop，选择 WSL 2 后端与 Linux containers，等待引擎启动。
不要求额外安装 Ubuntu。硬件虚拟化需要启用。

然后运行：

```powershell
Set-Location E:\zlowallpaper_sever
docker version
docker compose config --quiet
docker compose up -d redis
docker compose exec redis redis-cli ping
```

docker version 应同时显示 Client 与 Server。Redis 应返回 PONG。
Redis 连接地址 127.0.0.1:6379，本机开发不设密码，端口只绑定本机，使用 AOF 与命名数据卷。
可另装 Redis Insight 图形客户端。日常停服务用 `docker compose stop redis`，恢复用 `docker compose start redis`。

## 已完成：Spring Boot 工程骨架

不用另外安装 Spring、Spring Boot 或 Tomcat。创建 Maven 工程后，Maven 根据 pom.xml 下载框架，
Spring Web 通过内嵌 Tomcat 提供 HTTP 服务。

当前工程参数：

| 参数 | 值 |
| --- | --- |
| Location | E:\zlowallpaper_sever |
| Project / Language | Maven / Java |
| Spring Boot | 3.5.16 |
| Group / Artifact | com.zlo / zlowallpaper-server |
| Package | com.zlo.wallpaper |
| Java / Packaging | 21 / Jar |

pom.xml 已加入Spring Web、Validation、Spring Data Redis、MySQL Driver、MyBatis-Plus3.5.17 Boot3 starter和测试依赖。
七个业务模块当前只包含职责说明，没有提前生成实体或业务接口。已经建立启动类、健康接口、统一响应外壳、requestId及管理端CORS配置。

本机Spring应用连接127.0.0.1:3306/zlowallpaper，账号wallpaper_app，连接时区设置UTC。
application-local.yml 通过spring.config.import显式读取根目录.env，密码使用MYSQL_PASSWORD；工作目录必须是项目根目录。也可通过进程环境变量提供密码。
Spring Boot默认不自动读取.env，本工程是通过上述导入配置完成读取。
启动时不自动执行建表SQL，不改变现有七张表。Redis目前仅预留依赖和连接参数，服务尚未验证。

在项目根目录运行 `.\scripts\start-backend.ps1`，然后请求 http://127.0.0.1:8080/api/v1/health。
已完成Java21编译打包、三个基础测试及真实MySQL健康接口/管理端跨域预检验证。商城业务接口后续逐模块开发。

## 官方参考

- MySQL 8.4 Windows 下载：https://dev.mysql.com/downloads/mysql/8.4.html
- MySQL Windows 服务：https://dev.mysql.com/doc/refman/8.4/en/windows-start-service.html
- DBeaver 下载：https://dbeaver.io/download/
- Spring Boot 系统要求：https://docs.spring.io/spring-boot/3.5/system-requirements.html
- IDEA Maven：https://www.jetbrains.com/help/idea/maven-support.html
- Docker Windows：https://docs.docker.com/desktop/setup/install/windows-install/
- Redis Docker：https://redis.io/docs/latest/operate/oss_and_stack/install/install-stack/docker/

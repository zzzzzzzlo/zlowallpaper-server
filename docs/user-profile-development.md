# 用户资料字段精简与前端预留入口

2026-10-16。本次移除显示名称，预留个人资料编辑和充值的前端接入位置。

## 已完成的改动

- 数据库 users 移除 display_name。旧库执行 database/008-remove-user-display-name.sql；新版001不再创建这一列，008可重复执行。
- User实体、UserMapper查询/插入、注册、登录、刷新令牌、GET /me同步移除该字段。
- UserVO统一为 id、username、balanceCents，三者都是字符串；登录与刷新响应中的user也是此结构。
- 桌面端账号栏、个人资料页与管理端账号栏均展示username；个人资料页不再展示显示名称。
- 两端演示数据、响应校验、OpenAPI、数据库校验SQL和回归测试同步调整。

用户关联仍使用users.id。orders.user_id、entitlements.user_id、sessions.user_id和商品创建/上传管理员ID不变；本次不更改任何用户的密码、用户名、余额或购买记录。

## 前端已预留，后端尚未开发

个人信息页增加“修改用户名”“修改密码”“充值”三个按钮，点击明确提示功能尚未开放，不收集输入、不发送不存在的请求。

| StoreService方法 | 后续输入 | 当前行为 |
| --- | --- | --- |
| updateUsername(newUsername) | 新用户名QString | 只发送尚未开放提示，不修改账号 |
| changePassword(currentPassword,newPassword) | 当前密码、新密码QString | 只提示，不保存或记录密码 |
| rechargeBalance(amountCents) | 充值金额整数最小单位的十进制字符串 | 只提示，不修改余额 |

这三个方法并非已实现的HTTP接口，故不在OpenAPI paths中添加虚假可调用路径。具体路径、请求DTO和响应待开发后端时确定。UI输入表单也留待该时接入上述方法；不需要重新创建资料页面。

后续修改用户名应由服务端检查唯一性并保持用户ID不变；修改密码需校验当前密码并决定会话失效规则；充值需通过充值业务确认入账，不能开放任意修改balanceCents的资料接口。

## 文件位置

- 后端：auth/entity/User.java、auth/vo/UserVO.java、auth/service/impl/AuthServiceImpl.java、UserServiceImpl.java及mapper/auth/UserMapper.xml。
- 桌面端：src/store/StoreService.h/.cpp预留请求方法；src/ui/StorePage.cpp提供按钮和提示；src/store/DemoServer.cpp同步演示协议。
- 管理端：public/js/contracts.js、app.js、mock-api.js同步新的User协议。
- 数据库：database/008-remove-user-display-name.sql。

## 验证记录

- 真实MySQL执行008迁移；本机忽略目录备份8个用户的原显示名称。六张业务表其余字段迁移前后摘要一致。
- 真实HTTP验证注册、登录、GET /me、刷新、退出：User固定三个公开字段，用户ID保持不变；独立测试账号与会话已清理。
- 后端完整verify：248项，247通过，1项需显式开启的浏览器测试跳过。
- 真实MySQL回归：27项，26通过，1项额外OSS下载测试未开启；覆盖认证、分类、商品、订单。
- 管理端：35项全部通过。
- Qt客户端：17项通过，1项需独立真实购买夹具的测试跳过。覆盖无显示名称的资料页、三个入口提示、预留方法不改变账号/密码/余额，以及既有商城流程。
- 已编译并更新E:/zlowallpaper运行程序；OpenAPI和前后端交接文档同步。

上述验证不表示三个预留的资料修改/充值接口已经实现。

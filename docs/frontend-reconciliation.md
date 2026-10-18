# 前端统一修复说明

日期：2026-10-16。后端API与数据表没有改动；本次修复工作台、桌面商城和演示/测试协议，并将客户端更新到用户指定的E盘运行目录。

## 1. 问题与修复

| 现象 | 原因及处理 |
| --- | --- |
| 工作台目录HTTP 200但CONTRACT_ERROR | contracts.js仍要求CNY；统一ZLC校验、模拟数据、价格单位及表单 |
| 围巾.png创建返回422 | 文件头FF D8 FF表明实际JPEG；选择图片时识别格式并以.jpg提交，后端严格验证保留 |
| 没有付款按钮/无法付款 | 移除testPaymentEnabled依赖及旧test-pay，PENDING显示正常壁纸币付款入口 |
| 没有个人信息与余额 | 账号旁新增按钮；展示ID、用户名、余额；打开和手动刷新读取GET /me |
| 已购封面空白 | 只为当前API同源已购封面附加Bearer，不向外部图片或OSS转发凭证 |
| 已购停售商品报404 | 直接显示已购列表完整资料，不再请求公开详情，公开权限不放宽 |
| 改封面后仍是旧图 | 刷新列表、已购同步及切换账号时清缓存、取消旧请求，以代次拒绝旧响应 |

## 2. 图片识别链路

`选择文件 → prepareUploadFile → 识别文件头 → 必要时生成正确名称/MIME的File → 暂存在编辑器 → 点击保存一次multipart提交`

先沿用大小和扩展名规则，只读前16字节识别JPEG、PNG、GIF、BMP、WebP。发现JPEG实际内容但名称为.png时，新建浏览器File对象，原字节不变，只将上传名改为.jpg；界面显示修正提示。视频仍由后端完整检查。

不会改用户磁盘原文件，也不以信任扩展名代替后端验证。HTML伪装图片仍被拒绝；后端保留文件签名、ffprobe、首帧解码、大小及哈希检查。文件识别中禁用保存；关闭弹窗或重新选文件后，旧异步结果不能覆盖新选择。

本次实际文件：`E:\壁纸\【哲风壁纸】围巾-插画-清冷.png`，JPEG编码、2358×1498、771093字节。真实工作台创建返回HTTP 201、IMAGE和正确元数据，封面与原文件提交名均为.jpg。

## 3. 个人资料与付款

`个人信息按钮 → StorePage.showProfile → StoreService.refreshUser → GET /me → userChanged → 更新页面`

页面只读，展示账号ID、用户名和壁纸币余额；首次打开自动同步，也可手动刷新。余额保持十进制字符串，拆分整数和两位小数，不转double，避免BIGINT精度丢失。已增加修改用户名、修改密码、充值三个入口及StoreService预留方法；当前仅提示尚未开放，不发送写请求、不修改本地余额。

`StoreService.payOrder → POST /orders/{id}/pay → 刷新用户/已购/订单`

付款成功实际扣壁纸币；余额不足显示后端错误并刷新余额，不自动反复付款。用户请求代次和已有会话代次共同防止旧账号、旧余额覆盖新响应。历史CNY订单仍按自身currency展示，不误标成壁纸币。

## 4. 已购封面与缓存

ApiClient.authorizeCoverRequest只对当前API同源的`/api/v1/me/wallpapers/{id}/cover`附加Bearer。公开图片和OSS签名下载不带账号凭证。

已购列表包含完整当前资料，停售商品直接展示这份资料。后端公开详情/封面的上架条件不变。

刷新商品、同步已购或切换账号时清空缓存，终止旧图片读取。coverGeneration拒绝旧请求回写。刷新列表也更新右侧已选商品及封面，而不只重绘左侧卡片。

## 5. 主要文件

| 文件 | 职责 |
| --- | --- |
| admin/public/js/contracts.js | ZLC校验/金额单位、余额字段、prepareUploadFile |
| admin/public/js/app.js | 异步识别图片、读取状态与过期选择保护、一次保存 |
| admin/public/js/mock-api.js、public/index.html | 当前模拟协议及壁纸币单位 |
| frontend/src/store/ApiClient.* | 已购封面同源鉴权 |
| frontend/src/store/StoreService.* | 正常付款、GET /me刷新和请求代次 |
| frontend/src/ui/StorePage.* | 个人信息、付款按钮、历史币种、停售详情和封面缓存 |
| frontend/src/store/DemoServer.* | 演示余额/扣币、已购封面权限、当前Order结构；与真实账号隔离 |
| frontend/tests/StoreTests.cpp | 协议、页面、权限与显式启用的真实后端测试 |

## 6. 验证及运行目录

- 管理端35项测试通过。
- Qt最终17项全部通过，包含显式启用的真实后端/OSS闭环；不是只验证演示服务器。
- Playwright真实工作台：目录正常；选择文件无上传；保存一次请求201；图片识别正确。390px页面scrollWidth=390，无横向溢出。
- Qt验证付款、重复付款不重扣、余额不足、资料页、封面权限、外部请求不带Bearer、缓存刷新及付款按钮不依赖旧字段。
- 真实Qt→后端→MySQL→OSS：测试余额100→88；商品停售仍可授权下载；实际文件大小及SHA通过；个人信息页显示88.00币。
- 测试数据与新OSS对象按临时账号和实际对象版本精确清理，不改原有用户密码、角色、余额及原文件。
- 重新编译并更新`E:\zlowallpaper\ZloWallpaper.exe`；源码在`D:\Codex\software\zlowallpaper\frontend`。保留运行目录的用户本地壁纸和配置。

工作台刷新页面后生效，桌面程序重新打开后可见个人信息按钮。资料修改、充值、退款等新业务接口不属于本次范围。

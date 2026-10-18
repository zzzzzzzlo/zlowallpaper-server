# 我的已购分页

日期：2026-10-17。沿用catalog模块和WallpaperMapper，不新增数据库表或第三方依赖。

## 接口

`GET /api/v1/me/wallpapers?page=1&pageSize=12`，必须携带当前账号Bearer。查询参数：

| 参数 | 含义 |
| --- | --- |
| page | 默认1，必须大于等于1 |
| pageSize | 默认12，允许1～50 |
| keyword | 可选，按标题包含匹配，最长100个Unicode字符；%和_按普通文本处理 |
| categoryId | 可选，按分类筛选；停用分类仍可查询已购商品 |
| wallpaperId | 可选，精确查询当前账号是否拥有某张商品；不是指定其他用户 |

成功响应的data从原数组改为与商城分页一致的对象：

```json
{
  "code": "OK",
  "message": "",
  "data": {"items": [], "page": 1, "pageSize": 12, "total": 0},
  "requestId": "本次请求编号"
}
```

items内商品字段不变，ID和sizeBytes仍为字符串。total为当前筛选条件下的总数。空结果或越界返回HTTP 200、空items，并保留请求页码及总数。page/pageSize非法返回400；未登录返回401。取消原200项总量上限及OWNED_LIST_LIMIT_EXCEEDED错误。

## 后端调用链

```text
AuthFilter认证并设置BaseContext
 → WallpaperController.getMyWallpapers：绑定/校验OwnedWallpaperQueryDTO
 → WallpaperServiceImpl.getMyWallpapers
     读取当前用户ID，整理筛选参数
     创建Page<WallpaperRow>
 → WallpaperMapper.selectOwnedWallpapers
     entitlements e按e.user_id限制账号
     JOIN wallpapers w：e.wallpaper_id=w.id，取当前商品资料
     JOIN categories c：w.category_id=c.id，取分类名称
     先筛选，再按e.granted_at DESC、e.id DESC排序
     MyBatis-Plus执行COUNT统计并补LIMIT
 → Service把当前页Row转换为WallpaperVO及已购封面URL
 → PageVO(items,page,pageSize,total) → ApiResponse → JSON
```

OwnedWallpaperQueryDTO继承商城已有WallpaperQueryDTO以复用分页和关键词规则，只增加wallpaperId。公开分页继续使用原SQL，只有已购Service调用权益SQL；停售商品只能出现在有权益的用户结果中。商品ID不能转为数据库Long时匹配空结果，不能退化成无筛选查询。

本查询不写数据库、不使用行锁。分页仍是每次请求读取当前数据，跨页期间新增购买或退款可能使数据移动，不承诺多次翻页拥有同一份快照；权益访问和下载授权仍由后端实时检查。

## 客户端

- StoreService.syncOwned只请求当前页，保存页码和筛选，付款后刷新当前条件；独立请求代次丢弃较晚到达的旧翻页响应。
- StorePage启用已购上一页/下一页，数量和页数来自服务端；搜索或分类改变回到第一页。权益减少导致越界时重新查询最后一页。
- 不再在客户端过滤单页数据，否则会造成搜索漏项、页数错误。
- owned()现在表示当前页。owns()使用已确认的权益；商城选中商品后以wallpaperId精确查询，避免把当前页之外的商品判断成未购买。付款/免费领取成功立即确认权益，登出清空账号缓存。最终下单和下载权限仍由服务器判断。
- DemoServer更新为同一分页协议；测试数据仅存在本机内存，不能代替真实后端。

这是一次data形状变更，后端、Qt客户端和Apifox定义需要一起更新。工作台不调用该用户接口，业务代码无需改动；两端文档同步新契约。

## 验证

后端已通过268项测试，失败0、错误0、跳过1。覆盖201张壁纸分5页取完、无重复、总数与越界、标题通配符、精确权益查询、停用分类、账号隔离、非法参数，以及原订单/上传/下载回归。

真实MySQL/HTTP验收通过：在临时8081后端创建201张停售商品及隔离账号权益，逐页取完，验证同时间排序、总数、空页、标题筛选、停用分类、精确权益范围和401/400分支。测试账号、商品、分类、订单、权益和会话均已清理，没有访问云文件或修改现有用户余额。

Qt StoreTests为23通过、0失败、1个显式真实付款用例未启用；资料库窗口回归3通过。新增服务测试和Widget测试验证25张已购的三页、末页按钮、搜索回首页、空结果、页签切换、跨页权益和退出清理；第三页截图已经查看。详情见 `.local-setup/owned-pagination-qt-tests.log`、`owned-pagination-ui/owned-page-3.png`、`owned-pagination-http.log`。

本机客户端可执行文件同步到`E:\zlowallpaper\ZloWallpaper.exe`，原有loaded_wallpaper文件和library.json保留；后端在IDEA重新运行即可使用新协议。临时验收后端会停止，不占用8080。

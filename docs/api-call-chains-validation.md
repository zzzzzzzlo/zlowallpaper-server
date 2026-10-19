# 接口调用链文档核对记录

核对日期：2026-10-18，对应 [接口调用链与技术说明](api-call-chains.md)。

| 核对项 | 结果 |
| --- | --- |
| Controller方法及类路由组合 | 25个业务HTTP接口，与文档索引25/25一致 |
| 接口标题和目录跳转 | 25个Markdown标题，25个完整标题链接匹配 |
| 代码围栏 | 27个代码块均闭合 |
| HTML兼容隐患 | 移除全部HTML锚点；正文没有裸露的HTML/泛型标签 |
| 源码引用 | 65个引用目标文件存在 |
| 本项目Class.member | 143项在对应源码中找到 |
| 第三方依赖 | 16项版本与当前打包JAR一致 |
| 静态OpenAPI | 24个操作已实现，仍漏列GET /catalog/covers/{id}，主文档已注明 |
| Redis封面缓存与代码整理 | 已实现；数据库结构和接口协议未改 |
| Maven测试 | 278项，0失败、0错误、1跳过 |
| 真实Redis/COS/HTTP | 四次成功封面请求仅一次COS读取；暖缓存后权限及故障回源通过 |

内容已同步COS/OSS统一ObjectStorage分流、已购分页、单商品精确权益查询及Qt消费链。已购data为items/page/pageSize/total，每页最多50条；订单列表仍是最多1000项数组。普通查询不写业务事务或持有行锁，商品/付款并发保护说明保持现有实现。

本次增加Redis封面缓存，统一分页方法代码组织并简化本地路径防御，调用链已同步三个封面入口与公共缓存类。真实验收仅两次读取同一现有COS封面，合计1542186字节，无云上传或删除；临时数据库夹具已清理。新增10项测试及缓存配置/边界见 [封面缓存说明](cover-cache-development.md)。既有Qt/真实MySQL分页验收见 [已购分页说明](owned-pagination.md)，COS迁移验收见 [COS迁移记录](cos-migration.md)。

格式原因：旧版HTML锚点紧贴Markdown标题，正文还有未放入代码标记的PageVO泛型。Obsidian不在HTML元素中解析Markdown；不同编辑/预览方式可能因此显示字面的标题、代码围栏和引用链接。新版使用原生Markdown标题链接，并把类型表达式放入反引号。官方说明：[HTML限制](https://obsidian.md/help/html)、[标题链接](https://obsidian.md/help/links)。

结构校验脚本和清单位于忽略提交的.local-setup/validate-call-chains-20261018.py、api-call-chains-inventory.json。本次另用markdown-it解析检查25个标题、27个代码块与目录目标；保留此前的纯Markdown标题方案。此前浏览器检查记录仍在本地，但本次未操作实际Obsidian应用或验证所有插件/主题组合。

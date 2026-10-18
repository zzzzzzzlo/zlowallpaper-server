# 接口调用链文档核对记录

核对日期：2026-10-18，对应 [接口调用链与技术说明](api-call-chains.md)。

| 核对项 | 结果 |
| --- | --- |
| Controller方法及类路由组合 | 25个业务HTTP接口，与文档索引25/25一致 |
| 接口标题和目录跳转 | 25个Markdown标题，25个完整标题链接匹配 |
| 代码围栏 | 27个代码块均闭合 |
| HTML兼容隐患 | 移除全部HTML锚点；正文没有裸露的HTML/泛型标签 |
| 源码引用 | 63个引用目标文件存在 |
| 本项目Class.member | 141项在对应源码中找到 |
| 第三方依赖 | 14项版本与当前打包JAR一致 |
| 静态OpenAPI | 24个操作已实现，仍漏列GET /catalog/covers/{id}，主文档已注明 |
| 本次业务代码、数据库、运行服务 | 没有修改或重启 |

内容已同步COS/OSS统一ObjectStorage分流、已购分页、单商品精确权益查询及Qt消费链。已购data为items/page/pageSize/total，每页最多50条；订单列表仍是最多1000项数组。普通查询不写业务事务或持有行锁，商品/付款并发保护说明保持现有实现。

本次只做文档与格式验收，不重新调用上传、下载或付款，不消耗云文件流量。既有268项后端及Qt/真实MySQL验收证据见 [已购分页说明](owned-pagination.md)，COS迁移验收见 [COS迁移记录](cos-migration.md)。

格式原因：旧版HTML锚点紧贴Markdown标题，正文还有未放入代码标记的PageVO泛型。Obsidian不在HTML元素中解析Markdown；不同编辑/预览方式可能因此显示字面的标题、代码围栏和引用链接。新版使用原生Markdown标题链接，并把类型表达式放入反引号。官方说明：[HTML限制](https://obsidian.md/help/html)、[标题链接](https://obsidian.md/help/links)。

结构校验脚本和清单位于忽略提交的.local-setup/validate-call-chains-20261018.py、api-call-chains-inventory.json。另用markdown-it解析及Playwright浏览器检查标题、代码块和跳转；实际Obsidian插件/主题的所有组合不在本次验收范围内。

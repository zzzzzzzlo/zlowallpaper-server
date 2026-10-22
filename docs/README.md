# 文档索引

这些文档以当前源码和配置为准。旧资产模型、测试付款、已购200条限制和阶段性验收记录不再作为当前设计说明。

| 文档 | 阅读目的 |
| --- | --- |
| [开发环境](development-environment.md) | 依赖、配置和启动 |
| [数据库初始化](../database/README.md) | 增量 SQL 顺序及当前结构 |
| [接口契约](api/README.md) / [OpenAPI](api/openapi.yaml) | 导入 Apifox，核对状态码和字段 |
| [调用链与技术说明](api-call-chains.md) | Controller → Service → Mapper → SQL |
| [数据模型](data-model.md) | 字段、Java 命名和约束 |
| [文件存储](storage.md) | COS/OSS、授权下载和失败清理 |
| [封面缓存](cover-cache-development.md) | 命中、回源合并与权限边界 |
| [封面压缩](cover-compression-development.md) | 1920/q=2、元数据与清理 |
| [测试说明](integration-testing.md) | 默认测试和显式开启的专项 |

推荐顺序：根 README → 调用链的公共组件 → 一条业务 → 对应 SQL。字段以 OpenAPI 为准，行为以源码和测试为准。

# 后端代码注释阅读说明

更新：2026-10-15。范围为src/main/java已实现的后端代码，以及src/test/java自动测试。未开发模块仍保留阶段说明。

每个声明的类、接口、record和匿名类说明用途和创建方式。Spring组件注明注入位置，普通对象注明new或数据库映射位置；DTO说明Controller绑定，Mapper说明MyBatis代理及XML，配置对象说明配置绑定。

每个显式方法和构造器说明作用、参数、返回内容、调用/引用位置。HTTP入口、定时任务、Jackson、数据绑定、异常处理和JUnit用例按实际框架调度说明，不把框架调用误写成“没有人调用”。

无参数或void方法明确说明。Lombok和record生成的访问方法在所属类解释，没有为补注释而改成手工getter/setter；这些访问方法只是读写对象字段，不自动执行SQL。

建议按Controller→Service接口→ServiceImpl→Mapper→XML阅读，遇到Entity、Row、VO时看类注释了解它是表记录、查询中间结果还是前端返回数据。调用位置包含自动测试，它们不会在生产环境运行。

以后移动或重命名方法、增加调用方时需同步这些说明，源码注释不会自动随运行时更新。

最初补注释时用JDK语法树核对声明、参数和引用位置，并逐文件比较注释前后的Java代码标记，确认补注释不改变代码内容。2026-10-14商品调用链整理另有业务代码重构，以完整回归测试及新的注释语法树检查验证。

当前覆盖：118个含类型声明的Java文件，共124个类型、557个显式方法/构造器；其中生产代码105个类型、283个方法/构造器。类注释、方法注释、所有显式参数的@param及非void方法的@return均通过语法树核对；匿名类也有创建和回调说明。getMyOrders、catalog的getMyWallpapers与download的createDownloadGrant主流程集中在各自Service入口，具体见order-query-development.md和download-development.md。

商品主流程直接阅读AdminWallpaperServiceImpl.createWallpaper/updateWallpaper；原save分流和WallpaperRules已移除。数据库状态判断集中到WallpaperPersistenceService。因并发变化保留的分类第二次检查、权限重读、商品快照比较及商品行锁，均在代码中用“【并发复查】”或“【并发控制】”解释；上传组件也标明两份文件的并发槽位限制。详细阅读顺序见catalog-development3.md。

2026-10-15最新已购/下载验证：完整后端248项，247通过、1项浏览器条件测试跳过；已购列表最终200项与JSON大小边界另外22项通过；新增真实MySQL/OSS共3项通过，完整读取35,755,786字节，SHA一致。云测试没有上传或删除文件。本次只同步前端契约和交接说明，不改两端程序；Qt仍需适配已购封面Bearer及此前虚拟币界面。详细范围见download-development.md。

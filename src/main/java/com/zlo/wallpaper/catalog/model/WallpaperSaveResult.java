package com.zlo.wallpaper.catalog.model;

import com.zlo.wallpaper.catalog.entity.Wallpaper;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * 承接商品修改后的实体与待清理旧文件，供事务提交后处理。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 显式实例化位置：WallpaperPersistenceService.update。
 * 字段访问：Lombok注解或record由编译器生成访问方法；其作用是读写本对象字段，不自行执行数据库操作。
 */
@Data
public class WallpaperSaveResult {
    private Wallpaper wallpaper;
    private List<UploadedFile> replacedFiles = new ArrayList<>();
}

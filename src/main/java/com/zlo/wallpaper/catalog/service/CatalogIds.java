package com.zlo.wallpaper.catalog.service;

import com.zlo.wallpaper.common.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 集中处理外部字符串ID与本机正数BIGINT编号的安全转换。
 * 创建方式：静态工具类不实例化；私有构造器防止业务代码new。
 */
public final class CatalogIds {
    /**
     * 限制外部实例化，当前类只通过静态方法使用。
     * 调用位置：没有业务调用，此构造器用于禁止new。
     * 输入参数：无。
     * 返回：无返回值（构造器用于初始化本对象）。
     *
     */
    private CatalogIds() { }

    /**
     * 把外部ID安全转换为本机正数BIGINT编号，不能映射时返回null。
     * 调用/引用位置：CategoryServiceImpl.getCategoryForUpdate、CoverService.read、CoverService.readAdmin、OrderServiceImpl.createOrder、WallpaperPersistenceService.checkCategory、AdminWallpaperServiceImpl.databaseId、WallpaperServiceImpl.detail、WallpaperServiceImpl.page。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 正数数据库编号；外部ID非法、越界或不能映射时为null
     */
    public static Long findDatabaseId(String id) {
        if (id == null || !id.matches("[0-9]{1,19}")) { return null; }
        try {
            long value = Long.parseLong(id);
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) { return null; }
    }

    /**
     * 检查外部ID字符和长度，非法输入返回400而不进入数据库转换。
     * 调用/引用位置：CategoryServiceImpl.getCategoryForUpdate、CoverService.read、CoverService.readAdmin、AdminWallpaperServiceImpl.databaseId、AdminWallpaperServiceImpl.validateMutation、WallpaperServiceImpl.detail。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     */
    public static void validatePathId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "id 格式不符合接口约定");
        }
    }
}

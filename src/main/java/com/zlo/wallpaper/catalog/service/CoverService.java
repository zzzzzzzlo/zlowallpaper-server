package com.zlo.wallpaper.catalog.service;

import com.zlo.wallpaper.catalog.mapper.WallpaperMapper;
import com.zlo.wallpaper.catalog.model.CoverRow;
import com.zlo.wallpaper.catalog.storage.ObjectStorage;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.common.BaseContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 按公开、管理员或本账号已购权限读取封面，复用本地路径及云文件大小校验。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：AdminWallpaperController.coverService、CoverController的构造器。
 */
@Service
public class CoverService {
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif",
            "image/webp", "image/bmp");
    private final WallpaperMapper wallpaperMapper;
    private final Path storageRoot;
    @Autowired private ObjectStorage storage;
    @Autowired private CoverCacheService cache;

    /**
     * 构造CoverService并保存其依赖或初始属性。
     * 框架调用位置：Spring创建当前组件时调用。
     * 返回：无返回值（构造器用于初始化本对象）。
     *
     * @param wallpaperMapper 由Spring注入的数据库Mapper代理
     * @param storageRoot 服务器配置的本地存储根目录，封面路径必须限制在此目录内
     */
    public CoverService(WallpaperMapper wallpaperMapper, @Value("${wallpaper.storage.root}") String storageRoot) {
        this.wallpaperMapper = wallpaperMapper;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    /**
     * 封装封面Resource、媒体类型和字节长度，供公开、管理、已购封面入口返回HTTP响应。
     * 创建方式：readFile通过record构造器创建，编译器生成字段访问方法；Controller取出Resource输出图片。
     * 显式实例化位置：CoverService.readFile。
     */
    public record Cover(Resource resource, MediaType mediaType, long size) { }

    /**
     * 根据商品可见性读取公开封面，并返回可用于HTTP输出的资源信息。
     * 调用/引用位置：CoverController.read。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 封面Resource、Content-Type和实际字节长度，供Controller输出图片
     */
    public Cover read(String id) {
        CatalogIds.validatePathId(id);
        Long databaseId = CatalogIds.findDatabaseId(id);
        CoverRow asset = databaseId == null ? null : wallpaperMapper.selectPublicCover(databaseId);
        return readFile(asset);
    }

    /**
     * 读取管理员可见的商品封面，允许草稿和下架商品但仍限制资源位置。
     * 调用/引用位置：AdminWallpaperController.getCover。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 封面Resource、Content-Type和实际字节长度，供Controller输出图片
     */
    public Cover readAdmin(String id) {
        CatalogIds.validatePathId(id);
        Long databaseId = CatalogIds.findDatabaseId(id);
        return readFile(databaseId == null ? null : wallpaperMapper.selectAdminCover(databaseId));
    }

    /**
     * 读取本账号已购商品封面；允许停售，公开入口和管理员入口的查询条件保持不变。
     * 调用位置：CoverController.readOwned，AuthFilter验证登录后进入。
     * @param id 已购商品的字符串ID
     * @return 本账号有权益时的封面资源，否则返回401或404业务异常
     */
    public Cover readOwned(String id) {
        Long userId = BaseContext.getCurrentId();
        if (userId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "请先登录");
        }
        CatalogIds.validatePathId(id);
        Long databaseId = CatalogIds.findDatabaseId(id);
        return readFile(databaseId == null ? null : wallpaperMapper.selectOwnedCover(userId, databaseId));
    }

    /**
     * 权限查询完成后读封面：先Redis，未命中再读云存储并写缓存；本地旧文件保留基础路径检查。
     * 调用/引用位置：CoverService.read、CoverService.readAdmin、CoverService.readOwned。
     *
     * @param asset 数据库封面位置或可信上传文件元数据，按当前方法所需类型使用
     * @return 封面Resource、Content-Type和实际字节长度，供Controller输出图片
     */
    private Cover readFile(CoverRow asset) {
        if (asset == null) { throw missing(); }
        if (asset.mimeType() == null || !IMAGE_TYPES.contains(asset.mimeType()) || asset.sizeBytes() == null || asset.sizeBytes() <= 0 || asset.sizeBytes() > 4194304) {
            throw missing();
        }
        if ("OSS".equals(asset.storageProvider()) || "COS".equals(asset.storageProvider())) {
            // 查询只放行商品自己的 READY 封面，不接受客户端提供任意对象地址。
            if (asset.bucket() == null || asset.bucket().isBlank()) { throw missing(); }
            String key = "cover:v1:" + asset.storageProvider() + ":" + asset.bucket() + ":" + asset.objectKey();
            byte[] bytes = cache.getCover(key);
            if (bytes == null || bytes.length != asset.sizeBytes()) {
                // 图片字节可以共享，权限不能缓存：三个入口都先查各自的可见性/权益SQL。
                bytes = storage.readCover(asset.storageProvider(), asset.bucket(), asset.objectKey(), asset.sizeBytes());
                cache.putCover(key, bytes);
            }
            return new Cover(new ByteArrayResource(bytes), MediaType.parseMediaType(asset.mimeType()), asset.sizeBytes());
        }
        if (!"LOCAL".equals(asset.storageProvider())) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "当前存储方式尚未接入");
        }
        try {
            Path file = storageRoot.resolve(asset.objectKey()).normalize();
            // 本地目录由服务端管理，保留规范化路径防止../越界，并检查文件存在和大小。
            if (!file.startsWith(storageRoot) || !Files.isRegularFile(file) || Files.size(file) != asset.sizeBytes()) { throw missing(); }
            return new Cover(new FileSystemResource(file), MediaType.parseMediaType(asset.mimeType()), asset.sizeBytes());
        } catch (IOException | java.nio.file.InvalidPathException exception) {
            throw missing();
        }
    }

    /**
     * 构造当前商品或封面不存在的404业务异常。
     * 调用/引用位置：CoverService.readFile。
     * 输入参数：无。
     *
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException missing() {
        return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "封面不存在或不可公开访问");
    }
}

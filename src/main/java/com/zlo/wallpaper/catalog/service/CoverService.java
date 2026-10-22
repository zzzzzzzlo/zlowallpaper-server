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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    // 只保存本进程正在加载的封面；完成后移除，不作为另一层长期图片缓存。
    private final ConcurrentMap<String, CompletableFuture<byte[]>> loadingCovers = new ConcurrentHashMap<>();
    @Value("${wallpaper.cover-cache.wait-seconds:10}") private long waitSeconds = 10;

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
     * 权限查询完成后读封面：先Redis，同键未命中请求共享本进程一次回源；本地旧文件保留基础路径检查。
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
                CompletableFuture<byte[]> task = new CompletableFuture<>();
                CompletableFuture<byte[]> running = loadingCovers.putIfAbsent(key, task);
                if (running == null) {
                    // 当前请求登记成功，负责回源；不同封面键可以各自加载，不持有全局锁。
                    try {
                        // 并发复查：首次查Redis后，前一批请求可能已填好缓存并移除了任务。
                        // putIfAbsent只确定本批加载者，不锁Redis；复查避免新一批又重复读取COS。
                        bytes = cache.getCover(key);
                        if (bytes == null || bytes.length != asset.sizeBytes()) {
                            bytes = storage.readCover(asset.storageProvider(), asset.bucket(), asset.objectKey(), asset.sizeBytes());
                            cache.putCover(key, bytes);
                        }
                        // 即使Redis不可用，已等候的请求仍能共享本次读到的字节。
                        task.complete(bytes);
                    } catch (RuntimeException | Error exception) {
                        task.completeExceptionally(exception);
                        throw exception;
                    } finally {
                        loadingCovers.remove(key, task);
                    }
                } else {
                    // 已有请求负责同一封面，当前线程等结果，不额外创建异步线程或再次回源。
                    try {
                        bytes = running.get(waitSeconds, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "封面加载等待已中断，请重试");
                    } catch (TimeoutException exception) {
                        // 等待者不能移除/取消仍在执行的任务，否则后续请求会另开一次回源。
                        throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "封面加载较慢，请稍后重试");
                    } catch (ExecutionException exception) {
                        // 解包原异常，让等待者保持回源请求原来的404/503等错误含义。
                        Throwable cause = exception.getCause();
                        if (cause instanceof RuntimeException) { throw (RuntimeException) cause; }
                        if (cause instanceof Error) { throw (Error) cause; }
                        throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "封面加载失败，请稍后重试");
                    }
                }
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

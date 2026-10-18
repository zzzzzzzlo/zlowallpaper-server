package com.zlo.wallpaper.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlo.wallpaper.catalog.mapper.AdminWallpaperMapper;
import com.zlo.wallpaper.catalog.model.StoredObject;
import com.zlo.wallpaper.catalog.model.UploadedFile;
import com.zlo.wallpaper.catalog.storage.ObjectStorage;
import com.zlo.wallpaper.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.DirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录持久待清理任务，补偿失败上传和回收替换旧文件；删除前再次检查商品引用。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：AdminWallpaperIntegrationTest.cleanup、AdminWallpaperServiceImpl.cleanup。
 */
@Service
public class ProductFileCleanupService {
    private static final Logger log = LoggerFactory.getLogger(ProductFileCleanupService.class);
    private final Set<String> activeUploads = ConcurrentHashMap.newKeySet();
    @Autowired
    private AdminWallpaperMapper mapper;
    @Autowired
    private ObjectStorage storage;
    @Autowired
    private ObjectMapper json;
    @Value("${wallpaper.storage.root}") private String storageRoot;

    /**
     * 把新上传对象加入活动集合并持久记录回收任务，任务不能保存则中止商品保存。
     * 调用/引用位置：AdminWallpaperIntegrationTest.cleanupNeverDeletesFilesStillReferencedByAnyProductOrActiveUpload、AdminWallpaperServiceImpl.createWallpaper、AdminWallpaperServiceImpl.updateWallpaper。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param file 服务器临时文件路径、上传对象或可信当前文件元数据
     */
    public void trackUpload(UploadedFile file) {
        StoredObject object = object(file);
        if (object == null) { return; }
        String key = taskKey(object);
        activeUploads.add(key);
        try { writeTask(object); }
        catch (IOException exception) {
            log.error("上传清理任务无法写入", exception);
            activeUploads.remove(key);
            // 无法记录清理任务时不继续创建商品，尝试回收刚上传的文件。
            attempt(object, taskPath(object));
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "CLEANUP_STORAGE_UNAVAILABLE", "文件清理记录无法保存，请检查服务器存储目录");
        }
    }

    /**
     * 商品提交成功后移除新上传对象的临时回收任务和活动标识。
     * 调用/引用位置：AdminWallpaperServiceImpl.createWallpaper、AdminWallpaperServiceImpl.updateWallpaper。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param file 服务器临时文件路径、上传对象或可信当前文件元数据
     */
    public void completeUpload(UploadedFile file) {
        StoredObject object = object(file);
        if (object == null) { return; }
        activeUploads.remove(taskKey(object));
        removeTask(taskPath(object));
    }

    /**
     * 商品保存失败时登记并尝试回收本次新上传文件。
     * 调用/引用位置：AdminWallpaperIntegrationTest.cleanupNeverDeletesFilesStillReferencedByAnyProductOrActiveUpload、AdminWallpaperServiceImpl.createWallpaper、AdminWallpaperServiceImpl.updateWallpaper。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param file 服务器临时文件路径、上传对象或可信当前文件元数据
     */
    public void rollbackUpload(UploadedFile file) {
        StoredObject object = object(file);
        if (object == null) { return; }
        activeUploads.remove(taskKey(object));
        persistAndAttempt(object);
    }

    /**
     * 商品文件替换提交后登记并尝试回收旧文件。
     * 调用/引用位置：AdminWallpaperIntegrationTest.cleanupNeverDeletesFilesStillReferencedByAnyProductOrActiveUpload、AdminWallpaperServiceImpl.updateWallpaper。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param file 服务器临时文件路径、上传对象或可信当前文件元数据
     */
    public void retireFile(UploadedFile file) {
        StoredObject object = object(file);
        if (object != null) { persistAndAttempt(object); }
    }

    /**
     * 先持久保存待清理对象，再尝试检查引用并删除。
     * 调用/引用位置：ProductFileCleanupService.retireFile、ProductFileCleanupService.rollbackUpload。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param object 待上传、删除或持久记录的可信存储对象位置
     */
    private void persistAndAttempt(StoredObject object) {
        try { writeTask(object); }
        catch (IOException exception) { log.error("无法保存OSS清理任务，objectKey={}，需要人工清理", object.getObjectKey()); }
        attempt(object, taskPath(object));
    }

    /**
     * 把可信OSS文件元数据转为存储对象，未知来源或本地文件不参与云端自动删除。
     * 调用/引用位置：ProductFileCleanupService.completeUpload、ProductFileCleanupService.retireFile、ProductFileCleanupService.rollbackUpload、ProductFileCleanupService.trackUpload。
     *
     * @param file 服务器临时文件路径、上传对象或可信当前文件元数据
     * @return OSS实际Bucket、对象键和版本标识，用于之后精确清理
     */
    private StoredObject object(UploadedFile file) {
        if (file == null || (!"OSS".equals(file.getStorageProvider()) && !"COS".equals(file.getStorageProvider()))) { return null; }
        StoredObject object = new StoredObject(file.getStorageProvider(), file.getBucket(), file.getObjectKey(), file.getObjectVersionId());
        if (!owned(object)) {
            // 兼容老LOCAL或手工导入商品，不删除未知来源的云对象。
            log.warn("未删除不属于本应用上传路径的文件，需要人工核对");
            return null;
        }
        return object;
    }

    /**
     * 判断对象键与Bucket是否属于本应用上传命名空间。
     * 调用/引用位置：ProductFileCleanupService.object、ProductFileCleanupService.retryPendingFiles。
     *
     * @param object 待上传、删除或持久记录的可信存储对象位置
     * @return 判断结果；true表示上述条件成立，false表示不成立
     */
    private boolean owned(StoredObject object) {
        return object != null && ("OSS".equals(object.getProvider()) || "COS".equals(object.getProvider()))
                && object.getBucket() != null && !object.getBucket().isBlank() && object.getObjectKey() != null
                && object.getObjectKey().matches("zlowallpaper/(covers|originals)/[a-f0-9-]{36}\\.(jpg|jpeg|png|bmp|webp|gif|mp4|webm|mov|avi)");
    }

    /**
     * 跳过活动上传，核对数据库引用后尝试删除对象；失败则保留任务重试。
     * 调用/引用位置：ProductFileCleanupService.persistAndAttempt、ProductFileCleanupService.retryPendingFiles、ProductFileCleanupService.trackUpload。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param object 待上传、删除或持久记录的可信存储对象位置
     * @param task 服务器控制的待清理任务文件路径
     */
    private void attempt(StoredObject object, Path task) {
        if (activeUploads.contains(taskKey(object))) { return; }
        try {
            // DB提交结果未知、DB不可用时不能贸然删文件；任务保留，等下次重新查引用。
            if (mapper.countFileReferences(object.getBucket(), object.getObjectKey()) == 0) { storage.deleteObject(object); }
            removeTask(task);
        } catch (RuntimeException exception) {
            log.warn("文件清理暂未完成，objectKey={}，等待后台重试", object.getObjectKey());
        }
    }

    /**
     * 按计划分批读取待清理任务，验证来源并重试，隔离损坏任务。
     * 调用/引用位置：AdminWallpaperIntegrationTest.cleanupNeverDeletesFilesStillReferencedByAnyProductOrActiveUpload、AdminWallpaperIntegrationTest.databaseFailureRollsBackProductAndRetainsFailedDeletesForRetry、AdminWallpaperIntegrationTest.fixtures。
     * 框架调用位置：Spring定时任务调度器按配置执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Scheduled(initialDelayString = "${wallpaper.catalog.cleanup-delay-ms:300000}",
            fixedDelayString = "${wallpaper.catalog.cleanup-delay-ms:300000}")
    public void retryPendingFiles() {
        Path directory = directory();
        if (!Files.isDirectory(directory)) { return; }
        // 每批最多64项，目录由服务器配置；只处理本服务生成的JSON任务。
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory, "*.json")) {
            int processed = 0;
            for (Path task : paths) {
                if (processed++ >= 64) { break; }
                try {
                    StoredObject object = json.readValue(Files.readString(task), StoredObject.class);
                    if (owned(object) && task.equals(taskPath(object))) { attempt(object, task); }
                    else { quarantine(task); }
                } catch (IOException | RuntimeException exception) { quarantine(task); }
            }
        } catch (IOException exception) { log.warn("清理任务目录暂不可读取"); }
    }

    /**
     * 把不可信或损坏的清理任务改名隔离，避免后台重复处理。
     * 调用/引用位置：ProductFileCleanupService.retryPendingFiles。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param task 服务器控制的待清理任务文件路径
     */
    private void quarantine(Path task) {
        log.warn("隔离无效清理任务：{}", task.getFileName());
        try { Files.move(task, task.resolveSibling(task.getFileName() + ".invalid"), StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException exception) { log.warn("无效任务暂无法隔离"); }
    }

    /**
     * 用临时文件写入待清理对象JSON，再替换为正式任务文件。
     * 调用/引用位置：ProductFileCleanupService.persistAndAttempt、ProductFileCleanupService.trackUpload。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param object 待上传、删除或持久记录的可信存储对象位置
     */
    private void writeTask(StoredObject object) throws IOException {
        Files.createDirectories(directory());
        Path temporary = Files.createTempFile(directory(), "cleanup-", ".tmp");
        try {
            Files.writeString(temporary, json.writeValueAsString(object));
            Files.move(temporary, taskPath(object), StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    /**
     * 删除已经处理的任务文件，失败时保留日志供后续核对。
     * 调用/引用位置：ProductFileCleanupService.attempt、ProductFileCleanupService.completeUpload。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param task 服务器控制的待清理任务文件路径
     */
    private void removeTask(Path task) {
        try { Files.deleteIfExists(task); }
        catch (IOException exception) { log.warn("已处理清理任务暂不可删除：{}", task.getFileName()); }
    }

    /**
     * 根据服务器配置计算本机待清理任务目录。
     * 调用/引用位置：ProductFileCleanupService.retryPendingFiles、ProductFileCleanupService.taskPath、ProductFileCleanupService.writeTask。
     * 输入参数：无。
     *
     * @return 上述处理得到的Path结果；业务校验失败时通过异常返回错误
     */
    private Path directory() { return Path.of(storageRoot).toAbsolutePath().normalize().resolve(".catalog-cleanup"); }
    /**
     * 根据对象位置计算唯一清理任务文件路径。
     * 调用/引用位置：ProductFileCleanupService.completeUpload、ProductFileCleanupService.persistAndAttempt、ProductFileCleanupService.retryPendingFiles、ProductFileCleanupService.trackUpload、ProductFileCleanupService.writeTask。
     *
     * @param object 待上传、删除或持久记录的可信存储对象位置
     * @return 上述处理得到的Path结果；业务校验失败时通过异常返回错误
     */
    private Path taskPath(StoredObject object) { return directory().resolve(taskKey(object) + ".json"); }
    /**
     * 对Bucket、对象键和存储版本计算哈希，作为稳定任务文件名。
     * 调用/引用位置：ProductFileCleanupService.attempt、ProductFileCleanupService.completeUpload、ProductFileCleanupService.rollbackUpload、ProductFileCleanupService.taskPath、ProductFileCleanupService.trackUpload。
     *
     * @param object 待上传、删除或持久记录的可信存储对象位置
     * @return 上述处理得到的String结果；业务校验失败时通过异常返回错误
     */
    private String taskKey(StoredObject object) {
        try {
            String value = object.getBucket() + "\n" + object.getObjectKey() + "\n" + object.getVersionId();
            // 旧OSS任务沿用原键名；COS任务加前缀，避免两个服务碰巧同桶/键时串任务。
            if ("COS".equals(object.getProvider())) { value = "COS\n" + value; }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}

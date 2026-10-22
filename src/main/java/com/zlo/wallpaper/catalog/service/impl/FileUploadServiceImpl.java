package com.zlo.wallpaper.catalog.service.impl;

import com.zlo.wallpaper.catalog.model.UploadedFile;
import com.zlo.wallpaper.catalog.media.MediaInspector;
import com.zlo.wallpaper.catalog.model.MediaMetadata;
import com.zlo.wallpaper.catalog.model.StoredObject;
import com.zlo.wallpaper.catalog.service.FileUploadService;
import com.zlo.wallpaper.catalog.storage.ObjectStorage;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * 将上传内容写入临时文件并检查真实媒体；封面压缩后更新元数据，再上传私有COS/OSS。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：AdminWallpaperServiceImpl.uploader。
 */
@Service
public class FileUploadServiceImpl implements FileUploadService {
    private static final Logger log = LoggerFactory.getLogger(FileUploadServiceImpl.class);
    private static final long COVER_LIMIT = 10L * 1024 * 1024;
    private static final long ORIGINAL_LIMIT = 10L * 1024 * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "bmp", "webp", "gif", "mp4", "webm", "mov", "avi");
    // 【并发控制：上传槽位】同一后端进程最多同时处理两份文件，限制临时磁盘和媒体解析负载。
    // 一次创建包含封面和原文件的两次顺序上传，不表示最多两个完整创建请求。
    private final Semaphore uploadSlots = new Semaphore(2);
    @Autowired private MediaInspector mediaInspector;
    @Autowired private ObjectStorage storage;
    @Autowired private Clock clock;

    /**
     * 校验当前管理员和上传文件，流式计算哈希并检查真实媒体后上传OSS。
     * 调用/引用位置：AdminWallpaperIntegrationTest.changedCategoryAndRevokedRoleDuringLongUploadAreRecheckedAndBothFilesReclaimed、AdminWallpaperIntegrationTest.concurrentFileReplacementReturnsConflictAndKeepsOtherRequestFile、AdminWallpaperIntegrationTest.databaseFailureRollsBackProductAndRetainsFailedDeletesForRetry、AdminWallpaperIntegrationTest.fixtures、AdminWallpaperIntegrationTest.originalUploadFailureReclaimsAlreadyUploadedCoverAndDoesNotInsert、AdminWallpaperServiceImpl.createWallpaper、AdminWallpaperServiceImpl.updateWallpaper、FileUploadServiceTest.cloudFailureKeepsErrorAndCleansTemporaryFile、FileUploadServiceTest.declaredAndActualCoverLimitsAreEnforced、FileUploadServiceTest.invalidFilenameNeverUploads、FileUploadServiceTest.invalidMediaNeverUploads、FileUploadServiceTest.invalidPurposeNeverUploads、FileUploadServiceTest.missingIdentityNeverUploads、FileUploadServiceTest.missingOrEmptyFileNeverUploads、FileUploadServiceTest.returnsTrustedFileMetadataAndExactOssObjectVersionWithoutDatabase。
     *
     * @param file 服务器临时文件路径、上传对象或可信当前文件元数据
     * @param purpose 文件用途ORIGINAL或COVER，决定允许类型及大小上限
     * @return 检查及上传完成的文件显示名、存储位置、哈希、大小和媒体信息
     */
    @Override
    public UploadedFile uploadFile(MultipartFile file, String purpose) {
        // 1. 检查上传上下文与文件基本条件；这里不查询商品分类或重复检查商品DTO。
        // 身份与角色由 AuthFilter 检查；从上下文读取上传者，不接受客户端指定用户 ID。
        Long uploadedBy = BaseContext.getCurrentId();
        if (uploadedBy == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "请先登录");
        }
        if (file == null || !("COVER".equals(purpose) || "ORIGINAL".equals(purpose))) {
            throw validation("请提交 file 文件和正确的 purpose");
        }
        if (file.isEmpty()) { throw validation("上传文件不能为空"); }
        long limit = "COVER".equals(purpose) ? COVER_LIMIT : ORIGINAL_LIMIT;
        if (file.getSize() > limit) { throw tooLarge(); }
        String filename = filename(file.getOriginalFilename());
        String extension = extension(filename);
        // 【并发控制】拿不到槽位立即返回429；拿到后，在finally中释放。
        if (!uploadSlots.tryAcquire()) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "UPLOAD_BUSY", "当前上传任务较多，请稍后重试");
        }
        Path temporaryFile = null;
        Path compressedFile = null;
        try {
            // 2. 边复制边计算完整SHA；再解析实际媒体内容，得到可信宽高、帧率和类型。
            temporaryFile = Files.createTempFile("zlo-upload-", "." + extension);
            UploadedFile asset = new UploadedFile();
            copyAndHash(file, temporaryFile, limit, asset);
            MediaMetadata media = mediaInspector.inspect(temporaryFile, extension, purpose);
            Path uploadFile = temporaryFile;
            if ("COVER".equals(purpose)) {
                // 封面压缩在上传槽位内、数据库事务外；原壁纸文件不进入此分支。
                compressedFile = Files.createTempFile("zlo-cover-", ".jpg");
                media = mediaInspector.compressCover(temporaryFile, compressedFile);
                long compressedSize = Files.size(compressedFile);
                // 输入放宽到10 MiB，输出仍遵守数据库/读取接口已有的4 MiB契约。
                if (compressedSize <= 0 || compressedSize > 4L * 1024 * 1024) {
                    throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MEDIA", "封面压缩结果无效或超过4 MiB，请调整封面后重试");
                }
                asset.setSizeBytes(compressedSize);
                asset.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(compressedFile))));
                extension = "jpg";
                uploadFile = compressedFile;
            }
            asset.setPurpose(purpose);
            asset.setOriginalFilename(filename);
            // UUID 文件键由服务器生成，原文件名只作展示，不参与存储路径。
            asset.setObjectKey("zlowallpaper/" + ("COVER".equals(purpose) ? "covers/" : "originals/")
                    + UUID.randomUUID() + "." + extension);
            asset.setMimeType(media.getMimeType());
            asset.setFileExtension(extension);
            asset.setWidth(media.getWidth());
            asset.setHeight(media.getHeight());
            asset.setFps(media.getFps());
            asset.setStatus("READY");
            asset.setUploadedBy(uploadedBy);
            LocalDateTime currentTime = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
            asset.setCreatedAt(currentTime);
            asset.setUpdatedAt(currentTime);
            // 3. 上传OSS并返回元数据。商品是否保存成功以及文件回收由外层商品Service决定。
            StoredObject stored = storage.upload(uploadFile, asset.getObjectKey(), asset.getMimeType(), asset.getSizeBytes());
            asset.setStorageProvider(stored.getProvider());
            asset.setBucket(stored.getBucket());
            asset.setObjectVersionId(stored.getVersionId());
            // 这里只上传并返回可信元数据。商品保存负责写wallpapers，失败时回收这份云对象。
            return asset;
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "UPLOAD_IO_ERROR", "上传文件暂不可处理，请检查本机临时磁盘空间");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        } finally {
            for (Path temporary : new Path[]{temporaryFile, compressedFile}) {
                if (temporary == null) { continue; }
                try { Files.deleteIfExists(temporary); }
                catch (IOException exception) {
                    temporary.toFile().deleteOnExit();
                    log.warn("上传临时文件清理失败，需检查临时目录权限");
                }
            }
            uploadSlots.release();
        }
    }

    /**
     * 边读取上传流边写临时文件并计算SHA-256，超过实际大小上限立即拒绝。
     * 调用/引用位置：FileUploadServiceImpl.uploadFile。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param file 服务器临时文件路径、上传对象或可信当前文件元数据
     * @param target 服务器生成的临时文件路径，上传内容流式写入该文件
     * @param limit 允许的实际文件大小上限，单位字节
     * @param asset 数据库封面位置或可信上传文件元数据，按当前方法所需类型使用
     */
    private void copyAndHash(MultipartFile file, Path target, long limit, UploadedFile asset) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 不可用", exception); }
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(target)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                if (size > limit) { throw tooLarge(); }
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        }
        if (size == 0 || size != file.getSize()) { throw validation("文件大小与实际内容不符"); }
        asset.setSizeBytes(size);
        asset.setSha256(HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * 从上传对象读取安全显示文件名，拒绝路径字符和非法文件名。
     * 调用/引用位置：FileUploadServiceImpl.uploadFile。
     *
     * @param original 本次已校验且上传完成的原文件元数据
     * @return 上述处理得到的String结果；业务校验失败时通过异常返回错误
     */
    private String filename(String original) {
        if (original == null) { throw validation("文件名不能为空"); }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isBlank() || name.codePointCount(0, name.length()) > 255) {
            throw validation("文件名须为1–255个字符");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) { throw validation("文件名不能含控制字符"); }
        }
        return name;
    }

    /**
     * 从已校验文件名提取小写扩展名，并检查支持的类型。
     * 调用/引用位置：FileUploadServiceImpl.uploadFile。
     *
     * @param filename 已经检查过路径字符和长度的原始显示文件名
     * @return 上述处理得到的String结果；业务校验失败时通过异常返回错误
     */
    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension)) {
            throw new BusinessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE", "不支持此文件扩展名");
        }
        return extension;
    }

    /**
     * 构造上传用途或文件名校验失败的400业务异常。
     * 调用/引用位置：FileUploadServiceImpl.copyAndHash、FileUploadServiceImpl.filename、FileUploadServiceImpl.uploadFile。
     *
     * @param message 允许向前端展示的业务说明
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException validation(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
    /**
     * 构造上传文件超过实际允许大小的413业务异常。
     * 调用/引用位置：FileUploadServiceImpl.copyAndHash、FileUploadServiceImpl.uploadFile。
     * 输入参数：无。
     *
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException tooLarge() {
        return new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "封面上传最大10 MiB，原文件最大10 GiB");
    }
}

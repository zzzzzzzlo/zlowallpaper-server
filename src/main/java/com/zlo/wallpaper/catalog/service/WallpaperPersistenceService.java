package com.zlo.wallpaper.catalog.service;

import com.zlo.wallpaper.auth.entity.User;
import com.zlo.wallpaper.auth.mapper.UserMapper;
import com.zlo.wallpaper.catalog.dto.WallpaperMutationDTO;
import com.zlo.wallpaper.catalog.entity.Category;
import com.zlo.wallpaper.catalog.entity.Wallpaper;
import com.zlo.wallpaper.catalog.mapper.AdminWallpaperMapper;
import com.zlo.wallpaper.catalog.mapper.CategoryMapper;
import com.zlo.wallpaper.catalog.model.UploadedFile;
import com.zlo.wallpaper.catalog.model.WallpaperSaveResult;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

/**
 * 集中处理商品的数据库状态：上传前分类预检查、短事务保存、并发复查和发布。
 * 创建/修改方法只被AdminWallpaperServiceImpl调用；这里不上传OSS文件。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：AdminWallpaperServiceImpl.persistence。
 */
@Service
public class WallpaperPersistenceService {
    @Autowired
    private AdminWallpaperMapper mapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private Clock clock;

    /**
     * 在短事务中重新检查权限及分类后创建商品并保存两份当前文件元数据。
     * 调用/引用位置：AdminWallpaperServiceImpl.createWallpaper。
     *
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @param original 本次已校验且上传完成的原文件元数据
     * @param cover 本次封面文件元数据或是否按封面限制校验的标志
     * @return 上述处理得到的Wallpaper结果；业务校验失败时通过异常返回错误
     */
    @Transactional
    public Wallpaper create(WallpaperMutationDTO dto, UploadedFile original, UploadedFile cover) {
        checkAdministrator();
        // 【并发复查：分类第二次检查】上传前检查只能反映当时状态。
        // 此处FOR UPDATE读取最新分类并持锁至提交，防止检查后分类又被停用/删除。
        Category category = checkCategory(dto.getCategoryId(), null, true);
        Wallpaper wallpaper = new Wallpaper();
        wallpaper.setTitle(dto.getTitle());
        wallpaper.setDescription(dto.getDescription() == null ? "" : dto.getDescription());
        wallpaper.setCategoryId(category.getId());
        wallpaper.setCategoryName(category.getName());
        wallpaper.setOriginal(original);
        wallpaper.setCover(cover);
        wallpaper.setType(mediaType(original));
        wallpaper.setPriceCents(dto.getPriceCents());
        wallpaper.setCurrency("ZLC");
        wallpaper.setCreator(dto.getCreator() == null ? "" : dto.getCreator());
        wallpaper.setPublicationStatus("DRAFT");
        wallpaper.setCreatedBy(BaseContext.getCurrentId());
        wallpaper.setCreatedAt(now());
        wallpaper.setUpdatedAt(wallpaper.getCreatedAt());
        // 两份新文件由上传组件检查并生成元数据，创建时不再重复检查整个文件对象。
        if (mapper.insert(wallpaper) != 1 || wallpaper.getId() == null) { throw new IllegalStateException("商品写入未完成"); }
        return wallpaper;
    }

    /**
     * 在短事务中锁定商品并校验并发文件快照，更新允许字段及被替换的文件列。
     * 调用/引用位置：AdminWallpaperServiceImpl.updateWallpaper。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @param original 本次已校验且上传完成的原文件元数据
     * @param cover 本次封面文件元数据或是否按封面限制校验的标志
     * @param snapshot 文件上传开始前读取的商品快照，用于检测并发替换
     * @return 修改后的商品和需要在事务提交后清理的旧文件列表
     */
    @Transactional
    public WallpaperSaveResult update(long id, WallpaperMutationDTO dto, UploadedFile original, UploadedFile cover, Wallpaper snapshot) {
        checkAdministrator();
        // 【并发控制：商品行锁】读取最新值，只修改本次提交字段，避免覆盖其他请求的资料。
        Wallpaper current = mapper.selectByIdForUpdate(id);
        if (current == null) { throw missing(); }
        // 【并发复查：商品快照】上传期间不锁商品；写库时发现同一份文件已被其他请求更换，拒绝覆盖并让编排层清理本次上传。
        if ((original != null && !sameFile(snapshot.getOriginal(), current.getOriginal()))
                || (cover != null && !sameFile(snapshot.getCover(), current.getCover()))) {
            throw new BusinessException(HttpStatus.CONFLICT, "PRODUCT_CHANGED", "商品文件已被其他请求修改，请刷新后重试");
        }
        if (dto.getCategoryId() != null) {
            // 【并发复查：分类第二次检查】以拿到商品锁后的原分类为保留依据。
            Category category = checkCategory(dto.getCategoryId(), current.getCategoryId(), true);
            current.setCategoryId(category.getId());
        }
        if (dto.getTitle() != null) { current.setTitle(dto.getTitle()); }
        if (dto.getDescription() != null) { current.setDescription(dto.getDescription()); }
        if (dto.getCreator() != null) { current.setCreator(dto.getCreator()); }
        if (dto.getPriceCents() != null) { current.setPriceCents(dto.getPriceCents()); }
        WallpaperSaveResult result = new WallpaperSaveResult();
        if (original != null) {
            if (current.getOriginal() != null) { result.getReplacedFiles().add(current.getOriginal()); }
            current.setOriginal(original); current.setType(mediaType(original));
        }
        if (cover != null) {
            if (current.getCover() != null) { result.getReplacedFiles().add(current.getCover()); }
            current.setCover(cover);
        }
        // 保存资料不更改发布状态；正在上架的商品必须继续满足文件完整性。
        if ("PUBLISHED".equals(current.getPublicationStatus())) { checkReady(current); }
        current.setUpdatedAt(now());
        if (mapper.update(current, original != null, cover != null) != 1) { throw missing(); }
        result.setWallpaper(mapper.selectById(id));
        return result;
    }

    /**
     * 取得商品行锁并校验权限、状态和文件就绪信息，再修改发布状态。
     * 调用/引用位置：AdminWallpaperServiceImpl.setPublication。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @param status 目标业务状态或预期HTTP状态，取值由当前方法校验
     * @return 上述处理得到的Wallpaper结果；业务校验失败时通过异常返回错误
     */
    @Transactional
    public Wallpaper publish(long id, String status) {
        checkAdministrator();
        if (!"PUBLISHED".equals(status) && !"WITHDRAWN".equals(status)) { throw invalid("发布状态只能为PUBLISHED或WITHDRAWN"); }
        Wallpaper wallpaper = mapper.selectByIdForUpdate(id);
        if (wallpaper == null) { throw missing(); }
        if ("PUBLISHED".equals(status)) { checkReady(wallpaper); }
        if (!Objects.equals(status, wallpaper.getPublicationStatus())) {
            LocalDateTime time = now();
            LocalDateTime publishedAt = "PUBLISHED".equals(status) ? time : wallpaper.getPublishedAt();
            mapper.updatePublication(id, status, publishedAt, time);
        }
        return mapper.selectById(id);
    }

    /**
     * 在商品短事务中重新读取当前账号的管理员权限，防止长上传期间角色变化。
     * 调用/引用位置：WallpaperPersistenceService.create、WallpaperPersistenceService.publish、WallpaperPersistenceService.update。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    private void checkAdministrator() {
        Long id = BaseContext.getCurrentId();
        User user = id == null ? null : userMapper.selectById(id);
        // 【并发复查：管理员权限】过滤器确认的是请求开始时的身份。
        // 大文件上传期间账号可能被停用或降级，写库前重新读取，拒绝已经失去权限的操作。
        // 这次读取不锁用户行，不表示它能阻止此后所有角色变化。
        if (user == null || !"ACTIVE".equals(user.getStatus()) || !"ADMIN".equals(user.getRole())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "当前账号没有管理员权限");
        }
    }

    /**
     * 读取当前UTC时间并与数据库毫秒时间精度保持一致。
     * 调用/引用位置：WallpaperPersistenceService.create、WallpaperPersistenceService.publish、WallpaperPersistenceService.update。
     * 输入参数：无。
     *
     * @return 当前UTC时间，精度与数据库的毫秒字段一致
     */
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC); }


    /**
     * 上传前第一次检查分类，尽早返回不存在/停用错误，不在上传期间持有分类锁。
     * 调用位置：AdminWallpaperServiceImpl.createWallpaper、updateWallpaper。
     * @param categoryId 表单中的分类字符串ID
     * @param retainedId 修改时的原分类ID；创建传null
     */
    public void checkCategoryBeforeUpload(String categoryId, Long retainedId) {
        checkCategory(categoryId, retainedId, false);
    }

    /**
     * 核对分类存在及启用规则，可保留原停用分类；必要时取得行锁。
     * 调用/引用位置：WallpaperPersistenceService.checkCategoryBeforeUpload、create、update。
     *
     * @param externalId 前端传入的字符串ID，需要安全映射到数据库编号
     * @param retainedId 编辑时允许保留的原分类ID
     * @param lock 是否在当前数据库事务中执行FOR UPDATE读取
     * @return 上述处理得到的Category结果；业务校验失败时通过异常返回错误
     */
    private Category checkCategory(String externalId, Long retainedId, boolean lock) {
        Long id = CatalogIds.findDatabaseId(externalId);
        Category category = id == null ? null : (lock ? categoryMapper.selectByIdForUpdate(id) : categoryMapper.selectById(id));
        if (category == null) { throw new BusinessException(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "分类不存在"); }
        // 停用分类可以继续保留；新增商品或迁移到另一个分类必须使用启用分类。
        if (!category.getEnabled() && !Objects.equals(id, retainedId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "CATEGORY_DISABLED", "新增商品或更换分类只能使用启用分类");
        }
        return category;
    }

    /**
     * 核对两份文件的可信元数据完整且媒体类型一致，未就绪商品禁止上架。
     * 调用/引用位置：WallpaperPersistenceService.publish、update。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param wallpaper 当前商品实体，包含资料与当前文件元数据
     */
    private void checkReady(Wallpaper wallpaper) {
        if (!ready(wallpaper.getOriginal(), false) || !ready(wallpaper.getCover(), true)
                || !mediaType(wallpaper.getOriginal()).equals(wallpaper.getType())) {
            throw new BusinessException(HttpStatus.CONFLICT, "PRODUCT_NOT_READY", "商品原文件或封面信息不完整，不能上架");
        }
    }

    /**
     * 把当前原文件扩展名映射为IMAGE、GIF或VIDEO商品类型。
     * 调用/引用位置：WallpaperPersistenceService.create、WallpaperPersistenceService.update、WallpaperPersistenceService.checkReady。
     *
     * @param file 服务器临时文件路径、上传对象或可信当前文件元数据
     * @return 上述处理得到的String结果；业务校验失败时通过异常返回错误
     */
    private String mediaType(UploadedFile file) {
        if ("gif".equals(file.getFileExtension())) { return "GIF"; }
        return Set.of("mp4", "webm", "mov", "avi").contains(file.getFileExtension()) ? "VIDEO" : "IMAGE";
    }

    /**
     * 比较两个文件的存储提供者、Bucket、对象键和存储版本是否指向同一对象。
     * 调用/引用位置：WallpaperPersistenceService.update。
     *
     * @param first 比较的第一个当前文件元数据
     * @param second 比较的第二个当前文件元数据
     * @return 判断结果；true表示上述条件成立，false表示不成立
     */
    private boolean sameFile(UploadedFile first, UploadedFile second) {
        if (first == null || second == null) { return first == second; }
        return Objects.equals(first.getStorageProvider(), second.getStorageProvider())
                && Objects.equals(first.getBucket(), second.getBucket()) && Objects.equals(first.getObjectKey(), second.getObjectKey())
                && Objects.equals(first.getObjectVersionId(), second.getObjectVersionId());
    }

    /**
     * 检查文件状态、大小、哈希、尺寸、用途和存储位置是否满足发布要求。
     * 调用/引用位置：WallpaperPersistenceService.checkReady。
     *
     * @param file 服务器临时文件路径、上传对象或可信当前文件元数据
     * @param cover 本次封面文件元数据或是否按封面限制校验的标志
     * @return 判断结果；true表示上述条件成立，false表示不成立
     */
    private boolean ready(UploadedFile file, boolean cover) {
        if (file == null || !"READY".equals(file.getStatus()) || blank(file.getObjectKey()) || blank(file.getMimeType())
                || blank(file.getStorageProvider()) || blank(file.getOriginalFilename()) || blank(file.getFileExtension())
                || file.getSizeBytes() == null || file.getSizeBytes() <= 0 || file.getSizeBytes() > (cover ? 4194304L : 10737418240L)
                || file.getSha256() == null || !file.getSha256().matches("[a-f0-9]{64}")
                || file.getWidth() == null || file.getHeight() == null || file.getWidth() <= 0 || file.getHeight() <= 0
                || file.getFps() == null || file.getFps().signum() < 0 || file.getUploadedBy() == null || file.getCreatedAt() == null) { return false; }
        if (("OSS".equals(file.getStorageProvider()) || "COS".equals(file.getStorageProvider())) && blank(file.getBucket())) { return false; }
        return cover ? Set.of("jpg", "jpeg", "png", "bmp", "webp", "gif").contains(file.getFileExtension())
                && Set.of("image/jpeg", "image/png", "image/bmp", "image/webp", "image/gif").contains(file.getMimeType())
                : Set.of("jpg", "jpeg", "png", "bmp", "webp", "gif", "mp4", "webm", "mov", "avi").contains(file.getFileExtension());
    }

    /**
     * 判断字符串是否为null或仅包含空白。
     * 调用/引用位置：WallpaperPersistenceService.ready。
     *
     * @param text 需要整理、计数或比对的字符串
     * @return 判断结果；true表示上述条件成立，false表示不成立
     */
    private boolean blank(String text) { return text == null || text.isBlank(); }

    /**
     * 构造当前商品或封面不存在的404业务异常。
     * 调用/引用位置：WallpaperPersistenceService.publish、update。
     * 输入参数：无。
     *
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "商品不存在"); }

    /**
     * 构造当前模块输入或媒体校验失败的业务异常，供调用方抛出。
     * 调用/引用位置：WallpaperPersistenceService.publish。
     *
     * @param message 允许向前端展示的业务说明
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException invalid(String message) { return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
}

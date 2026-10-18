package com.zlo.wallpaper.catalog.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zlo.wallpaper.catalog.dto.AdminWallpaperQueryDTO;
import com.zlo.wallpaper.catalog.dto.WallpaperMutationDTO;
import com.zlo.wallpaper.catalog.entity.Wallpaper;
import com.zlo.wallpaper.catalog.mapper.AdminWallpaperMapper;
import com.zlo.wallpaper.catalog.model.UploadedFile;
import com.zlo.wallpaper.catalog.model.WallpaperSaveResult;
import com.zlo.wallpaper.catalog.service.*;
import com.zlo.wallpaper.catalog.vo.AdminWallpaperVO;
import com.zlo.wallpaper.common.vo.PageVO;
import com.zlo.wallpaper.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.ArrayList;
import java.util.List;

/**
 * 集中展示商品创建、修改的完整流程：资料检查、上传、事务保存、清理和返回。
 * 阅读入口：先看createWallpaper/updateWallpaper，再看WallpaperPersistenceService的对应事务方法。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：AdminWallpaperController.service、AdminWallpaperIntegrationTest.adminService。
 */
@Service
public class AdminWallpaperServiceImpl implements AdminWallpaperService {
    @Autowired
    private AdminWallpaperMapper mapper;
    @Autowired
    private FileUploadService uploader;
    @Autowired
    private ProductFileCleanupService cleanup;
    @Autowired
    private WallpaperPersistenceService persistence;
    @Value("${wallpaper.public-base-url}") private String publicBaseUrl;

    /**
     * 查询管理员商品分页，包含草稿、已上架和下架商品。
     * 调用/引用位置：AdminWallpaperController.getWallpapers。
     *
     * @param query 分页及筛选请求参数，包含页码、条数或允许的筛选项
     * @return 包含全部商品状态的管理分页items、page、pageSize、total
     */
    @Override
    public PageVO<AdminWallpaperVO> getWallpapers(AdminWallpaperQueryDTO query) {
        Page<Wallpaper> page = new Page<>(query.getPage(), query.getPageSize());
        page.setOptimizeJoinOfCountSql(false);
        IPage<Wallpaper> result = mapper.selectPage(page);
        List<AdminWallpaperVO> items = new ArrayList<>();
        for (Wallpaper wallpaper : result.getRecords()) { items.add(toVO(wallpaper)); }
        return new PageVO<>(items, query.getPage(), query.getPageSize(), result.getTotal());
    }

    /**
     * 创建草稿商品；完整顺序在本方法中展示，文件上传不参加数据库事务。
     * 调用位置：AdminWallpaperController.createWallpaper。
     * @param dto 本次表单资料和必填的原文件、封面
     * @return 已保存的管理端商品资料，ID为字符串且不包含OSS位置
     */
    @Override
    public AdminWallpaperVO createWallpaper(WallpaperMutationDTO dto) {
        // 1. 一次检查普通资料；分类先预检查，避免无效分类也上传大文件。
        validateMutation(dto, true);
        persistence.checkCategoryBeforeUpload(dto.getCategoryId(), null);

        UploadedFile original = null;
        UploadedFile cover = null;
        Wallpaper saved;
        try {
            // 2. 先上传小封面，再上传原文件；文件校验统一由上传组件负责。
            cover = uploader.uploadFile(dto.getCoverFile(), "COVER");
            cleanup.trackUpload(cover);
            original = uploader.uploadFile(dto.getOriginalFile(), "ORIGINAL");
            cleanup.trackUpload(original);

            // 3. 【并发复查】上传期间分类/权限可能变化，事务类重新查询后再写入。
            // 调用另一个Spring Bean才能经过@Transactional代理；返回时事务已经提交。
            saved = persistence.create(dto, original, cover);
        } catch (RuntimeException exception) {
            // OSS不会随数据库事务回滚，所以显式回收本次新文件。
            cleanup.rollbackUpload(original);
            cleanup.rollbackUpload(cover);
            throw exception;
        }

        // 4. 商品已提交，新文件从待清理任务中移除；最后组织返回数据。
        cleanup.completeUpload(original);
        cleanup.completeUpload(cover);
        return toVO(saved);
    }

    /**
     * 修改商品资料及可选文件；与创建分开编排，不再通过save方法二次分流。
     * 调用位置：AdminWallpaperController.updateWallpaper。
     * @param id 前端传入的商品字符串ID
     * @param dto 本次需要修改的字段，省略字段保持原值
     * @return 保存后的管理端商品资料
     */
    @Override
    public AdminWallpaperVO updateWallpaper(String id, WallpaperMutationDTO dto) {
        // 1. 检查资料并读取上传前快照；快照只用来检测文件替换冲突。
        validateMutation(dto, false);
        Wallpaper snapshot = mapper.selectById(databaseId(id));
        if (snapshot == null) {
            throw missing();
        }
        if (dto.getCategoryId() != null) {
            persistence.checkCategoryBeforeUpload(dto.getCategoryId(), snapshot.getCategoryId());
        }

        UploadedFile original = null;
        UploadedFile cover = null;
        WallpaperSaveResult result;
        try {
            // 2. 只上传管理员本次重新选择的文件；纯资料修改不访问OSS。
            if (dto.getCoverFile() != null) {
                cover = uploader.uploadFile(dto.getCoverFile(), "COVER");
                cleanup.trackUpload(cover);
            }
            if (dto.getOriginalFile() != null) {
                original = uploader.uploadFile(dto.getOriginalFile(), "ORIGINAL");
                cleanup.trackUpload(original);
            }

            // 3. 【并发复查】短事务锁定最新商品，比较文件快照并再次检查所选分类。
            result = persistence.update(snapshot.getId(), dto, original, cover, snapshot);
        } catch (RuntimeException exception) {
            cleanup.rollbackUpload(original);
            cleanup.rollbackUpload(cover);
            throw exception;
        }

        // 4. 事务提交后才清理旧文件，避免数据库回滚后商品指向已删除的对象。
        cleanup.completeUpload(original);
        cleanup.completeUpload(cover);
        for (UploadedFile old : result.getReplacedFiles()) {
            cleanup.retireFile(old);
        }
        return toVO(result.getWallpaper());
    }

    /**
     * 校验目标状态及商品文件就绪信息，再执行上架或下架。
     * 调用/引用位置：AdminWallpaperController.setPublication。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @param status 目标业务状态或预期HTTP状态，取值由当前方法校验
     * @return 管理商品资料、当前媒体元数据、文件显示名及受保护封面URL
     */
    @Override
    public AdminWallpaperVO setPublication(String id, String status) {
        return toVO(persistence.publish(databaseId(id), status));
    }

    /**
     * 将查询结果或数据库实体逐项转换成前端公开字段，不返回内部存储位置。
     * 调用/引用位置：AdminWallpaperServiceImpl.getWallpapers、AdminWallpaperServiceImpl.createWallpaper、AdminWallpaperServiceImpl.updateWallpaper、AdminWallpaperServiceImpl.setPublication。
     *
     * @param wallpaper 当前商品实体，包含资料与当前文件元数据
     * @return 管理商品资料、当前媒体元数据、文件显示名及受保护封面URL
     */
    private AdminWallpaperVO toVO(Wallpaper wallpaper) {
        AdminWallpaperVO vo = new AdminWallpaperVO();
        vo.setId(wallpaper.getId().toString());
        vo.setTitle(wallpaper.getTitle());
        vo.setDescription(wallpaper.getDescription());
        vo.setCategoryId(wallpaper.getCategoryId().toString());
        vo.setCategoryName(wallpaper.getCategoryName());
        vo.setType(wallpaper.getType());
        vo.setPriceCents(wallpaper.getPriceCents());
        vo.setCurrency(wallpaper.getCurrency());
        vo.setCreator(wallpaper.getCreator());
        vo.setPublicationStatus(wallpaper.getPublicationStatus());
        UploadedFile original = wallpaper.getOriginal();
        UploadedFile cover = wallpaper.getCover();
        vo.setOriginalFilename(original == null ? "" : original.getOriginalFilename());
        vo.setCoverFilename(cover == null ? "" : cover.getOriginalFilename());
        vo.setCoverUrl(cover == null ? "" : UriComponentsBuilder.fromUriString(publicBaseUrl)
                .pathSegment("admin", "wallpapers", wallpaper.getId().toString(), "cover").build().toUriString());
        if (original != null) {
            vo.setWidth(original.getWidth());
            vo.setHeight(original.getHeight());
            vo.setFps(original.getFps());
            vo.setSha256(original.getSha256());
            vo.setSizeBytes(original.getSizeBytes() == null ? null : original.getSizeBytes().toString());
        }
        return vo;
    }


    /**
     * 校验新增或修改商品的实际输入，整理字符串并限制允许字段与大小。
     * 调用/引用位置：AdminWallpaperServiceImpl.createWallpaper、AdminWallpaperServiceImpl.updateWallpaper。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @param create true表示创建请求，false表示部分修改请求
     */
    private void validateMutation(WallpaperMutationDTO dto, boolean create) {
        if (create && (dto.getTitle() == null || dto.getCategoryId() == null || dto.getPriceCents() == null
                || dto.getOriginalFile() == null || dto.getCoverFile() == null)) {
            throw invalid("创建商品需要标题、分类、价格、原文件和封面");
        }
        if (!create && dto.getTitle() == null && dto.getDescription() == null && dto.getCategoryId() == null
                && dto.getPriceCents() == null && dto.getCreator() == null && dto.getOriginalFile() == null && dto.getCoverFile() == null) {
            throw invalid("请提交需要修改的字段");
        }
        if (dto.getTitle() != null) {
            dto.setTitle(dto.getTitle().strip());
            if (dto.getTitle().isEmpty() || length(dto.getTitle()) > 120) { throw invalid("标题须为1–120个Unicode字符"); }
        }
        if (dto.getDescription() != null && length(dto.getDescription()) > 4000) { throw invalid("描述最多4000个Unicode字符"); }
        if (dto.getCreator() != null) {
            dto.setCreator(dto.getCreator().strip());
            if (length(dto.getCreator()) > 120) { throw invalid("作者最多120个Unicode字符"); }
        }
        if (dto.getPriceCents() != null && (dto.getPriceCents() < 0 || dto.getPriceCents() > 100000000)) {
            throw invalid("价格须为0–100000000整数分");
        }
        if (dto.getCategoryId() != null) {
            dto.setCategoryId(dto.getCategoryId().strip());
            if (dto.getCategoryId().isEmpty()) { throw invalid("分类ID不能为空"); }
            CatalogIds.validatePathId(dto.getCategoryId());
        }
    }

    /**
     * 验证外部商品ID并转换为本机编号，无法映射时返回商品不存在。
     * 调用/引用位置：AdminWallpaperServiceImpl.setPublication、AdminWallpaperServiceImpl.updateWallpaper。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 上述处理得到的long结果；业务校验失败时通过异常返回错误
     */
    private long databaseId(String id) {
        CatalogIds.validatePathId(id);
        Long databaseId = CatalogIds.findDatabaseId(id);
        if (databaseId == null) { throw missing(); }
        return databaseId;
    }

    /**
     * 按Unicode码点统计字符数，避免把一个补充字符计成两个。
     * 调用/引用位置：AdminWallpaperServiceImpl.validateMutation。
     *
     * @param text 需要整理、计数或比对的字符串
     * @return 上述处理得到的int结果；业务校验失败时通过异常返回错误
     */
    private int length(String text) { return text.codePointCount(0, text.length()); }

    /**
     * 构造当前商品或封面不存在的404业务异常。
     * 调用/引用位置：AdminWallpaperServiceImpl.updateWallpaper、AdminWallpaperServiceImpl.databaseId。
     * 输入参数：无。
     *
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException missing() { return new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "商品不存在"); }

    /**
     * 构造当前模块输入或媒体校验失败的业务异常，供调用方抛出。
     * 调用/引用位置：AdminWallpaperServiceImpl.validateMutation。
     *
     * @param message 允许向前端展示的业务说明
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException invalid(String message) { return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
}

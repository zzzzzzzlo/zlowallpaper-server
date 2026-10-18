package com.zlo.wallpaper.catalog.controller;

import com.zlo.wallpaper.catalog.dto.AdminWallpaperQueryDTO;
import com.zlo.wallpaper.catalog.dto.WallpaperMutationDTO;
import com.zlo.wallpaper.catalog.dto.WallpaperPublicationDTO;
import com.zlo.wallpaper.catalog.service.AdminWallpaperService;
import com.zlo.wallpaper.catalog.service.CoverService;
import com.zlo.wallpaper.catalog.vo.AdminWallpaperVO;
import com.zlo.wallpaper.common.ApiResponse;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.common.RequestIdFilter;
import com.zlo.wallpaper.common.vo.PageVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.validation.BindingResult;
import org.springframework.validation.BindException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.ExtendedServletRequestDataBinder;
import java.beans.PropertyEditorSupport;
import java.util.List;
import java.util.Set;

/**
 * 提供管理员商品分页、创建、修改、上下架和受保护封面预览入口。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 */
@RestController
@RequestMapping("/admin/wallpapers")
public class AdminWallpaperController {
    @Autowired
    private AdminWallpaperService service;
    @Autowired
    private CoverService coverService;



    // 草稿封面需要管理员凭证，不能借公开封面接口绕过商品可见性。
    /**
     * 读取管理员可见的商品封面并返回图片字节及禁止缓存的响应头。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 包含实际HTTP状态、响应头及上述业务结果的HTTP响应
     */
    @GetMapping("/{id}/cover")
    public ResponseEntity<Resource> getCover(@PathVariable String id) {
        CoverService.Cover cover = coverService.readAdmin(id);
        return ResponseEntity.ok().contentType(cover.mediaType()).contentLength(cover.size())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore()).body(cover.resource());
    }

    /**
     * 统一配置绑定来源、允许的商品字段和价格转换；不查询数据库或检查文件内容。
     * 框架调用位置：Spring MVC在绑定本Controller请求前回调。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param binder Spring当前请求的数据绑定器
     */
    @InitBinder
    public void configureBinding(WebDataBinder binder) {
        // Spring在请求绑定阶段调用本方法，接口方法不需要手动调用。
        if (binder instanceof ExtendedServletRequestDataBinder servletBinder) {
            servletBinder.setHeaderPredicate(name -> false);
        }
        if (!"mutation".equals(binder.getObjectName())) {
            return;
        }
        binder.setAllowedFields("title", "description", "categoryId", "priceCents", "creator", "originalFile", "coverFile");
        binder.registerCustomEditor(Integer.class, "priceCents", new PropertyEditorSupport() /**
         * 表单价格的匿名属性编辑器；在AdminWallpaperController.configureBinding中new并注册给WebDataBinder。
         * 使用位置：当前new表达式所属方法；由Spring数据绑定器调用其重写方法。
         */
        {
            /**
             * 转换表单价格；WebDataBinder绑定priceCents时调用。
             * @param value 表单中的整数分文本
             */
            @Override
            public void setAsText(String value) {
                // 表单都是文本，但金额必须是整数分，拒绝空值、小数、十六进制和科学计数法。
                if (value == null || !value.matches("[0-9]+")) {
                    throw new IllegalArgumentException("金额必须为整数分");
                }
                setValue(Integer.valueOf(value));
            }
        });
    }

    /**
     * 查询管理员商品分页，包含草稿、已上架和下架商品。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param query 分页及筛选请求参数，包含页码、条数或允许的筛选项
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包装上述业务结果及本次requestId的统一JSON响应
     */
    @GetMapping
    public ApiResponse<PageVO<AdminWallpaperVO>> getWallpapers(@Valid @ModelAttribute AdminWallpaperQueryDTO query,
                                                               HttpServletRequest request) {
        for (String name : request.getParameterMap().keySet()) {
            if (!Set.of("page", "pageSize").contains(name) || request.getParameterValues(name).length != 1) {
                throw invalid();
            }
        }
        return ApiResponse.success(service.getWallpapers(query), RequestIdFilter.current(request));
    }

    /**
     * 处理资料及两份文件，校验后创建DRAFT商品并返回当前商品资料。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @param binding 表单绑定结果，包含字段转换或绑定错误
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包装上述业务结果及本次requestId的统一JSON响应
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminWallpaperVO> createWallpaper(@ModelAttribute("mutation") WallpaperMutationDTO dto,
                                                          BindingResult binding, MultipartHttpServletRequest request) throws BindException {
        checkForm(binding, request);
        return ApiResponse.success(service.createWallpaper(dto), RequestIdFilter.current(request));
    }

    /**
     * 更新商品资料或替换当前文件，保留省略字段与原发布状态。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @param binding 表单绑定结果，包含字段转换或绑定错误
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包装上述业务结果及本次requestId的统一JSON响应
     */
    @PatchMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AdminWallpaperVO> updateWallpaper(@PathVariable String id,
                                                          @ModelAttribute("mutation") WallpaperMutationDTO dto,
                                                          BindingResult binding, MultipartHttpServletRequest request) throws BindException {
        checkForm(binding, request);
        return ApiResponse.success(service.updateWallpaper(id, dto), RequestIdFilter.current(request));
    }

    /**
     * 校验目标状态及商品文件就绪信息，再执行上架或下架。
     * 框架调用位置：Spring MVC根据方法路由注解处理前端HTTP请求。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     * @return 包装上述业务结果及本次requestId的统一JSON响应
     */
    @PutMapping(value = "/{id}/publication", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<AdminWallpaperVO> setPublication(@PathVariable String id,
@Valid @RequestBody WallpaperPublicationDTO dto, HttpServletRequest request) {
        return ApiResponse.success(service.setPublication(id, dto.getStatus()), RequestIdFilter.current(request));
    }

    /**
     * 检查绑定错误、未知字段、重复参数和重复文件，拒绝不符合表单契约的请求。
     * 调用/引用位置：AdminWallpaperController.createWallpaper、AdminWallpaperController.updateWallpaper。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param binding 表单绑定结果，包含字段转换或绑定错误
     * @param request 当前HTTP请求，用于读取请求属性、表单参数或请求标识
     */
    private void checkForm(BindingResult binding, MultipartHttpServletRequest request) throws BindException {
        if (binding.hasErrors()) { throw new BindException(binding); }
    // 白名单按实际参数和文件检查；路径里的id由@PathVariable处理，不能误判为表单字段。
        for (String name : request.getParameterMap().keySet()) {
            if (!Set.of("title", "description", "categoryId", "priceCents", "creator").contains(name)
                    || request.getParameterValues(name).length != 1) { throw invalid(); }
        }
        for (String name : request.getMultiFileMap().keySet()) {
            if (!Set.of("originalFile", "coverFile").contains(name)) { throw invalid(); }
            List<MultipartFile> files = request.getMultiFileMap().get(name);
            if (files.size() != 1) { throw invalid(); }
        }
    }

    /**
     * 构造未知字段、重复参数或重复文件的400表单校验异常。
     * 调用/引用位置：AdminWallpaperController.checkForm、AdminWallpaperController.getWallpapers。
     * 输入参数：无。
     *
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException invalid() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求包含未定义字段或重复参数");
    }
}

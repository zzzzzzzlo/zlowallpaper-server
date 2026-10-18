package com.zlo.wallpaper.common;

import org.springframework.http.HttpStatus;

/**
 * 携带可向前端展示的HTTP状态、业务错误码和消息，由全局异常处理统一转换。
 * 创建方式：业务层通过new创建，或由MyBatis/Jackson根据查询/数据绑定结果构造；不作为Spring组件注入。
 * 显式实例化位置：AdminWallpaperController.invalid、AuthFilter.doFilterInternal、AuthServiceImpl.expired、AuthServiceImpl.login、AuthServiceImpl.usernameExists、CatalogIds.validatePathId、CategoryServiceImpl.categoryInUse、CategoryServiceImpl.nameExists、CategoryServiceImpl.notFound、CoverService.missing、CoverService.readFile、FileUploadServiceImpl.extension、FileUploadServiceImpl.tooLarge、FileUploadServiceImpl.uploadFile、FileUploadServiceImpl.validation、JwtUtil.expired、MediaInspector.checkSignature、MediaInspector.execute、MediaInspector.inspect、MediaInspector.invalid、OrderServiceImpl.loginRequired、OrderServiceImpl.productMissing、OrderServiceImpl.purchaseConflict、OssStorage.configurationError、OssStorage.missing、OssStorage.translate、OssStorage.unavailable、ProductFileCleanupService.trackUpload、UserServiceImpl.getCurrentUser、WallpaperPersistenceService.checkAdministrator、WallpaperPersistenceService.update、WallpaperPersistenceService.checkCategory、WallpaperPersistenceService.checkReady、AdminWallpaperServiceImpl.invalid、WallpaperPersistenceService.invalid、AdminWallpaperServiceImpl.missing、WallpaperPersistenceService.missing、WallpaperServiceImpl.detail。
 */
public class BusinessException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    /**
     * 构造BusinessException并保存其依赖或初始属性。
     * 调用/引用位置：AdminWallpaperController.invalid、AdminWallpaperIntegrationTest.databaseFailureRollsBackProductAndRetainsFailedDeletesForRetry、AdminWallpaperIntegrationTest.originalUploadFailureReclaimsAlreadyUploadedCoverAndDoesNotInsert、AdminWallpaperIntegrationTest.upload、AuthFilter.doFilterInternal、AuthServiceImpl.expired、AuthServiceImpl.login、AuthServiceImpl.usernameExists、CatalogIds.validatePathId、CategoryServiceImpl.categoryInUse、CategoryServiceImpl.nameExists、CategoryServiceImpl.notFound、CoverService.missing、CoverService.readFile、FileUploadServiceImpl.extension、FileUploadServiceImpl.tooLarge、FileUploadServiceImpl.uploadFile、FileUploadServiceImpl.validation、FileUploadServiceTest.cloudFailureKeepsErrorAndCleansTemporaryFile、FileUploadServiceTest.invalidMediaNeverUploads、JwtUtil.expired、MediaInspector.checkSignature、MediaInspector.execute、MediaInspector.inspect、MediaInspector.invalid、OrderServiceImpl.loginRequired、OrderServiceImpl.productMissing、OrderServiceImpl.purchaseConflict、OssStorage.configurationError、OssStorage.missing、OssStorage.translate、OssStorage.unavailable、ProductFileCleanupService.trackUpload、UserServiceImpl.getCurrentUser、WallpaperPersistenceService.checkAdministrator、WallpaperPersistenceService.update、WallpaperPersistenceService.checkCategory、WallpaperPersistenceService.checkReady、AdminWallpaperServiceImpl.invalid、WallpaperPersistenceService.invalid、AdminWallpaperServiceImpl.missing、WallpaperPersistenceService.missing、WallpaperServiceImpl.detail。
     * 返回：无返回值（构造器用于初始化本对象）。
     *
     * @param status 目标业务状态或预期HTTP状态，取值由当前方法校验
     * @param code 统一响应使用的业务错误码
     * @param message 允许向前端展示的业务说明
     */
    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    /**
     * 读取业务异常携带的HTTP状态。
     * 调用/引用位置：AuthFilter.doFilterInternal、FileUploadServiceTest.declaredAndActualCoverLimitsAreEnforced、FileUploadServiceTest.missingIdentityNeverUploads、GlobalExceptionHandler.business、MediaToolsIT.coverRejectsVideoBeforeProbeAndAcceptsValidImage、MediaToolsIT.missingBinaryReturns503RatherThanAcceptingUncheckedMedia、OssStorageTest.coverReadEnforcesActualSizeAndReturnsBytes、OssStorageTest.ossAccessDeniedBecomes503InsteadOfFrontend403。
     * 输入参数：无。
     *
     * @return 上述处理得到的HttpStatus结果；业务校验失败时通过异常返回错误
     */
    public HttpStatus getStatus() { return status; }
    /**
     * 读取业务异常携带的错误码。
     * 调用/引用位置：AdminCategoryIntegrationTest.createTogether、AuthFilter.doFilterInternal、AuthIntegrationTest.concurrentRefresh、AuthMySqlIT.refreshTogether、FileUploadServiceTest.cloudFailureKeepsErrorAndCleansTemporaryFile、FileUploadServiceTest.invalidMediaNeverUploads、GlobalExceptionHandler.business、MediaSignatureTest.renamedHtmlIsNeverAcceptedAsMedia、MediaToolsIT.missingBinaryReturns503RatherThanAcceptingUncheckedMedia、MediaToolsIT.signatureOnlyPngAndRenamedImageAreRejected、OssStorageTest.missingConfigurationFailsOnRequestNotOnConstruction、OssStorageTest.multipartFailureAbortsAndPreservesPrimaryErrorEvenIfAbortFails、OssStorageTest.ossAccessDeniedBecomes503InsteadOfFrontend403。
     * 输入参数：无。
     *
     * @return 上述处理得到的String结果；业务校验失败时通过异常返回错误
     */
    public String getCode() { return code; }
}

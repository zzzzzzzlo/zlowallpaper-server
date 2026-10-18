package com.zlo.wallpaper.common;

/**
 * 用ThreadLocal保存当前请求线程的可信用户ID，结束后必须清理以防线程复用串号。
 * 创建方式：静态工具类不实例化；私有构造器防止业务代码new。
 */
public final class BaseContext {
    private static final ThreadLocal<Long> CURRENT_USER = new ThreadLocal<>();
    /**
     * 限制外部实例化，当前类只通过静态方法使用。
     * 调用位置：没有业务调用，此构造器用于禁止new。
     * 输入参数：无。
     * 返回：无返回值（构造器用于初始化本对象）。
     *
     */
    private BaseContext() { }
    /**
     * 把认证得到的用户ID保存到当前请求线程。
     * 调用/引用位置：AuthFilter.doFilterInternal、FileUploadServiceTest.owner、OrderIntegrationTest.concurrent、OrderMySqlIT.create。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     */
    public static void setCurrentId(Long id) { CURRENT_USER.set(id); }
    /**
     * 读取当前请求线程的可信用户ID，没有设置时返回null。
     * 调用/引用位置：AdminCategoryIntegrationTest.adminListIncludesDisabledSortedCategoriesAndCountsEveryProductState、AdminWallpaperIntegrationTest.createWritesBothFilesInProductAndReturnsDraftWithoutCloudLocations、AdminWallpaperIntegrationTest.upload、AuthIntegrationTest.loginAndMeMatchContractAndDoNotLeakInternalFields、AuthIntegrationTest.missingAuthAndCorsAndContextCleanupAreCorrect、FileUploadServiceImpl.uploadFile、OrderIntegrationTest.freeOrderCreatesPaidSnapshotAndEntitlementInOneRequest、OrderServiceImpl.createOrder、UserServiceImpl.getCurrentUser、WallpaperPersistenceService.checkAdministrator、WallpaperPersistenceService.create。
     * 输入参数：无。
     *
     * @return 查到的当前对象或编号；不存在时为null
     */
    public static Long getCurrentId() { return CURRENT_USER.get(); }
    /**
     * 移除当前线程用户ID，避免Web线程复用时串到下一次请求。
     * 调用/引用位置：AuthFilter.doFilterInternal、AuthIntegrationTest.clearDatabase、FileUploadServiceTest.clear、FileUploadServiceTest.missingIdentityNeverUploads、OrderIntegrationTest.concurrent、OrderMySqlIT.cleanupOnlyThisTestsFixtures、OrderMySqlIT.create。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    public static void removeCurrentId() { CURRENT_USER.remove(); }
}

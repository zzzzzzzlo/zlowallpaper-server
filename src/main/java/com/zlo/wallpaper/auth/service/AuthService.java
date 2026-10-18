package com.zlo.wallpaper.auth.service;

import com.zlo.wallpaper.auth.dto.LoginDTO;
import com.zlo.wallpaper.auth.dto.RegisterDTO;
import com.zlo.wallpaper.auth.entity.User;
import com.zlo.wallpaper.auth.vo.RegisterVO;
import com.zlo.wallpaper.auth.vo.SessionVO;

/**
 * 声明注册、登录、刷新、登出与令牌认证能力，Controller和AuthFilter只依赖此接口。
 * 创建方式：接口本身不实例化，由对应@Service实现提供注入对象。
 * 注入位置：AdminCategoryIntegrationTest.authService、AdminWallpaperIntegrationTest.auth、AuthController.authService、AuthFilter.authService、AuthIntegrationTest.authService、AuthMySqlIT.authService、OrderIntegrationTest.authService。
 */
public interface AuthService {
    /**
     * 校验注册资料、保存安全密码哈希并返回注册结果。
     * 调用/引用位置：AdminCategoryIntegrationTest.registerAndLogin、AdminWallpaperIntegrationTest.account、AuthController.register、AuthMySqlIT.registerOwnAccount、OrderIntegrationTest.login。
     *
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @return 注册成功后的用户名，不包含登录令牌
     */
    RegisterVO register(RegisterDTO dto);
    /**
     * 校验账号密码和当前用户状态，创建会话并签发登录结果。
     * 调用/引用位置：AdminCategoryIntegrationTest.registerAndLogin、AdminWallpaperIntegrationTest.account、AuthController.login、AuthMySqlIT.login、OrderIntegrationTest.login。
     *
     * @param dto 本次请求绑定并经过校验的DTO资料
     * @return 新accessToken、refreshToken、实际有效秒数expiresIn及公开UserVO
     */
    SessionVO login(LoginDTO dto);
    /**
     * 核对刷新令牌、轮换会话并返回新令牌；发现重放时撤销对应登录组。
     * 调用/引用位置：AuthController.refresh、AuthIntegrationTest.concurrentRefresh、AuthMySqlIT.refreshTogether、AuthMySqlIT.roundTripAndReplayCommitOnMySql。
     *
     * @param refreshToken 客户端持有的明文刷新令牌，用于轮换或撤销其所属登录组，不记录到日志
     * @return 新accessToken、refreshToken、实际有效秒数expiresIn及公开UserVO
     */
    SessionVO refresh(String refreshToken);
    /**
     * 撤销刷新令牌所属登录组，使该组访问令牌也不能继续使用。
     * 调用/引用位置：AuthController.logout、AuthMySqlIT.logoutRacingRefreshCannotLeaveActiveSessionOnMySql、AuthMySqlIT.roundTripAndReplayCommitOnMySql。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param refreshToken 客户端持有的明文刷新令牌，用于轮换或撤销其所属登录组，不记录到日志
     */
    void logout(String refreshToken);
    /**
     * 验证访问JWT并核对数据库会话、用户归属和当前账号状态。
     * 调用/引用位置：AuthFilter.doFilterInternal、AuthMySqlIT.roundTripAndReplayCommitOnMySql。
     *
     * @param accessToken Authorization携带的访问JWT，需要验证签名并核对会话和用户
     * @return 上述处理得到的User结果；业务校验失败时通过异常返回错误
     */
    User authenticate(String accessToken);
}

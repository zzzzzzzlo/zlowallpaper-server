package com.zlo.wallpaper.auth.service.impl;

import com.zlo.wallpaper.auth.entity.User;
import com.zlo.wallpaper.auth.dto.ChangePasswordDTO;
import com.zlo.wallpaper.auth.mapper.SessionMapper;
import com.zlo.wallpaper.auth.mapper.UserMapper;
import com.zlo.wallpaper.auth.service.UserService;
import com.zlo.wallpaper.auth.vo.UserVO;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 根据可信用户ID查询资料，或校验旧密码并修改当前账号密码。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：UserController.userService。
 */
@Service
public class UserServiceImpl implements UserService {
    @Autowired
    private UserMapper userMapper;
    @Autowired private SessionMapper sessionMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private Clock clock;

    /**
     * 读取当前登录账号资料并转换为公开用户信息。
     * 调用/引用位置：UserController.getCurrentUser。
     * 输入参数：无。
     *
     * @return 不含密码和内部状态的用户ID、用户名及壁纸币余额
     */
    @Override
    public UserVO getCurrentUser() {
        Long userId = BaseContext.getCurrentId();
        User user = userId == null ? null : userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "登录已失效，请重新登录");
        }
        return new UserVO(user.getId().toString(), user.getUsername(), user.getBalanceCents().toString());
    }

    /** 密码更新和会话撤销一起提交；任何一步失败都回滚，不保留半完成结果。 */
    @Override
    @Transactional
    public void changePassword(ChangePasswordDTO dto) {
        Long userId = BaseContext.getCurrentId();
        // 并发复查：过滤器认证后账号状态可能变化；锁用户行后再读最新状态。
        // 登录创建会话也先锁该用户行，保证旧密码登录不能绕过本次全部撤销。
        User user = userId == null ? null : userMapper.selectByIdForUpdate(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "登录已失效，请重新登录");
        }
        if (!passwordEncoder.matches(dto.getCurrentPassword(), user.getPasswordHash())) {
            // 旧密码填错属于本次业务失败，不使用401，避免客户端误清除有效登录。
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INCORRECT", "当前密码不正确");
        }
        if (dto.getCurrentPassword().equals(dto.getNewPassword())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED", "新密码不能与当前密码相同");
        }
        String passwordHash = passwordEncoder.encode(dto.getNewPassword());
        LocalDateTime changedAt = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
        userMapper.updatePassword(userId, passwordHash, changedAt);
        sessionMapper.revokeByUserId(userId, changedAt);
    }
}

package com.zlo.wallpaper.auth.service.impl;

import com.zlo.wallpaper.auth.entity.User;
import com.zlo.wallpaper.auth.mapper.UserMapper;
import com.zlo.wallpaper.auth.service.UserService;
import com.zlo.wallpaper.auth.vo.UserVO;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 根据当前线程的可信用户ID查询用户，并转换成不含密码的UserVO。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：UserController.userService。
 */
@Service
public class UserServiceImpl implements UserService {
    @Autowired
    private UserMapper userMapper;

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
}

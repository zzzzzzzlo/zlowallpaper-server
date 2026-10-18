package com.zlo.wallpaper.auth.service;

import com.zlo.wallpaper.auth.vo.UserVO;

/**
 * 声明当前登录用户的资料查询能力。
 * 创建方式：接口本身不实例化，由对应@Service实现提供注入对象。
 * 注入位置：UserController.userService。
 */
public interface UserService {
    /**
     * 读取当前登录账号资料并转换为公开用户信息。
     * 调用/引用位置：UserController.getCurrentUser。
     * 输入参数：无。
     *
     * @return 不含密码和内部状态的用户ID、用户名及壁纸币余额
     */
    UserVO getCurrentUser();
}

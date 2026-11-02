package com.zlo.wallpaper.auth.service;

import com.zlo.wallpaper.auth.vo.UserVO;
import com.zlo.wallpaper.auth.dto.ChangePasswordDTO;

/**
 * 声明当前登录用户的资料查询与密码修改能力。
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

    /** 核对当前密码，更新密码哈希并撤销该账号所有现有会话。 */
    void changePassword(ChangePasswordDTO dto);
}

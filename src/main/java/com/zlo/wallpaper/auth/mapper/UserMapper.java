package com.zlo.wallpaper.auth.mapper;

import com.zlo.wallpaper.auth.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;

/**
 * 声明账号查询、注册及余额扣减SQL，供认证、用户信息和订单付款使用。
 * 创建方式：MyBatis扫描@Mapper后生成代理，SQL实现在同模块Mapper XML中。
 * 注入位置：AuthMySqlIT.userMapper、AuthServiceImpl.userMapper、UserServiceImpl.userMapper、WallpaperPersistenceService.userMapper、OrderServiceImpl.userMapper。
 * 数据库映射位置：src/main/resources/mapper/auth/UserMapper.xml。
 */
@Mapper
public interface UserMapper {
    /**
     * 按用户名查询账号，由密码校验和注册判重使用。
     * 调用/引用位置：AuthMySqlIT.registerOwnAccount、AuthServiceImpl.login、AuthServiceImpl.register。
     *
     * @param username 待查询或注册的用户名
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    User selectByUsername(@Param("username") String username);
    /**
     * 按主键读取数据库记录；不存在时返回null。
     * 调用/引用位置：AuthServiceImpl.authenticate、AuthServiceImpl.refresh、UserServiceImpl.getCurrentUser、WallpaperPersistenceService.checkAdministrator。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    User selectById(@Param("id") long id);
    /**
     * 仅查询账号当前壁纸币余额，避免订单确认读取密码哈希等无关资料。
     * 调用位置：OrderServiceImpl.createOrder、payOrder、getMyOrders。
     * @param id 来自登录上下文的可信用户ID
     * @return 余额整数最小单位；用户不存在时为null，100单位等于1壁纸币
     */
    Long selectBalanceById(@Param("id") long id);
    /**
     * 把实体写入对应数据库表，并在有自增主键时回填id。
     * 调用/引用位置：AuthServiceImpl.register。
     *
     * @param user 已查询或校验的用户实体
     * @return 受影响的行数或统计数量；具体SQL见对应Mapper XML
     */
    int insert(User user);

    /**
     * 扣减已锁定账号的壁纸币余额，余额不足时不更新。
     * 调用位置：OrderServiceImpl.payOrder；与订单和权益共用一个事务。
     * @param userId 当前可信用户ID
     * @param amountCents 订单金额，100整数单位=1壁纸币
     * @param paidAt 本次统一的UTC付款时间
     * @return 扣减成功为1，余额不足为0
     */
    int deductBalance(@Param("userId") Long userId, @Param("amountCents") int amountCents,
                      @Param("paidAt") LocalDateTime paidAt);
}

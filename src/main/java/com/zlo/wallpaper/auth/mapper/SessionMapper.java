package com.zlo.wallpaper.auth.mapper;

import com.zlo.wallpaper.auth.entity.Session;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;

/** 登录时插入一条会话，认证和刷新只读，登出原子标记撤销。SQL位于SessionMapper.xml。 */
@Mapper
public interface SessionMapper {
    /** 按JWT的sid核对当前会话；认证时调用。 */
    Session selectById(@Param("id") long id);
    /** 按刷新令牌哈希读取当前登录；不加锁、不修改数据。 */
    Session selectByRefreshHash(@Param("hash") String hash);
    /** 登录成功时插入一条会话，并回填自增ID。 */
    int insert(Session session);
    /** 仅撤销尚未撤销的当前会话，重复调用不覆盖原撤销时间。 */
    int revokeByRefreshHash(@Param("hash") String hash, @Param("now") LocalDateTime now);
    /** 改密成功后撤销该账号所有未撤销会话；与密码更新在同一事务提交。 */
    int revokeByUserId(@Param("userId") long userId, @Param("now") LocalDateTime now);
    /** 定时清理到期且已经超过保留期的会话。 */
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}

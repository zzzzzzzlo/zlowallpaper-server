package com.zlo.wallpaper.auth.mapper;

import com.zlo.wallpaper.auth.entity.Session;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;

/**
 * 声明会话查询、轮换、撤销和清理SQL；配合事务中的锁定读取处理并发刷新。
 * 创建方式：MyBatis扫描@Mapper后生成代理，SQL实现在同模块Mapper XML中。
 * 注入位置：AuthServiceImpl.sessionMapper、SessionCleanupTask.sessionMapper。
 * 数据库映射位置：src/main/resources/mapper/auth/SessionMapper.xml。
 */
@Mapper
public interface SessionMapper {
    /**
     * 按主键读取数据库记录；不存在时返回null。
     * 调用/引用位置：AuthServiceImpl.authenticate。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    Session selectById(@Param("id") long id);
    /**
     * 按刷新令牌哈希定位会话，供后续锁定其登录组。
     * 调用/引用位置：AuthServiceImpl.lockSession。
     *
     * @param hash 刷新令牌的SHA-256哈希，用于匹配sessions.refresh_token_hash
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    Session selectByRefreshHash(@Param("hash") String hash);
    /**
     * 按刷新令牌哈希执行锁定读取，获得事务内最新会话状态。
     * 调用/引用位置：AuthServiceImpl.lockSession。
     *
     * @param hash 刷新令牌的SHA-256哈希，用于匹配sessions.refresh_token_hash
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    Session selectByRefreshHashForUpdate(@Param("hash") String hash);
    /**
     * 锁定登录组最早的一条记录，使同组刷新与登出按顺序执行。
     * 调用/引用位置：AuthServiceImpl.lockSession。
     *
     * @param familyId 一次独立登录的固定组标识
     * @return 匹配条件的数据库记录或状态，没有记录时为null
     */
    Session lockFamily(@Param("familyId") String familyId);
    /**
     * 把实体写入对应数据库表，并在有自增主键时回填id。
     * 调用/引用位置：AuthServiceImpl.createSession。
     *
     * @param session 已查询或新创建的会话实体
     * @return 受影响的行数或统计数量；具体SQL见对应Mapper XML
     */
    int insert(Session session);
    /**
     * 记录旧会话的ROTATED撤销状态与后继会话ID。
     * 调用/引用位置：AuthServiceImpl.refresh。
     *
     * @param id 当前方法要处理的分类、商品、订单或资源ID
     * @param nextId 旧会话轮换后的后继记录ID
     * @param now 本次操作统一使用的UTC时间
     * @return 受影响的行数或统计数量；具体SQL见对应Mapper XML
     */
    int markRotated(@Param("id") long id, @Param("nextId") long nextId, @Param("now") LocalDateTime now);
    /**
     * 按登录组撤销全部会话，处理登出、重放或账号禁用。
     * 调用/引用位置：AuthServiceImpl.logout、AuthServiceImpl.refresh。
     *
     * @param familyId 一次独立登录的固定组标识
     * @param reason 撤销登录组的业务原因
     * @param now 本次操作统一使用的UTC时间
     * @return 受影响的行数或统计数量；具体SQL见对应Mapper XML
     */
    int revokeFamily(@Param("familyId") String familyId, @Param("reason") String reason,
                     @Param("now") LocalDateTime now);
    /**
     * 删除超过整组截止时间及保留期的会话历史。
     * 调用/引用位置：SessionCleanupTask.cleanExpiredSessions。
     *
     * @param cutoff 清理允许删除的最晚UTC截止时间
     * @return 受影响的行数或统计数量；具体SQL见对应Mapper XML
     */
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}

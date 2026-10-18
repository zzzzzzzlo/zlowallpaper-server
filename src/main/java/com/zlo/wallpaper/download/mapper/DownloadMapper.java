package com.zlo.wallpaper.download.mapper;

import com.zlo.wallpaper.download.model.DownloadFileRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 声明下载授权需要的权益与当前原文件联合查询，避免先查权益再查文件产生两个查询时点。
 * 创建方式：MyBatis扫描@Mapper生成代理；SQL位于mapper/download/DownloadMapper.xml。
 * 注入位置：DownloadServiceImpl.downloadMapper。
 */
@Mapper
public interface DownloadMapper {
    /**
     * 一次SELECT读取本账号权益对应的当前原文件，不按商品上架或分类启用状态过滤。
     * 调用位置：DownloadServiceImpl.createDownloadGrant。
     * @param userId BaseContext提供的可信用户ID
     * @param wallpaperId 要申请下载的商品数据库ID
     * @return 有权益时的文件资料；没有权益或商品时为null
     */
    DownloadFileRow selectOwnedDownloadFile(@Param("userId") Long userId, @Param("wallpaperId") Long wallpaperId);
}

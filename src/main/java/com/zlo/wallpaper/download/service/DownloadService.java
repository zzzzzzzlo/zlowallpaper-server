package com.zlo.wallpaper.download.service;

import com.zlo.wallpaper.download.vo.DownloadGrantVO;

/**
 * 声明下载授权能力，已购列表由catalog模块的WallpaperService负责。
 * 创建方式：接口不实例化，Spring注入DownloadServiceImpl；注入位置DownloadController.downloadService。
 */
public interface DownloadService {
    /**
     * 根据当前登录账号权益签发当前原文件的5分钟下载URL。
     * 调用位置：DownloadController.createDownloadGrant，由DownloadServiceImpl实现。
     * @param id 请求路径中的商品字符串ID
     * @return 下载URL、过期时间及对应文件的大小、SHA和扩展名
     */
    DownloadGrantVO createDownloadGrant(String id);
}

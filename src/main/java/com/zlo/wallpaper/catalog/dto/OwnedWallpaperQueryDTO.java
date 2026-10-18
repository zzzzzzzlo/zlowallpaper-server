package com.zlo.wallpaper.catalog.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 我的已购查询参数，由Spring MVC绑定并校验，传给WallpaperServiceImpl.getMyWallpapers。
 * 复用商城的page/pageSize/keyword/categoryId规则；wallpaperId用于客户端确认某张商品的权益。
 * 这里没有userId字段，账号只能取自登录上下文，不能查询其他用户的购买记录。
 */
@Getter
@Setter
public class OwnedWallpaperQueryDTO extends WallpaperQueryDTO {
    private String wallpaperId;
}

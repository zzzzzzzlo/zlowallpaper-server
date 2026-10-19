package com.zlo.wallpaper.catalog;

import com.zlo.wallpaper.catalog.mapper.WallpaperMapper;
import com.zlo.wallpaper.catalog.model.CoverRow;
import com.zlo.wallpaper.catalog.service.CoverCacheService;
import com.zlo.wallpaper.catalog.service.CoverService;
import com.zlo.wallpaper.catalog.storage.ObjectStorage;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** JUnit实例化，验证读取顺序和替换缓存键；权限查Mapper在缓存命中时仍必须发生。 */
@ExtendWith(MockitoExtension.class)
class CoverServiceTest {
    @Mock private WallpaperMapper mapper;
    @Mock private ObjectStorage storage;
    @Mock private CoverCacheService cache;
    @TempDir Path root;
    private CoverService service;
    private final byte[] bytes = {1, 2, 3};
    private final CoverRow cover = new CoverRow(11L, "COS", "bucket-test", "covers/first.png", "image/png", 3L);

    /** 模拟Spring注入，只为本次测试创建Service，不引入额外业务辅助类。 */
    @BeforeEach
    void createService() {
        service = new CoverService(mapper, root.toString());
        ReflectionTestUtils.setField(service, "storage", storage);
        ReflectionTestUtils.setField(service, "cache", cache);
    }

    /** 清除测试线程身份，不能污染后续用例。 */
    @AfterEach
    void clearIdentity() { BaseContext.removeCurrentId(); }

    /** 初次读云、之后命中；公开、管理员和本账号已购可以复用相同图片字节。 */
    @Test
    void threeEntrypointsShareBytesAfterTheirOwnQueries() throws Exception {
        Map<String, byte[]> cached = new HashMap<>();
        when(cache.getCover(anyString())).thenAnswer(call -> cached.get(call.getArgument(0)));
        doAnswer(call -> { cached.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(cache).putCover(anyString(), any(byte[].class));
        when(mapper.selectPublicCover(11L)).thenReturn(cover);
        when(mapper.selectAdminCover(11L)).thenReturn(cover);
        when(mapper.selectOwnedCover(99L, 11L)).thenReturn(cover);
        when(storage.readCover("COS", "bucket-test", "covers/first.png", 3L)).thenReturn(bytes);
        assertArrayEquals(bytes, service.read("11").resource().getInputStream().readAllBytes());
        assertArrayEquals(bytes, service.readAdmin("11").resource().getInputStream().readAllBytes());
        BaseContext.setCurrentId(99L);
        assertArrayEquals(bytes, service.readOwned("11").resource().getInputStream().readAllBytes());
        verify(storage, times(1)).readCover("COS", "bucket-test", "covers/first.png", 3L);
        verify(mapper).selectPublicCover(11L); verify(mapper).selectAdminCover(11L); verify(mapper).selectOwnedCover(99L, 11L);
        assertEquals(1, cached.size());
    }

    /** 缓存已存在也不能绕过下架后的公开查询或他人权益限制。 */
    @Test
    void hiddenProductAndOtherAccountNeverReachTheCache() {
        BaseContext.setCurrentId(99L);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.read("11")).getStatus().value());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.readOwned("11")).getStatus().value());
        verifyNoInteractions(cache, storage);
    }

    /** 替换封面使用新的UUID键，旧缓存不会遮住管理员的新图片。 */
    @Test
    void replacementHasADifferentCacheKey() {
        CoverRow newer = new CoverRow(11L, "COS", "bucket-test", "covers/new.png", "image/png", 3L);
        when(mapper.selectPublicCover(11L)).thenReturn(cover, newer);
        when(storage.readCover(eq("COS"), eq("bucket-test"), anyString(), eq(3L))).thenReturn(bytes);
        service.read("11"); service.read("11");
        verify(cache).putCover("cover:v1:COS:bucket-test:covers/first.png", bytes);
        verify(cache).putCover("cover:v1:COS:bucket-test:covers/new.png", bytes);
    }

    /** 长度不一致的缓存按未命中处理，重新读取并覆盖。 */
    @Test
    void wrongCacheLengthFallsBackToCloud() {
        when(mapper.selectPublicCover(11L)).thenReturn(cover);
        when(cache.getCover(anyString())).thenReturn(new byte[]{1});
        when(storage.readCover("COS", "bucket-test", "covers/first.png", 3L)).thenReturn(bytes);
        service.read("11");
        verify(cache).putCover("cover:v1:COS:bucket-test:covers/first.png", bytes);
    }

    /** 本地旧封面保持基础路径检查，不走云缓存。 */
    @Test
    void localFileUsesBasicPathChecks() throws Exception {
        java.nio.file.Files.write(root.resolve("cover.png"), bytes);
        when(mapper.selectPublicCover(11L)).thenReturn(new CoverRow(11L,"LOCAL",null,"cover.png","image/png",3L),
                new CoverRow(11L,"LOCAL",null,"../outside.png","image/png",3L));
        assertArrayEquals(bytes, service.read("11").resource().getInputStream().readAllBytes());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.read("11")).getStatus().value());
        verifyNoInteractions(cache, storage);
    }
}

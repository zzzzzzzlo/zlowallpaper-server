package com.zlo.wallpaper.catalog;

import com.zlo.wallpaper.catalog.mapper.WallpaperMapper;
import com.zlo.wallpaper.catalog.model.CoverRow;
import com.zlo.wallpaper.catalog.service.CoverCacheService;
import com.zlo.wallpaper.catalog.service.CoverService;
import com.zlo.wallpaper.catalog.storage.ObjectStorage;
import com.zlo.wallpaper.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** JUnit创建；用受控的并发任务验证回源次数、不同键并行、等待者和加载者各自的清理职责。 */
@ExtendWith(MockitoExtension.class)
@Timeout(10)
class CoverServiceConcurrencyTest {
    @Mock private WallpaperMapper mapper;
    @Mock private ObjectStorage storage;
    @Mock private CoverCacheService cache;
    @TempDir Path root;
    private CoverService service;
    private Map<String, CompletableFuture<byte[]>> tasks;
    private final byte[] bytes = {1, 2, 3};
    private final String key = "cover:v1:COS:bucket-test:covers/first.png";
    private final CoverRow cover = new CoverRow(11L, "COS", "bucket-test", "covers/first.png", "image/png", 3L);

    /** 每个测试使用独立Service及任务表，不依赖静态全局状态。 */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        service = new CoverService(mapper, root.toString());
        ReflectionTestUtils.setField(service, "storage", storage);
        ReflectionTestUtils.setField(service, "cache", cache);
        tasks = (Map<String, CompletableFuture<byte[]>>) ReflectionTestUtils.getField(service, "loadingCovers");
    }

    /** 8个请求先进入业务查询；首次回源结束后其他请求只能等任务或命中缓存。 */
    @Test
    void concurrentColdRequestsReadCloudOnce() throws Exception {
        Map<String, byte[]> cached = new ConcurrentHashMap<>();
        CountDownLatch entered = new CountDownLatch(8);
        when(mapper.selectPublicCover(11L)).thenAnswer(call -> { entered.countDown(); return cover; });
        when(cache.getCover(key)).thenAnswer(call -> cached.get(key));
        doAnswer(call -> { cached.put(key, call.getArgument(1)); return null; }).when(cache).putCover(eq(key), any(byte[].class));
        when(storage.readCover("COS", "bucket-test", "covers/first.png", 3L)).thenAnswer(call -> {
            assertTrue(entered.await(3, TimeUnit.SECONDS)); return bytes;
        });
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<CoverService.Cover>> results = new ArrayList<>();
            for (int index = 0; index < 8; index++) results.add(pool.submit(() -> service.read("11")));
            for (Future<CoverService.Cover> result : results) assertArrayEquals(bytes, result.get(4, TimeUnit.SECONDS).resource().getInputStream().readAllBytes());
        }
        verify(storage, times(1)).readCover("COS", "bucket-test", "covers/first.png", 3L);
        verify(mapper, times(8)).selectPublicCover(11L);
        assertTrue(tasks.isEmpty());
    }

    /** 两张不同图片必须同时进入云读取，防止误用全局锁把业务串行化。 */
    @Test
    void differentKeysLoadInParallel() throws Exception {
        CoverRow other = new CoverRow(12L, "COS", "bucket-test", "covers/other.png", "image/png", 3L);
        when(mapper.selectPublicCover(11L)).thenReturn(cover);
        when(mapper.selectPublicCover(12L)).thenReturn(other);
        CountDownLatch bothReading = new CountDownLatch(2);
        when(storage.readCover(eq("COS"), eq("bucket-test"), anyString(), eq(3L))).thenAnswer(call -> {
            bothReading.countDown(); assertTrue(bothReading.await(3, TimeUnit.SECONDS)); return bytes;
        });
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<CoverService.Cover> first = pool.submit(() -> service.read("11"));
            Future<CoverService.Cover> second = pool.submit(() -> service.read("12"));
            assertArrayEquals(bytes, first.get(4, TimeUnit.SECONDS).resource().getInputStream().readAllBytes());
            assertArrayEquals(bytes, second.get(4, TimeUnit.SECONDS).resource().getInputStream().readAllBytes());
        }
        verify(storage, times(2)).readCover(eq("COS"), eq("bucket-test"), anyString(), eq(3L));
        assertTrue(tasks.isEmpty());
    }

    /** 第一次未命中与登记任务之间已有请求完成时，第二次读取应复用Redis，不再回源。 */
    @Test
    void rechecksRedisAfterBecomingLoader() throws Exception {
        when(mapper.selectPublicCover(11L)).thenReturn(cover);
        when(cache.getCover(key)).thenReturn(null, bytes);
        assertArrayEquals(bytes, service.read("11").resource().getInputStream().readAllBytes());
        verifyNoInteractions(storage); assertTrue(tasks.isEmpty());
    }

    /** Redis未命中/不可用时，等待者直接共享已登记任务的结果，不依赖写缓存成功。 */
    @Test
    void followersShareTheLoadingResultWithoutRedis() throws Exception {
        when(mapper.selectPublicCover(11L)).thenReturn(cover);
        CompletableFuture<byte[]> loading = new CompletableFuture<>(); tasks.put(key, loading);
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            try {
                List<Future<CoverService.Cover>> results = new ArrayList<>();
                for (int index = 0; index < 4; index++) results.add(pool.submit(() -> service.read("11")));
                verify(cache, timeout(2000).times(4)).getCover(key);
                loading.complete(bytes);
                for (Future<CoverService.Cover> result : results) assertArrayEquals(bytes, result.get(2, TimeUnit.SECONDS).resource().getInputStream().readAllBytes());
            } finally { loading.complete(bytes); }
        }
        verifyNoInteractions(storage);
        assertSame(loading, tasks.get(key)); // 等待者无权清理加载者的记录。
    }

    /** 加载者失败释放登记，下一次请求可以重新尝试；404/503业务异常保持原意。 */
    @Test
    void failedLoaderCleansUpAndAllowsRetry() throws Exception {
        when(mapper.selectPublicCover(11L)).thenReturn(cover);
        BusinessException failure = new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "COS_UNAVAILABLE", "云存储暂不可用");
        AtomicReference<CompletableFuture<byte[]>> failedTask = new AtomicReference<>();
        when(storage.readCover("COS", "bucket-test", "covers/first.png", 3L)).thenAnswer(call -> {
            failedTask.set(tasks.get(key)); throw failure;
        }).thenReturn(bytes);
        assertSame(failure, assertThrows(BusinessException.class, () -> service.read("11")));
        assertTrue(failedTask.get().isCompletedExceptionally()); // 清理前必须唤醒等待者，不能只删除登记。
        assertTrue(tasks.isEmpty());
        assertArrayEquals(bytes, service.read("11").resource().getInputStream().readAllBytes());
        assertTrue(tasks.isEmpty());
        verify(storage, times(2)).readCover("COS", "bucket-test", "covers/first.png", 3L);
    }

    /** 等待者拿到原来的业务异常，不把云文件404或503变成ExecutionException导致500。 */
    @Test
    void followerReceivesOriginalFailure() {
        when(mapper.selectPublicCover(11L)).thenReturn(cover);
        CompletableFuture<byte[]> loading = new CompletableFuture<>(); tasks.put(key, loading);
        BusinessException failure = new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "封面不存在");
        loading.completeExceptionally(failure);
        assertSame(failure, assertThrows(BusinessException.class, () -> service.read("11")));
        verifyNoInteractions(storage); assertSame(loading, tasks.get(key));
    }

    /** 等待超时后不移除或取消加载者；后续同图请求仍复用它，不能另开回源。 */
    @Test
    void timeoutLeavesTheOriginalTaskRunning() throws Exception {
        when(mapper.selectPublicCover(11L)).thenReturn(cover);
        ReflectionTestUtils.setField(service, "waitSeconds", 1L);
        CompletableFuture<byte[]> loading = new CompletableFuture<>(); tasks.put(key, loading);
        assertEquals(503, assertThrows(BusinessException.class, () -> service.read("11")).getStatus().value());
        assertSame(loading, tasks.get(key)); assertFalse(loading.isDone());
        loading.complete(bytes);
        assertArrayEquals(bytes, service.read("11").resource().getInputStream().readAllBytes());
        verifyNoInteractions(storage);
    }

    /** 等待线程被中断时恢复标记，不取消其他请求正在使用的加载任务。 */
    @Test
    void interruptionPreservesFlagAndTask() {
        when(mapper.selectPublicCover(11L)).thenReturn(cover);
        CompletableFuture<byte[]> loading = new CompletableFuture<>(); tasks.put(key, loading);
        try {
            Thread.currentThread().interrupt();
            assertEquals(503, assertThrows(BusinessException.class, () -> service.read("11")).getStatus().value());
            assertTrue(Thread.currentThread().isInterrupted()); assertSame(loading, tasks.get(key));
        } finally { Thread.interrupted(); }
        verifyNoInteractions(storage);
    }
}

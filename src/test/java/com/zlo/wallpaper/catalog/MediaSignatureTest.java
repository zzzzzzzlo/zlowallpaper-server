package com.zlo.wallpaper.catalog;

import com.zlo.wallpaper.catalog.media.MediaInspector;
import com.zlo.wallpaper.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证文件头签名的自动测试或测试配置。
 * 创建位置：由JUnit/Spring Test测试框架创建；仅用于src/test，不注册为生产业务组件。
 */
class MediaSignatureTest {
    @TempDir Path directory;
    private final MediaInspector inspector = new MediaInspector();

    /**
     * 验证改名HTML不能伪装成支持的媒体。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param extension 经过白名单校验的小写文件扩展名
     */
    @ParameterizedTest
    @ValueSource(strings = {"jpg", "jpeg", "png", "bmp", "gif", "webp", "mp4", "mov", "webm", "avi"})
    void renamedHtmlIsNeverAcceptedAsMedia(String extension) throws Exception {
        Path file = directory.resolve("fake." + extension);
        Files.writeString(file, "<html><script>alert(1)</script></html>");
        BusinessException error = assertThrows(BusinessException.class, () -> inspector.checkSignature(file, extension));
        assertEquals("INVALID_MEDIA", error.getCode());
    }

    /**
     * 验证真实PNG不能仅改扩展名冒充JPEG。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void actualPngSignatureCannotBeRenamedJpeg() throws Exception {
        Path file = directory.resolve("renamed.jpg"); Files.write(file, new byte[]{(byte)137,80,78,71,13,10,26,10});
        assertDoesNotThrow(() -> inspector.checkSignature(file, "png"));
        assertThrows(BusinessException.class, () -> inspector.checkSignature(file, "jpg"));
    }

    /**
     * 验证QuickTime容器不能仅改扩展名冒充MP4。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void quickTimeContainerCannotBeRenamedMp4() throws Exception {
        Path file = directory.resolve("renamed.mp4");
        Files.write(file, "\u0000\u0000\u0000\u0014ftypqt  ".getBytes(StandardCharsets.ISO_8859_1));
        assertDoesNotThrow(() -> inspector.checkSignature(file, "mov"));
        assertThrows(BusinessException.class, () -> inspector.checkSignature(file, "mp4"));
    }
}

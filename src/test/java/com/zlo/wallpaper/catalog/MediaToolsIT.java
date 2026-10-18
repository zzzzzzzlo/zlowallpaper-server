package com.zlo.wallpaper.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlo.wallpaper.catalog.media.MediaInspector;
import com.zlo.wallpaper.catalog.model.MediaMetadata;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.config.UploadProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证真实媒体工具的自动测试或测试配置。
 * 创建位置：由JUnit/Spring Test测试框架创建；仅用于src/test，不注册为生产业务组件。
 */
@EnabledIfSystemProperty(named = "media.tools.tests", matches = "true")
class MediaToolsIT {
    @TempDir Path directory;
    private MediaInspector inspector;
    private UploadProperties properties;

    /**
     * 创建隔离的测试依赖、媒体文件或OSS替身。
     * 框架调用位置：JUnit按测试生命周期执行。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @BeforeEach
    void setup() {
        properties = new UploadProperties();
        inspector = new MediaInspector();
        ReflectionTestUtils.setField(inspector, "properties", properties);
        ReflectionTestUtils.setField(inspector, "objectMapper", new ObjectMapper());
    }

    /**
     * 验证支持的真实媒体格式返回实际尺寸与帧率。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param extension 经过白名单校验的小写文件扩展名
     */
    @ParameterizedTest
    @ValueSource(strings = {"jpg", "jpeg", "png", "bmp", "gif", "webp", "mp4", "mov", "webm", "avi"})
    void supportedFormatsReturnRealDimensionsAndFrameRate(String extension) throws Exception {
        Path file = fixture(extension);
        MediaMetadata result = inspector.inspect(file, extension, "ORIGINAL");
        assertEquals(32, result.getWidth());
        assertEquals(24, result.getHeight());
        if (List.of("mp4", "mov", "webm", "avi").contains(extension)) {
            assertEquals(0, result.getFps().compareTo(new java.math.BigDecimal("10")));
            assertTrue(result.getMimeType().startsWith("video/"));
        } else {
            assertTrue(result.getMimeType().startsWith("image/"));
            if (!"gif".equals(extension)) { assertEquals(0, result.getFps().signum()); }
        }
    }

    /**
     * 验证封面在探测前拒绝视频并接受有效图片。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void coverRejectsVideoBeforeProbeAndAcceptsValidImage() throws Exception {
        Path video = fixture("mp4");
        BusinessException error = assertThrows(BusinessException.class, () -> inspector.inspect(video, "mp4", "COVER"));
        assertEquals(415, error.getStatus().value());
        assertEquals("image/png", inspector.inspect(fixture("png"), "png", "COVER").getMimeType());
    }

    /**
     * 验证仅有PNG签名或格式改名的无效媒体被拒绝。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void signatureOnlyPngAndRenamedImageAreRejected() throws Exception {
        Path broken = directory.resolve("损坏.png");
        Files.write(broken, new byte[]{(byte)137,80,78,71,13,10,26,10});
        assertEquals("INVALID_MEDIA", assertThrows(BusinessException.class,
                () -> inspector.inspect(broken, "png", "COVER")).getCode());
        Path jpeg = fixture("jpg");
        assertThrows(BusinessException.class, () -> inspector.inspect(jpeg, "png", "ORIGINAL"));
    }

    /**
     * 验证媒体工具缺失返回503，不放过未检查文件。
     * 框架调用位置：JUnit测试引擎执行当前用例。
     * 输入参数：无。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     */
    @Test
    void missingBinaryReturns503RatherThanAcceptingUncheckedMedia() throws Exception {
        properties.setFfprobe(directory.resolve("does-not-exist.exe").toString());
        Path file = fixture("png");
        BusinessException error = assertThrows(BusinessException.class, () -> inspector.inspect(file, "png", "COVER"));
        assertEquals(503, error.getStatus().value());
        assertEquals("MEDIA_SERVICE_UNAVAILABLE", error.getCode());
    }

    /**
     * 构造或生成指定媒体格式的测试文件。
     * 调用/引用位置：MediaToolsIT.coverRejectsVideoBeforeProbeAndAcceptsValidImage、MediaToolsIT.missingBinaryReturns503RatherThanAcceptingUncheckedMedia、MediaToolsIT.signatureOnlyPngAndRenamedImageAreRejected、MediaToolsIT.supportedFormatsReturnRealDimensionsAndFrameRate。
     *
     * @param extension 经过白名单校验的小写文件扩展名
     * @return 上述处理得到的Path结果；业务校验失败时通过异常返回错误
     */
    private Path fixture(String extension) throws Exception {
        Path file = directory.resolve("壁纸 sample." + extension);
        BufferedImage image = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); graphics.setColor(Color.BLUE); graphics.fillRect(0,0,32,24); graphics.dispose();
        if (List.of("jpg", "jpeg", "png", "bmp", "gif").contains(extension)) {
            assertTrue(ImageIO.write(image, "jpeg".equals(extension) ? "jpg" : extension, file.toFile()));
            return file;
        }
        List<String> command = new ArrayList<>(List.of(properties.getFfmpeg(), "-v", "error", "-nostdin", "-y"));
        if ("webp".equals(extension)) {
            Path png = directory.resolve("source.png"); ImageIO.write(image, "png", png.toFile());
            command.addAll(List.of("-i", png.toString(), "-frames:v", "1"));
        } else {
            command.addAll(List.of("-f", "lavfi", "-i", "color=c=blue:s=32x24:r=10:d=0.2", "-c:v",
                    "webm".equals(extension) ? "libvpx" : "mpeg4", "-threads", "1"));
        }
        command.add(file.toString());
        Path errors = directory.resolve("generate.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(errors.toFile()).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("测试素材生成超时"); }
        assertEquals(0, process.exitValue(), Files.readString(errors));
        return file;
    }
}

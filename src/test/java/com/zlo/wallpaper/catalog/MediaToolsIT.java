package com.zlo.wallpaper.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlo.wallpaper.catalog.media.MediaInspector;
import com.zlo.wallpaper.catalog.model.MediaMetadata;
import com.zlo.wallpaper.catalog.model.StoredObject;
import com.zlo.wallpaper.catalog.model.UploadedFile;
import com.zlo.wallpaper.catalog.service.impl.FileUploadServiceImpl;
import com.zlo.wallpaper.catalog.storage.ObjectStorage;
import com.zlo.wallpaper.common.BaseContext;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.config.UploadProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Random;
import java.time.Clock;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

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

    @ParameterizedTest
    @ValueSource(strings = {"jpg", "jpeg", "png", "bmp", "gif", "webp"})
    void compressesSupportedCoversToStaticJpegWithoutUpscaling(String extension) throws Exception {
        Path source = fixture(extension);
        byte[] before = Files.readAllBytes(source);
        inspector.inspect(source, extension, "COVER");
        Path target = directory.resolve("compressed.jpg");
        Files.createFile(target);
        MediaMetadata result = inspector.compressCover(source, target);
        assertEquals("image/jpeg", result.getMimeType());
        assertEquals(32, result.getWidth());
        assertEquals(24, result.getHeight());
        assertEquals(0, result.getFps().signum());
        assertNotNull(ImageIO.read(target.toFile()));
        assertArrayEquals(before, Files.readAllBytes(source));
    }

    @Test void transparentCoverBecomesWhiteAndOddDimensionsAreKept() throws Exception {
        BufferedImage image = new BufferedImage(65, 33, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(255, 0, 0, 128));
        graphics.fillRect(33, 0, 32, 33);
        graphics.dispose();
        Path source = directory.resolve("transparent.png");
        ImageIO.write(image, "png", source.toFile());
        inspector.inspect(source, "png", "COVER");
        Path target = directory.resolve("white.jpg");
        MediaMetadata metadata = inspector.compressCover(source, target);
        assertEquals(65, metadata.getWidth());
        assertEquals(33, metadata.getHeight());
        BufferedImage result = ImageIO.read(target.toFile());
        Color white = new Color(result.getRGB(5, 5));
        assertTrue(white.getRed() > 245 && white.getGreen() > 245 && white.getBlue() > 245);
        Color blended = new Color(result.getRGB(55, 15));
        assertTrue(blended.getRed() > 240 && blended.getGreen() > 100 && blended.getGreen() < 160);
    }

    @Test void realUploadCompressesCoverLargerThanFourMiBAndKeepsTrustedMetadata() throws Exception {
        BufferedImage image = new BufferedImage(2000, 1200, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(1234);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) { image.setRGB(x, y, random.nextInt(0x1000000)); }
        }
        Path source = directory.resolve("large.png");
        ImageIO.write(image, "png", source.toFile());
        byte[] input = Files.readAllBytes(source);
        assertTrue(input.length > 4 * 1024 * 1024 && input.length <= 10 * 1024 * 1024);
        ObjectStorage storage = mock(ObjectStorage.class);
        FileUploadServiceImpl uploader = new FileUploadServiceImpl();
        ReflectionTestUtils.setField(uploader, "mediaInspector", inspector);
        ReflectionTestUtils.setField(uploader, "storage", storage);
        ReflectionTestUtils.setField(uploader, "clock", Clock.systemUTC());
        List<Path> temporaryPaths = new ArrayList<>();
        List<byte[]> saved = new ArrayList<>();
        when(storage.upload(any(), anyString(), anyString(), anyLong())).thenAnswer(call -> {
            Path path = call.getArgument(0);
            temporaryPaths.add(path);
            byte[] bytes = Files.readAllBytes(path);
            saved.add(bytes);
            assertEquals("image/jpeg", call.getArgument(2));
            assertEquals((long) bytes.length, (Long) call.getArgument(3));
            return new StoredObject("COS", "test-bucket", call.getArgument(1), "version");
        });
        BaseContext.setCurrentId(11L);
        UploadedFile uploaded;
        try {
            uploaded = uploader.uploadFile(new MockMultipartFile("coverFile", "large.png", "image/png", input), "COVER");
        } finally { BaseContext.removeCurrentId(); }
        assertEquals(1920, uploaded.getWidth());
        assertEquals(1152, uploaded.getHeight());
        assertEquals("large.png", uploaded.getOriginalFilename());
        assertEquals("jpg", uploaded.getFileExtension());
        assertTrue(uploaded.getSizeBytes() < input.length / 2);
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(saved.getFirst())), uploaded.getSha256());
        for (Path path : temporaryPaths) { assertFalse(Files.exists(path)); }
        assertArrayEquals(input, Files.readAllBytes(source));
        System.out.println("Cover compression: " + input.length + " -> " + uploaded.getSizeBytes() + " bytes, 2000x1200 -> 1920x1152");
    }

    @Test void missingCompressionToolFailsWithoutChangingSource() throws Exception {
        Path source = fixture("png");
        inspector.inspect(source, "png", "COVER");
        byte[] before = Files.readAllBytes(source);
        properties.setFfmpeg(directory.resolve("missing-ffmpeg.exe").toString());
        BusinessException error = assertThrows(BusinessException.class,
                () -> inspector.compressCover(source, directory.resolve("failed.jpg")));
        assertEquals("MEDIA_SERVICE_UNAVAILABLE", error.getCode());
        assertArrayEquals(before, Files.readAllBytes(source));
    }

    @Test void animatedGifCoverUsesFirstFrameOnly() throws Exception {
        Path source = directory.resolve("animated.gif");
        Path log = directory.resolve("animated-generate.log");
        Process process = new ProcessBuilder(properties.getFfmpeg(), "-v", "error", "-nostdin", "-y",
                "-f", "lavfi", "-i", "color=c=red:s=32x24:r=2:d=1,drawbox=enable='gte(t,0.5)':color=blue:t=fill",
                "-threads", "1", source.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("动态图素材生成超时"); }
        assertEquals(0, process.exitValue(), Files.readString(log));
        javax.imageio.ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (javax.imageio.stream.ImageInputStream input = ImageIO.createImageInputStream(source.toFile())) {
            reader.setInput(input);
            assertEquals(2, reader.getNumImages(true));
        } finally { reader.dispose(); }
        inspector.inspect(source, "gif", "COVER");
        Path target = directory.resolve("first-frame.jpg");
        MediaMetadata result = inspector.compressCover(source, target);
        assertEquals(0, result.getFps().signum());
        Color firstFrame = new Color(ImageIO.read(target.toFile()).getRGB(16, 12));
        assertTrue(firstFrame.getRed() > 230 && firstFrame.getBlue() < 25);
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

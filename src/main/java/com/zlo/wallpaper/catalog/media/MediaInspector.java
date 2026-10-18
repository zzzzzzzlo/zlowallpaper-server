package com.zlo.wallpaper.catalog.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlo.wallpaper.catalog.model.MediaMetadata;
import com.zlo.wallpaper.common.BusinessException;
import com.zlo.wallpaper.config.UploadProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 验证真实文件签名、容器、尺寸、帧率和首帧，不相信客户端提供的媒体元数据。
 * 创建方式：Spring组件扫描创建并管理；不需要业务代码手动new。
 * 注入位置：FileUploadServiceImpl.mediaInspector。
 */
@Component
public class MediaInspector {
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mov", "webm", "avi");
    @Autowired private UploadProperties properties;
    @Autowired private ObjectMapper objectMapper;

    /**
     * 调用真实媒体工具验证容器、尺寸、帧率和首帧，并产生可信元数据。
     * 调用/引用位置：FileUploadServiceImpl.uploadFile、FileUploadServiceTest.invalidMediaNeverUploads、FileUploadServiceTest.validMedia、MediaToolsIT.coverRejectsVideoBeforeProbeAndAcceptsValidImage、MediaToolsIT.missingBinaryReturns503RatherThanAcceptingUncheckedMedia、MediaToolsIT.signatureOnlyPngAndRenamedImageAreRejected、MediaToolsIT.supportedFormatsReturnRealDimensionsAndFrameRate。
     *
     * @param file 服务器临时文件路径、上传对象或可信当前文件元数据
     * @param extension 经过白名单校验的小写文件扩展名
     * @param purpose 文件用途ORIGINAL或COVER，决定允许类型及大小上限
     * @return 真实检查得到的MIME、扩展名、宽高和帧率
     */
    public MediaMetadata inspect(Path file, String extension, String purpose) {
        boolean video = VIDEO_EXTENSIONS.contains(extension);
        if ("COVER".equals(purpose) && video) {
            throw new BusinessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE", "封面必须是图片或 GIF");
        }
        checkSignature(file, extension);
        String output = execute(List.of(properties.getFfprobe(), "-v", "error", "-max_alloc", "67108864",
                "-protocol_whitelist", "file,pipe", "-format_whitelist",
                "image2,jpeg_pipe,png_pipe,bmp_pipe,webp_pipe,gif,mov,matroska,webm,avi",
                "-show_entries", "stream=codec_type,codec_name,width,height,avg_frame_rate,r_frame_rate:stream_disposition=attached_pic:format=format_name,duration",
                "-of", "json", "-i", file.toString()));
        try {
            JsonNode document = objectMapper.readTree(output);
            JsonNode selected = null;
            for (JsonNode stream : document.path("streams")) {
                if ("video".equals(stream.path("codec_type").asText())
                        && stream.path("disposition").path("attached_pic").asInt() != 1) {
                    selected = stream;
                    break;
                }
            }
            if (selected == null) { throw invalid(); }
            int width = selected.path("width").asInt();
            int height = selected.path("height").asInt();
            // 控制解码内存：拒绝超大像素媒体，不影响文件字节数的 10 GiB 契约。
            if (width <= 0 || height <= 0 || (long) width * height > 50_000_000L) { throw invalid(); }
            verifyFormat(extension, document.path("format").path("format_name").asText(),
                    selected.path("codec_name").asText());
            BigDecimal fps = BigDecimal.ZERO;
            if (video || "gif".equals(extension)) {
                fps = frameRate(selected.path("avg_frame_rate").asText());
                if (fps.signum() == 0) { fps = frameRate(selected.path("r_frame_rate").asText()); }
            }
            if (video) {
                BigDecimal duration = new BigDecimal(document.path("format").path("duration").asText("0"));
                if (duration.signum() <= 0 || fps.signum() <= 0) { throw invalid(); }
            }
            if (fps.compareTo(new BigDecimal("9999999.999")) > 0) { throw invalid(); }
            // 首帧实际解码可拦截只有伪造文件头/元数据的损坏文件，不做转码。
            execute(List.of(properties.getFfmpeg(), "-v", "error", "-nostdin", "-xerror",
                    "-max_alloc", "67108864", "-threads", "1", "-protocol_whitelist", "file,pipe",
                    "-i", file.toString(), "-map", "0:v:0", "-frames:v", "1", "-f", "null", "-"));
            MediaMetadata result = new MediaMetadata();
            result.setMimeType(mimeType(extension));
            result.setWidth(width);
            result.setHeight(height);
            result.setFps(fps);
            return result;
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /**
     * 验证文件头签名与扩展名一致，拒绝伪装成媒体的脚本或损坏文件。
     * 调用/引用位置：MediaInspector.inspect、MediaSignatureTest.actualPngSignatureCannotBeRenamedJpeg、MediaSignatureTest.quickTimeContainerCannotBeRenamedMp4、MediaSignatureTest.renamedHtmlIsNeverAcceptedAsMedia。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param file 服务器临时文件路径、上传对象或可信当前文件元数据
     * @param extension 经过白名单校验的小写文件扩展名
     */
    public void checkSignature(Path file, String extension) {
        try (InputStream input = Files.newInputStream(file)) {
            byte[] head = input.readNBytes(4096);
            boolean valid;
            switch (extension) {
                case "jpg", "jpeg" -> valid = starts(head, 0xff, 0xd8, 0xff);
                case "png" -> valid = starts(head, 137, 80, 78, 71, 13, 10, 26, 10);
                case "bmp" -> valid = ascii(head, 0, "BM");
                case "gif" -> valid = ascii(head, 0, "GIF87a") || ascii(head, 0, "GIF89a");
                case "webp" -> valid = ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP");
                case "avi" -> valid = ascii(head, 0, "RIFF") && ascii(head, 8, "AVI ");
                case "mp4" -> valid = ascii(head, 4, "ftyp") && !ascii(head, 8, "qt  ");
                case "mov" -> valid = (ascii(head, 4, "ftyp") && ascii(head, 8, "qt  "))
                        || ascii(head, 4, "moov") || ascii(head, 4, "mdat") || ascii(head, 4, "wide");
                case "webm" -> valid = starts(head, 0x1a, 0x45, 0xdf, 0xa3)
                        && new String(head, StandardCharsets.ISO_8859_1).contains("webm");
                default -> valid = false;
            }
            if (!valid) { throw invalid(); }
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "UPLOAD_IO_ERROR", "上传临时文件不可读取");
        }
    }

    /**
     * 核对ffprobe检测到的容器及编解码器是否与扩展名相符。
     * 调用/引用位置：MediaInspector.inspect。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param extension 经过白名单校验的小写文件扩展名
     * @param format ffprobe确认的真实容器格式
     * @param codec ffprobe确认的真实编解码器名称
     */
    private void verifyFormat(String extension, String format, String codec) {
        boolean valid;
        switch (extension) {
        case "jpg", "jpeg" -> valid = "mjpeg".equals(codec) && (format.contains("jpeg") || "image2".equals(format));
            case "png" -> valid = "png".equals(codec) && (format.contains("png") || "image2".equals(format));
            case "bmp" -> valid = "bmp".equals(codec) && (format.contains("bmp") || "image2".equals(format));
            case "webp" -> valid = "webp".equals(codec) && (format.contains("webp") || "image2".equals(format));
            case "gif" -> valid = "gif".equals(codec) && "gif".equals(format);
            case "mp4", "mov" -> valid = format.contains("mov") && !codec.isBlank();
            case "webm" -> valid = format.contains("webm") && !codec.isBlank();
            case "avi" -> valid = "avi".equals(format) && !codec.isBlank();
            default -> valid = false;
        }
        if (!valid) { throw invalid(); }
    }

    /**
     * 解析分数形式的帧率并保留三位小数，不能解析时返回零。
     * 调用/引用位置：MediaInspector.inspect。
     *
     * @param value 当前待读取、校验或转换的输入值
     * @return 上述处理得到的BigDecimal结果；业务校验失败时通过异常返回错误
     */
    private BigDecimal frameRate(String value) {
        try {
            String[] fraction = value.split("/");
            if (fraction.length != 2) { return BigDecimal.ZERO; }
            BigDecimal numerator = new BigDecimal(fraction[0]);
            BigDecimal denominator = new BigDecimal(fraction[1]);
            if (numerator.signum() <= 0 || denominator.signum() <= 0) { return BigDecimal.ZERO; }
            return numerator.divide(denominator, 3, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 以独立参数启动媒体检查进程，限制时间和输出大小并回收临时日志。
     * 调用/引用位置：MediaInspector.inspect。
     *
     * @param command 按独立参数组织的媒体工具命令，不经过Shell拼接执行
     * @return 上述处理得到的String结果；业务校验失败时通过异常返回错误
     */
    private String execute(List<String> command) {
        Path output = null;
        Path errors = null;
        Process process = null;
        try {
            output = Files.createTempFile("zlo-media-out-", ".txt");
            errors = Files.createTempFile("zlo-media-error-", ".txt");
            // 参数逐个传入，不经过 cmd/PowerShell，也不执行客户端文件名中的命令。
            process = new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
            if (!process.waitFor(properties.getMediaTimeoutSeconds(), TimeUnit.SECONDS)) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "MEDIA_CHECK_TIMEOUT", "媒体检查超时，请选择其他文件");
            }
            if (process.exitValue() != 0 || Files.size(output) > 1024 * 1024) { throw invalid(); }
            return Files.readString(output, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_SERVICE_UNAVAILABLE", "媒体检查工具不可用，请检查 ffprobe/ffmpeg 配置");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_SERVICE_UNAVAILABLE", "媒体检查已中断");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
            deleteTemporary(output);
            deleteTemporary(errors);
        }
    }

    /**
     * 删除媒体检查临时文件，删除失败时安排进程退出后清理。
     * 调用/引用位置：MediaInspector.execute。
     * 返回：无返回值；结果通过数据库、上下文、响应或测试断言体现。
     *
     * @param file 服务器临时文件路径、上传对象或可信当前文件元数据
     */
    private void deleteTemporary(Path file) {
        if (file != null) {
            try { Files.deleteIfExists(file); }
            catch (IOException exception) { file.toFile().deleteOnExit(); }
        }
    }

    /**
     * 比较字节数组开头是否与指定文件签名完全一致。
     * 调用/引用位置：MediaInspector.checkSignature。
     *
     * @param bytes 读取的文件头字节或待校验字节数组
     * @param signature 预期的文件头签名字节序列
     * @return 判断结果；true表示上述条件成立，false表示不成立
     */
    private boolean starts(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) { return false; }
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[i] & 0xff) != signature[i]) { return false; }
        }
        return true;
    }

    /**
     * 比较文件头指定偏移处是否包含预期ASCII标识。
     * 调用/引用位置：MediaInspector.checkSignature。
     *
     * @param bytes 读取的文件头字节或待校验字节数组
     * @param offset 需要开始匹配的字节偏移
     * @param text 需要整理、计数或比对的字符串
     * @return 判断结果；true表示上述条件成立，false表示不成立
     */
    private boolean ascii(byte[] bytes, int offset, String text) {
        if (bytes.length < offset + text.length()) { return false; }
        for (int i = 0; i < text.length(); i++) {
            if (bytes[offset + i] != text.charAt(i)) { return false; }
        }
        return true;
    }

    /**
     * 根据已校验的扩展名返回标准媒体类型。
     * 调用/引用位置：MediaInspector.inspect。
     *
     * @param extension 经过白名单校验的小写文件扩展名
     * @return 上述处理得到的String结果；业务校验失败时通过异常返回错误
     */
    private String mimeType(String extension) {
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "mp4" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "webm" -> "video/webm";
            case "avi" -> "video/x-msvideo";
            default -> throw invalid();
        };
    }

    /**
     * 构造真实格式、容器、尺寸或解码检查失败的422媒体业务异常。
     * 调用/引用位置：MediaInspector.checkSignature、MediaInspector.execute、MediaInspector.inspect、MediaInspector.mimeType、MediaInspector.verifyFormat。
     * 输入参数：无。
     *
     * @return 供调用方抛出的业务异常，携带安全消息与实际HTTP状态
     */
    private BusinessException invalid() {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_MEDIA", "文件格式与扩展名不符、媒体损坏或媒体信息不符合要求");
    }
}

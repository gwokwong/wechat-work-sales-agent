package com.example.wechatsales.screenshot;

import com.example.wechatsales.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 截图 OCR 识别服务。
 *
 * <p>按 {@code app.ocr.mode} 路由识别方案：
 * <ul>
 *   <li>{@code auto}（默认）：先探测本机 tesseract CLI（版本探测 + 首张识别探测），
 *       可用则用真实识别；否则回退到可配置外部 OCR 服务（{@code app.ocr.external-base-url}）；
 *       外部服务不可用/未配置则返回占位文本，由前端手动粘贴/编辑聊天内容，保证工作流不因 OCR 缺失阻断。</li>
 *   <li>{@code external}：强制走外部 OCR 服务，不可用时返回占位。</li>
 *   <li>{@code off}：关闭识别，一律返回占位文本。</li>
 * </ul>
 * 探测结果缓存于字段，避免每次上传重复探测。</p>
 */
@Slf4j
@Service
public class ScreenshotOcrService {

    /** 外部 OCR 服务路径（相对 external-base-url） */
    public static final String EXTERNAL_OCR_PATH = "/ocr";

    /** 鉴权 Header（可选） */
    public static final String API_KEY_HEADER = "X-API-Key";

    private final AppProperties appProperties;
    private final RestClient.Builder restClientBuilder;

    /** 已探测的可用方案缓存：TESSERACT / EXTERNAL / PLACEHOLDER / null(尚未探测) */
    private volatile OcrMode probedMode;

    public ScreenshotOcrService(AppProperties appProperties, RestClient.Builder restClientBuilder) {
        this.appProperties = appProperties;
        this.restClientBuilder = restClientBuilder;
    }

    /**
     * 对上传图片执行 OCR 识别。
     *
     * @param imageFile 已落盘的上传图片
     * @return 识别结果；整个过程不抛异常，任何失败都回退占位文本
     */
    public OcrResult recognize(File imageFile) {
        String mode = appProperties.getOcr().getMode() == null ? "auto" : appProperties.getOcr().getMode().trim();
        try {
            OcrResult result;
            switch (mode) {
                case "external" -> result = recognizeExternal(imageFile);
                case "off" -> result = OcrResult.placeholder("app.ocr.mode=off，识别已关闭");
                default -> result = recognizeAuto(imageFile);
            }
            if (result == null) {
                return OcrResult.placeholder("未配置可用的 OCR 方案");
            }
            log.info("[ScreenshotOcrService] 识别完成 mode={} textBytes={}", result.mode().code(),
                    result.text() == null ? 0 : result.text().getBytes(StandardCharsets.UTF_8).length);
            return result;
        } catch (Exception e) {
            log.warn("[ScreenshotOcrService] OCR 识别异常，回退占位文本: {}", e.getMessage());
            return OcrResult.placeholder("OCR 识别异常: " + e.getMessage());
        }
    }

    private OcrResult recognizeAuto(File imageFile) {
        // 1) 探测/复用本机 tesseract
        if (probedMode == null || probedMode == OcrMode.TESSERACT) {
            OcrResult local = recognizeTesseract(imageFile);
            if (local != null && local.mode() == OcrMode.TESSERACT) {
                probedMode = OcrMode.TESSERACT;
                return local;
            }
            probedMode = null;
        }
        // 2) 探测/复用外部 OCR 服务
        if (probedMode == null || probedMode == OcrMode.EXTERNAL) {
            OcrResult external = recognizeExternal(imageFile);
            if (external != null && external.mode() == OcrMode.EXTERNAL) {
                probedMode = OcrMode.EXTERNAL;
                return external;
            }
            if (appProperties.getOcr().getExternalBaseUrl() == null
                    || appProperties.getOcr().getExternalBaseUrl().isBlank()) {
                probedMode = OcrMode.PLACEHOLDER;
            } else {
                probedMode = OcrMode.PLACEHOLDER;
            }
        }
        // 3) 占位兜底
        return OcrResult.placeholder("本机无 tesseract 且未配置可用外部 OCR 服务(app.ocr.external-base-url)");
    }

    /**
     * 本机 tesseract CLI 识别：tesseract <image> stdout -l <lang>（先按配置语言，失败回退 eng）。
     *
     * <p>返回语义：只要本机 tesseract 可执行（commandAvailable）即返回 TESSERACT 结果——某张图
     * 识别文本为空（无文字/纯图片）时也返回文本为空的 TESSERACT 结果，由上层「空文本不入链」逻辑
     * 提示前端手动编辑，避免把可用引擎误判为占位兜底；仅当命令不可用或全部语言调用异常时才返回 null。</p>
     */
    private OcrResult recognizeTesseract(File imageFile) {
        String tesseract = appProperties.getOcr().getTesseractPath() == null
                ? "tesseract" : appProperties.getOcr().getTesseractPath();
        if (!commandAvailable(tesseract)) {
            log.info("[ScreenshotOcrService] tesseract 不可用: {}", tesseract);
            return null;
        }
        String[] langs = {appProperties.getOcr().getTesseractLang(), "eng"};
        OcrResult lastEmpty = null;
        for (String lang : langs) {
            if (lang == null || lang.isBlank()) {
                continue;
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(tesseract, imageFile.getAbsolutePath(), "stdout", "-l", lang);
                pb.redirectErrorStream(false);
                Process process = pb.start();
                // 先等进程结束再读 stdout：聊天截图识别文本量小（远小于管道缓冲），不会阻塞；
                // 按时间超时强制终止，避免个别异常图片长时间挂起进程。
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    log.warn("[ScreenshotOcrService] tesseract 超时已强制终止 path={} lang={}", tesseract, lang);
                    process.destroyForcibly();
                    continue;
                }
                if (process.exitValue() != 0) {
                    log.warn("[ScreenshotOcrService] tesseract 退出码 {} lang={}（尝试下一语言）", process.exitValue(), lang);
                    continue;
                }
                String raw = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String trimmed = normalize(raw);
                if (!trimmed.isBlank()) {
                    return new OcrResult(OcrMode.TESSERACT, trimmed,
                            "tesseract lang=" + lang + " path=" + tesseract);
                }
                lastEmpty = new OcrResult(OcrMode.TESSERACT, "",
                        "tesseract lang=" + lang + " path=" + tesseract + "（识别文本为空）");
            } catch (Exception e) {
                log.debug("[ScreenshotOcrService] tesseract lang={} 识别失败: {}", lang, e.getMessage());
            }
        }
        return lastEmpty;
    }

    /**
     * 外部 OCR 服务识别：POST {base-url}/ocr，请求头 X-API-Key（可选），
     * 请求体 {"imageBase64":"..."}，期望返回 {"text":"..."}。
     */
    private OcrResult recognizeExternal(File imageFile) {
        String baseUrl = appProperties.getOcr().getExternalBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        try {
            String base64 = Base64.getEncoder().encodeToString(
                    java.nio.file.Files.readAllBytes(imageFile.toPath()));
            Map<String, Object> payload = Map.of("imageBase64", base64);
            RestClient client = restClientBuilder.build();
            String apiKey = appProperties.getOcr().getExternalApiKey();
            var spec = client.post()
                    .uri(baseUrl + EXTERNAL_OCR_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload);
            if (apiKey != null && !apiKey.isBlank()) {
                spec = spec.header(API_KEY_HEADER, apiKey);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> body = spec.retrieve().body(Map.class);
            if (body == null) {
                return null;
            }
            Object textObj = body.get("text") != null ? body.get("text")
                    : (body.get("result") instanceof Map<?, ?> result ? result.get("text") : null);
            String text = normalize(textObj == null ? null : String.valueOf(textObj));
            if (text.isBlank()) {
                return null;
            }
            return new OcrResult(OcrMode.EXTERNAL, text, "external=" + baseUrl);
        } catch (Exception e) {
            log.warn("[ScreenshotOcrService] 外部 OCR 服务调用失败: {}", e.getMessage());
            return null;
        }
    }

    private boolean commandAvailable(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            return process.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("[ScreenshotOcrService] 探测可执行文件失败 command={}: {}", command, e.getMessage());
            return false;
        }
    }

    /** 兜底清理：去首尾空白后按行整理，空文本返回空串 */
    private String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.trim();
    }
}

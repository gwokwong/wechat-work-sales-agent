package com.example.wechatsales.screenshot;

import com.example.wechatsales.channel.MessageBus;
import com.example.wechatsales.config.AppProperties;
import com.example.wechatsales.domain.Message;
import com.example.wechatsales.domain.ReplyDraft;
import com.example.wechatsales.exception.BusinessException;
import com.example.wechatsales.exception.NotFoundException;
import com.example.wechatsales.repository.ContactRepository;
import com.example.wechatsales.repository.ReplyDraftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 截图工作流服务：
 * 上传图片落盘 → OCR 识别（ScreenshotOcrService）→ 组装文本消息 → 经 MessageBus
 * 复用既有 agent 编排链路（SalesAgentOrchestrator）生成话术草稿 → 支持将话术导出为文本文件。
 *
 * <p>上传与导出目录默认位于应用工作目录 ./data 下（{@code app.screenshot.upload-dir / export-dir}），
 * 并通过 {@code com.example.wechatsales.config.WebMvcConfig} 暴露为静态资源
 * {@code /uploads/screenshot/**}、{@code /uploads/export/**}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScreenshotService {

    /** 截图来源消息的通道标记（区别于 mock/wecom，用于流水审计） */
    public static final String CHANNEL_SCREENSHOT = "screenshot";
    public static final String MSG_ID_PREFIX = "screenshot-";

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AppProperties appProperties;
    private final ScreenshotOcrService ocrService;
    private final MessageBus messageBus;
    private final ContactRepository contactRepository;
    private final ReplyDraftRepository draftRepository;

    /** 上传后落盘结果 */
    public record StoreResult(String filePath, String url, String fileName, long sizeBytes) {
    }

    /** 提交识别文本后的编排结果 */
    public record SubmitResult(String messageId, ReplyDraft draft) {
    }

    /** 导出话术文件结果 */
    public record ExportResult(String filePath, String url, String fileName) {
    }

    /**
     * 批量上传并识别多张聊天截图：逐张「校验落盘 → OCR 识别」，返回按原顺序对应的结果列表。
     * 任一张失败（空文件/格式不支持等）会中止整批并抛出明确异常；多张识别文本由前端合并后提交生成。
     */
    public List<UploadResult> storeAndRecognizeAll(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException("未选择上传文件");
        }
        if (files.size() > appProperties.getScreenshot().getMaxBatchCount()) {
            throw new BusinessException("单次最多上传 " + appProperties.getScreenshot().getMaxBatchCount() + " 张截图");
        }
        List<UploadResult> results = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            StoreResult stored = storeUpload(file);
            OcrResult ocr = recognize(Path.of(stored.filePath()));
            results.add(new UploadResult(stored.fileName(), stored.url(), stored.sizeBytes(), ocr));
        }
        return results;
    }

    /** 校验并保存上传图片，返回落盘路径与可访问 URL */
    public StoreResult storeUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件为空");
        }
        AppProperties.Screenshot cfg = appProperties.getScreenshot();
        if (file.getSize() > cfg.getMaxUploadBytes()) {
            throw new BusinessException("图片超过大小上限（"
                    + (cfg.getMaxUploadBytes() / 1024 / 1024) + "MB）");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (ext == null || !cfg.getAllowedExtensions().contains(ext)) {
            throw new BusinessException("不支持的图片格式：仅支持 " + String.join(", ", cfg.getAllowedExtensions()));
        }
        try {
            Path dir = Paths.get(cfg.getUploadDir())
                    .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));
            Files.createDirectories(dir);
            String fileName = LocalDateTime.now().format(FILE_TS) + "-" + UUID.randomUUID()
                    .toString().substring(0, 8) + "." + ext;
            Path target = dir.resolve(fileName);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            String url = "/uploads/screenshot/" + dir.getFileName() + "/" + fileName;
            log.info("[ScreenshotService] 上传图片已保存 file={} url={} size={}",
                    target, url, file.getSize());
            return new StoreResult(target.toAbsolutePath().toString(), url, fileName, file.getSize());
        } catch (IOException e) {
            log.error("[ScreenshotService] 保存上传图片失败", e);
            throw new BusinessException("保存上传图片失败: " + e.getMessage());
        }
    }

    /** 对已落盘的图片执行 OCR 识别 */
    public OcrResult recognize(Path imageFile) {
        return ocrService.recognize(imageFile.toFile());
    }

    /**
     * 把「前端确认后的聊天文本」组装为消息并经总线投递给既有 agent 编排链路。
     *
     * @param externalUserId 客户 external_userid（截图对应的会话归属客户，前端选择）
     * @param text           识别/编辑后的聊天内容
     * @return 编排结果；若命中转人工/无可用策略/间隔拦截等未生成草稿场景，draft 为 null
     */
    public SubmitResult submitInboundText(String externalUserId, String text) {
        if (externalUserId == null || externalUserId.isBlank()) {
            throw new BusinessException("缺少客户标识，请先选择截图对应的客户");
        }
        if (text == null || text.isBlank()) {
            throw new BusinessException("聊天文本为空，请先粘贴或编辑识别内容");
        }
        contactRepository.findByExternalUserId(externalUserId)
                .orElseThrow(() -> new BusinessException(
                        "客户不存在 externalUserId=" + externalUserId + "，请先在客户管理创建该客户"));

        String msgId = MSG_ID_PREFIX + UUID.randomUUID();
        Message message = Message.inbound(msgId, externalUserId, text, CHANNEL_SCREENSHOT);
        messageBus.publish(message);

        ReplyDraft draft = draftRepository.findTopBySourceMsgIdOrderByIdDesc(msgId).orElse(null);
        if (draft == null) {
            log.info("[ScreenshotService] 截图消息处理完毕，未生成话术草稿 msgId={}（可能命中转人工/无策略/间隔拦截）",
                    msgId);
        }
        return new SubmitResult(msgId, draft);
    }

    /**
     * 将话术草稿导出为文件（txt 或 docx），返回可访问 URL。
     *
     * @param format 目标格式：txt（默认，UTF-8 纯文本）；docx 用 JDK 自带 ZipOutputStream
     *               手写最小合法 WordprocessingML 结构（零第三方依赖）。
     */
    public ExportResult exportDraft(Long draftId, String format) {
        ReplyDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new NotFoundException("话术草稿不存在 draftId=" + draftId));
        String fmt = format == null || format.isBlank() ? "txt" : format.trim().toLowerCase(Locale.ROOT);
        boolean docx = "docx".equals(fmt);
        String ext = docx ? "docx" : "txt";
        try {
            Path dir = Paths.get(appProperties.getScreenshot().getExportDir());
            Files.createDirectories(dir);
            String fileName = "draft-" + draft.getId() + "-"
                    + LocalDateTime.now().format(FILE_TS) + "." + ext;
            Path target = dir.resolve(fileName);
            List<String> lines = buildExportLines(draft);
            if (docx) {
                Files.write(target, buildDocx(lines));
            } else {
                Files.writeString(target, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            }
            String url = "/uploads/export/" + fileName;
            log.info("[ScreenshotService] 话术草稿已导出 file={} url={} format={}", target, url, fmt);
            return new ExportResult(target.toAbsolutePath().toString(), url, fileName);
        } catch (IOException e) {
            log.error("[ScreenshotService] 导出话术失败", e);
            throw new BusinessException("导出话术文件失败: " + e.getMessage());
        }
    }

    /** 组装导出文件内容（头部元信息 + 话术正文，逐行），txt 与 docx 共用同一内容源 */
    private List<String> buildExportLines(ReplyDraft draft) {
        List<String> lines = new ArrayList<>();
        lines.add("# 销售话术草稿");
        lines.add("草稿ID: " + draft.getId());
        lines.add("阶段: " + blank(draft.getStage()));
        lines.add("策略: " + blank(draft.getStrategyName()));
        lines.add("动作: " + blank(draft.getActionType()));
        lines.add("状态: " + blank(draft.getStatus()));
        if (draft.getQuoteReference() != null && !draft.getQuoteReference().isBlank()) {
            lines.add("报价单号: " + draft.getQuoteReference());
        }
        if (draft.getBlockedReason() != null && !draft.getBlockedReason().isBlank()) {
            lines.add("合规阻断原因: " + draft.getBlockedReason());
        }
        lines.add("");
        lines.add("-----------------");
        lines.add("");
        lines.add(draft.getContent());
        return lines;
    }

    /**
     * 用 JDK 自带 ZipOutputStream 生成最小合法的 .docx（Office Open XML Word 文档）：
     * {@code [Content_Types].xml + _rels/.rels + word/document.xml + word/_rels/document.xml.rels}，
     * 每个内容行渲染为一个 {@code <w:p>} 段落（首行标题加粗），中英文均可直接打开。
     */
    private byte[] buildDocx(List<String> lines) throws IOException {
        final String xmlDecl = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write((xmlDecl
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" "
                    + "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "</Types>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            zip.write((xmlDecl
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" "
                    + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" "
                    + "Target=\"word/document.xml\"/>"
                    + "</Relationships>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            StringBuilder doc = new StringBuilder();
            doc.append(xmlDecl)
                    .append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
            boolean titlePending = true;
            for (String line : lines) {
                if (line.isBlank()) {
                    doc.append("<w:p/>");
                    continue;
                }
                if (titlePending && line.startsWith("#")) {
                    titlePending = false;
                    doc.append("<w:p><w:pPr><w:rPr><w:b/></w:rPr></w:pPr><w:r><w:rPr><w:b/></w:rPr>")
                            .append("<w:t xml:space=\"preserve\">")
                            .append(escapeXml(line)).append("</w:t></w:r></w:p>");
                } else {
                    doc.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                            .append(escapeXml(line)).append("</w:t></w:r></w:p>");
                }
            }
            doc.append("<w:sectPr/></w:body></w:document>");
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(doc.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("word/_rels/document.xml.rels"));
            zip.write((xmlDecl
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }

    /** OOXML 文本节点转义：& < > " */
    private String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** 截图上传后由调用方组合的 OCR 结果（图片 URL + 文本 + 方案），避免 controller 直接拼 DTO */
    public record UploadResult(String fileName, String imageUrl, long sizeBytes,
                               OcrResult ocr) {
    }

    private String extensionOf(String name) {
        if (name == null || !name.contains(".")) {
            return null;
        }
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private String blank(String s) {
        return s == null ? "" : s;
    }
}

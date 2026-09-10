package com.example.wechatsales.rest;

import com.example.wechatsales.domain.ReplyDraft;
import com.example.wechatsales.screenshot.OcrResult;
import com.example.wechatsales.screenshot.ScreenshotService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 截图工作流 REST 接口：上传聊天截图 → OCR 识别 → 复用既有 agent 编排生成话术草稿 → 导出文件。
 *
 * <p>与既有管理端风格一致：统一 {@link ApiResponse}（code=200 成功），
 * 入参用 record 请求体 + jakarta validation，无 HMAC/鉴权（演示环境）。</p>
 */
@RestController
@RequestMapping("/api/screenshot")
@RequiredArgsConstructor
public class ScreenshotController {

    private final ScreenshotService screenshotService;

    /**
     * POST /api/screenshot/upload —— 批量上传聊天截图（multipart/form-data，字段名 file，可多张）。
     * 每张图片落盘到本地目录并立即 OCR，返回逐张图片访问 URL 与识别出的文本
     * （占位/空文本由前端编辑确认后合并，再调用 process 生成话术）。
     */
    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") List<MultipartFile> files) {
        List<ScreenshotService.UploadResult> items = screenshotService.storeAndRecognizeAll(files);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", items.size());
        body.put("items", items.stream().map(ScreenshotController::uploadItemBody).toList());
        return ApiResponse.ok("上传成功，共 " + items.size() + " 张", body);
    }

    private static Map<String, Object> uploadItemBody(ScreenshotService.UploadResult item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fileName", item.fileName());
        m.put("imageUrl", item.imageUrl());
        m.put("sizeBytes", item.sizeBytes());
        Map<String, Object> ocrBody = new LinkedHashMap<>();
        ocrBody.put("mode", item.ocr().mode().code());
        ocrBody.put("modeLabel", item.ocr().mode().label());
        ocrBody.put("text", item.ocr().text());
        ocrBody.put("detail", item.ocr().detail());
        m.put("ocr", ocrBody);
        return m;
    }

    /**
     * POST /api/screenshot/process —— 以「前端确认后的聊天文本」触发生成。
     * 组装为截图来源消息经 MessageBus 进入既有 SalesAgentOrchestrator 编排，
     * 返回生成的待审批话术草稿；未生成草稿时 drafted=false。
     */
    @PostMapping("/process")
    public ApiResponse<Map<String, Object>> process(@Valid @RequestBody ProcessRequest req) {
        ScreenshotService.SubmitResult result =
                screenshotService.submitInboundText(req.externalUserId(), req.text());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageId", result.messageId());
        body.put("drafted", result.draft() != null);
        body.put("draft", result.draft());
        return ApiResponse.ok(result.draft() != null ? "话术已生成" : "未生成话术（可能命中转人工/无可用策略/发送间隔限制）", body);
    }

    /**
     * GET /api/screenshot/drafts/{draftId}/export —— 将话术草稿导出为文件并返回下载 URL。
     * 可选参数 format=txt（默认）| docx。
     */
    @GetMapping("/drafts/{draftId}/export")
    public ApiResponse<Map<String, Object>> export(@PathVariable Long draftId,
                                                   @RequestParam(value = "format", defaultValue = "txt") String format) {
        ScreenshotService.ExportResult export = screenshotService.exportDraft(draftId, format);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileName", export.fileName());
        body.put("downloadUrl", export.url());
        body.put("format", format);
        return ApiResponse.ok("导出成功", body);
    }

    /**
     * 生成请求体：externalUserId 为截图对应会话的客户标识（由前端从既有客户列表选择），
     * text 为 OCR 识别结果（前端编辑确认后）的聊天文本。
     */
    public record ProcessRequest(@NotBlank String externalUserId, @NotBlank String text) {
    }
}

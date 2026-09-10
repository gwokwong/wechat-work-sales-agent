package com.example.wechatsales.screenshot;

/**
 * OCR 识别结果。
 *
 * @param mode  实际采用的识别方案
 * @param text  识别出的聊天文本；占位兜底时为提示用户手动粘贴的说明文本
 * @param detail 补充说明（识别语言、外部服务地址、失败原因等）
 */
public record OcrResult(OcrMode mode, String text, String detail) {

    public static OcrResult placeholder(String detail) {
        return new OcrResult(OcrMode.PLACEHOLDER, "[未识别出聊天内容] 当前未配置可用的 OCR（tesseract / 外部服务），"
                + "请在上方编辑框中手动粘贴或输入聊天文本后生成。", detail);
    }
}

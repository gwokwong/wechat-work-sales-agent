package com.example.wechatsales.screenshot;

/**
 * 截图 OCR 识别方案枚举。
 *
 * <ul>
 *   <li>{@link #TESSERACT}：本机 tesseract CLI 识别成功；</li>
 *   <li>{@link #EXTERNAL}：通过可配置外部 OCR 服务（app.ocr.external-base-url）识别成功；</li>
 *   <li>{@link #PLACEHOLDER}：无可用 OCR，返回占位文本，由前端手动粘贴/编辑聊天内容。</li>
 * </ul>
 */
public enum OcrMode {

    /** 本机 tesseract CLI */
    TESSERACT("tesseract"),
    /** 外部 OCR 服务 */
    EXTERNAL("external"),
    /** 占位兜底：实机不可用，由前端手动粘贴 */
    PLACEHOLDER("placeholder");

    private final String code;

    OcrMode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 给人看的描述，随 OCR 结果返回给前端展示 */
    public String label() {
        return switch (this) {
            case TESSERACT -> "本机 tesseract OCR";
            case EXTERNAL -> "外部 OCR 服务";
            case PLACEHOLDER -> "未启用 OCR（占位，可手动粘贴/编辑）";
        };
    }
}

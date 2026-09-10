package com.example.wechatsales.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Web MVC 静态资源映射：把截图工作流产物目录暴露为可访问 URL。
 *
 * <ul>
 *   <li>{@code /uploads/screenshot/**} → {@code app.screenshot.upload-dir}（上传的聊天截图）</li>
 *   <li>{@code /uploads/export/**} → {@code app.screenshot.export-dir}（导出的话术文本文件）</li>
 * </ul>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AppProperties appProperties;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registerDir(registry, "/uploads/screenshot/**", appProperties.getScreenshot().getUploadDir(), "上传截图目录");
        registerDir(registry, "/uploads/export/**", appProperties.getScreenshot().getExportDir(), "话术导出目录");
    }

    private void registerDir(ResourceHandlerRegistry registry, String urlPattern, String dir, String desc) {
        if (dir == null || dir.isBlank()) {
            log.warn("[WebMvcConfig] {} 未配置，跳过静态映射 {}", desc, urlPattern);
            return;
        }
        Path absolute = Paths.get(dir).toAbsolutePath().normalize();
        String location = absolute.toUri().toString();
        log.info("[WebMvcConfig] 静态映射 {} -> {}（{}）", urlPattern, absolute, desc);
        registry.addResourceHandler(urlPattern).addResourceLocations(location);
    }
}

package com.github.reactivity512.rag.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Раздача статики и страниц.
 *
 * <p>Фронтенд (перенесён без изменений из Python-версии) ссылается на
 * {@code /static/styles.css}, поэтому каталог ресурсов {@code classpath:/static/}
 * публикуется по префиксу {@code /static/**}, а корень и {@code /upload}
 * отдают соответствующие страницы.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/static/**")
                .addResourceLocations("classpath:/static/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/upload").setViewName("forward:/upload.html");
    }
}

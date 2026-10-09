package com.lunarforge.market.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// отдаём загруженные файлы из папки uploads по адресу /files/...
// FileStorageService сохраняет файл и возвращает ссылку вида /files/uuid.jpg,
// а тут я говорю спрингу: всё что начинается с /files/ ищи на диске в папке uploads.
// без этого картинки и голосовые в чате сохранялись бы, но приложение получало бы 404
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // "file:" значит путь на диске, а не внутри jar. путь относительный - от папки, где запущен сервер
        registry.addResourceHandler("/files/**")
                .addResourceLocations("file:uploads/");
    }
}

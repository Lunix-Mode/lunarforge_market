package com.lunarforge.market.controller;

import com.lunarforge.market.service.FileStorageService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

// загрузка файлов: фото товаров, аватарки, вложения в чат (фото/видео/голосовые/кружки).
// сначала приложение кидает сюда файл, получает url, а уже потом этот url отправляет
// в сообщении или в объявлении. сам файл в базу не пишу, только ссылку на него
@RestController
@RequestMapping("/api/files")
public class FileUploadController {
    private final FileStorageService fileStorageService;

    public FileUploadController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    // POST /api/files/upload, файл приходит multipart-ом в поле "file".
    // вся работа (проверка типа, имя файла, сохранение на диск) в FileStorageService,
    // контроллер только отдаёт обратно {"url": "..."}. если файл больше лимита -
    // спринг кинет MaxUploadSizeExceededException и его поймает GlobalExceptionHandler
    @PostMapping("/upload")
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) {
        String url = fileStorageService.store(file);
        return Map.of("url", url);
    }
}

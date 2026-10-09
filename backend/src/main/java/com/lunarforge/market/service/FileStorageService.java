package com.lunarforge.market.service;

import com.lunarforge.market.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

// файлы лежат в папке uploads рядом с сервером
// сюда через FileUploadController попадают все загрузки из приложения: картинки и вложения чата (фото, видео, голосовые, кружки).
// сервис только сохраняет файл и отдаёт ссылку, раздачу по /files/** настраивает WebConfig
@Service
public class FileStorageService {
    // белый список расширений. всё остальное отклоняю
    private static final java.util.Set<String> ALLOWED_EXTENSIONS = java.util.Set.of(
            "jpg", "jpeg", "png", "webp", "gif", "heic",
            "mp4", "webm", "3gp", "mov",
            "m4a", "mp3", "aac", "ogg", "wav", "amr");

    private final Path root = Paths.get("uploads");

    // при старте создаю папку, если её ещё нет (createDirectories не падает, если уже есть)
    public FileStorageService() {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RuntimeException("Could not create uploads directory", e);
        }
    }

    // имя = случайный uuid, чтобы не угадать чужие файлы.
    // только фото/видео/аудио - html и прочее нельзя, это потом раздаётся с нашего домена
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Файл пустой");
        }
        // 25 мегабайт. L - чтобы считалось в long
        if (file.getSize() > 25L * 1024 * 1024) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Файл слишком большой (макс. 25MB)");
        }

        // расширение беру из оригинального имени (то что после последней точки), в нижнем регистре,
        // чтобы PHOTO.JPG тоже проходил. само имя от пользователя не использую - там может быть ../ и т.п.
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Неподдерживаемый тип файла: ." + ext);
        }
        String filename = UUID.randomUUID() + "." + ext;

        // root.resolve(filename) = uploads/uuid.ext
        try {
            Files.copy(file.getInputStream(), root.resolve(filename));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось сохранить файл");
        }

        // эту ссылку сохраняем в базе, приложение потом грузит файл по ней
        return "/files/" + filename;
    }
}

package com.lunarforge.market.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

// все ошибки превращаем в json {status, error, message} с нормальным русским текстом.
// неожиданные пишем в лог, юзеру стектрейс не показываем
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // наши ApiException - статус и текст уже готовые, просто отдаём
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException ex) {
        return body(ex.getStatus(), ex.getMessage());
    }

    // не прошла валидация @Valid. беру первую ошибку поля и пишу "поле: что не так"
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .orElse("Некорректные данные");
        return body(HttpStatus.BAD_REQUEST, message);
    }

    // кривой json, неправильный тип параметра (буквы вместо числа) или не хватает параметра - всё это 400
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex) {
        return body(HttpStatus.BAD_REQUEST, "Некорректный запрос");
    }

    // файл больше лимита из настроек spring.servlet.multipart - отвечаю 413 с понятным текстом
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(MaxUploadSizeExceededException ex) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "Файл слишком большой (макс. 25 МБ)");
    }

    // клиент сам закрыл соединение посреди ответа (плеер перезапросил другой кусок видео, человек ушёл с экрана).
    // это не ошибка сервера: не пишем простыню в лог и не пытаемся ответить JSON-ом в уже начатый видеопоток
    // (иначе вторая ошибка "No converter for HashMap with preset Content-Type video/mp4")
    @ExceptionHandler({org.apache.catalina.connector.ClientAbortException.class,
            org.springframework.web.context.request.async.AsyncRequestNotUsableException.class})
    public void clientGone() {
        // ничего не делаем
    }

    // всё остальное, чего я не ожидал. в лог пишу полностью со стектрейсом чтобы потом найти,
    // а юзеру только общую фразу - внутренности сервера ему видеть незачем
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled error", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка сервера, попробуйте ещё раз");
    }

    // собираю тело ответа в одном месте, чтобы у всех ошибок был одинаковый формат.
    // приложение читает отсюда поле message и показывает его в тосте
    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> map = new HashMap<>();
        map.put("timestamp", Instant.now().toString());
        map.put("status", status.value());
        map.put("error", status.getReasonPhrase());
        map.put("message", message);
        return ResponseEntity.status(status).body(map);
    }
}

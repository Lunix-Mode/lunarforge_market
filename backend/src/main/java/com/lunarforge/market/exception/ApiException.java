package com.lunarforge.market.exception;

import org.springframework.http.HttpStatus;

// своё исключение для "нормальных" ошибок (нет денег, нет доступа, не найдено и т.д.).
// кидаю его из сервисов с нужным http статусом и русским текстом, а GlobalExceptionHandler
// превращает его в json-ответ. RuntimeException - чтобы не писать throws везде,
// и чтобы @Transactional откатывал транзакцию (он откатывает как раз на unchecked)
public class ApiException extends RuntimeException {
    // какой статус вернуть клиенту (400, 403, 404, 409...)
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}

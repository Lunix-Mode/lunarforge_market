package com.lunarforge.market.util;

import org.json.JSONObject;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import retrofit2.Response;

// сервер присылает причину ошибки по-русски, её и показываем вместо "не удалось".
// два случая: сервер ответил с ошибкой (message) или до сервера вообще не достучались (network)
public final class ApiErrors {
    // утилитный класс, объекты не создаём
    private ApiErrors() {}

    // достаю текст ошибки из тела ответа вида {"message": "..."}.
    // если не получилось - показываю fallback, который передал экран
    public static String message(Response<?> response, String fallback) {
        try {
            if (response.errorBody() != null) {
                // string() можно прочитать только один раз, поэтому сохраняю в переменную
                String raw = response.errorBody().string();
                String msg = new JSONObject(raw).optString("message", "");
                if (!msg.isEmpty()) return msg;
            }
        } catch (Exception ignored) {
            // тело не json или пустое - не страшно, просто идём дальше к запасному тексту
        }
        // 401 - токен протух или недействителен
        if (response.code() == 401) return "Сессия истекла, войдите снова";
        return fallback;
    }

    // для onFailure у retrofit: по типу исключения понимаю что случилось и пишу по-человечески
    public static String network(Throwable t) {
        // сервер выключен или неправильный адрес
        if (t instanceof ConnectException || t instanceof UnknownHostException) {
            return "Нет связи с сервером. Проверьте, что сервер запущен и адрес указан верно";
        }
        if (t instanceof SocketTimeoutException) return "Сервер не отвечает, попробуйте ещё раз";
        // остальные проблемы сети. порядок проверок важен: ConnectException и таймаут тоже IOException
        if (t instanceof IOException) return "Ошибка сети: " + t.getMessage();
        return "Ошибка: " + t.getMessage();
    }
}

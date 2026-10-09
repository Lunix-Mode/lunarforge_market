package com.lunarforge.market.util;

import android.content.Context;
import android.content.SharedPreferences;

// токен, id и ник в SharedPreferences, чтобы не логиниться каждый раз
// используется на экране входа (сохранить), в ApiClient (подставить токен) и при выходе (clear)
public class SessionManager {
    private static final String PREFS = "lunarforge_session";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_NICKNAME = "nickname";

    private final SharedPreferences prefs;

    // беру getApplicationContext, чтобы не держать ссылку на активити - иначе можно словить утечку памяти.
    // MODE_PRIVATE - файл с настройками видит только моё приложение
    public SessionManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // apply() пишет на диск в фоне и не тормозит интерфейс (commit() блокировал бы поток)
    public void saveSession(String token, long userId, String nickname) {
        prefs.edit()
                .putString(KEY_TOKEN, token)
                .putLong(KEY_USER_ID, userId)
                .putString(KEY_NICKNAME, nickname)
                .apply();
    }

    public String getToken() {
        return prefs.getString(KEY_TOKEN, null);
    }

    // -1 значит "не залогинен", настоящих id меньше единицы не бывает
    public long getUserId() {
        return prefs.getLong(KEY_USER_ID, -1);
    }

    public String getNickname() {
        return prefs.getString(KEY_NICKNAME, null);
    }

    // залогинен = токен есть. если токен истёк, сервер ответит 401 и тогда сессию придётся очистить
    public boolean isLoggedIn() {
        return getToken() != null;
    }

    // выход из аккаунта - стираю всё разом
    public void clear() {
        prefs.edit().clear().apply();
    }
}

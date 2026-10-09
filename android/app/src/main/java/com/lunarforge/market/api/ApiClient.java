package com.lunarforge.market.api;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.lunarforge.market.ui.auth.LoginActivity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lunarforge.market.util.SessionManager;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

// настройка retrofit для общения с сервером. все экраны берут ApiService через getApiService(),
// сам объект создаётся один раз и дальше переиспользуется
public class ApiClient {
    // IP компа в домашнем вайфае. если роутер выдаст другой - смотреть ipconfig и менять тут
    // (или закрепить IP за компом в настройках роутера)
    public static final String BASE_URL = "http://192.168.50.32:8080/";

    // кэш - чтобы не создавать retrofit и okhttp заново на каждый запрос (это дорого)
    private static ApiService apiService;
    // время последнего перехода на экран блокировки. volatile - потому что интерцептор работает в фоновых потоках okhttp
    private static volatile long lastBlockedRedirect = 0;

    // один клиент на всё приложение. токен добавляется к каждому запросу.
    // если сервер ответил 401 - чистим сессию и кидаем на логин
    public static synchronized ApiService getApiService(Context context) {
        if (apiService == null) {
            // беру контекст приложения, а не активити - иначе статическое поле держало бы закрытую активити в памяти (утечка)
            Context app = context.getApplicationContext();
            SessionManager session = new SessionManager(app);

            // интерцептор вызывается на каждый запрос: подставляю заголовок Authorization с токеном, если он есть
            Interceptor authInterceptor = chain -> {
                Request original = chain.request();
                String token = session.getToken();
                Request request = token == null ? original
                        : original.newBuilder().header("Authorization", "Bearer " + token).build();
                Response response = chain.proceed(request);
                // на /api/auth/ 401 - это просто неправильный пароль при входе, там сессию чистить не надо
                if (response.code() == 401 && token != null && !original.url().encodedPath().startsWith("/api/auth/")) {
                    session.clear();
                    // интерцептор работает не в главном потоке, а запускать активити надо из главного - поэтому через Handler
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Intent intent = new Intent(app, LoginActivity.class);
                        // NEW_TASK нужен потому что стартую из контекста приложения, CLEAR_TASK - чтобы кнопкой назад нельзя было вернуться
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        app.startActivity(intent);
                    });
                }
                // заблокировали прямо во время работы - сервер ответит 403 с "blocked":true на любое действие
                // peekBody читает начало ответа, не "съедая" его - экран, который делал запрос, всё равно получит тело
                if (response.code() == 403 && token != null && response.peekBody(512).string().contains("\"blocked\":true")) {
                    long now = System.currentTimeMillis();
                    if (now - lastBlockedRedirect > 3000) { // несколько запросов сразу - открываем экран один раз
                        lastBlockedRedirect = now;
                        new Handler(Looper.getMainLooper()).post(() -> {
                            Intent intent = new Intent(app, com.lunarforge.market.ui.auth.BlockedActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            app.startActivity(intent);
                        });
                    }
                }
                return response;
            };

            // логирование запросов в logcat, BASIC - только метод, адрес и код ответа, без тел (там бывают токены)
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            logging.level(HttpLoggingInterceptor.Level.BASIC); // setLevel() устарел в OkHttp 4+

            OkHttpClient client = new OkHttpClient.Builder()
                    .addInterceptor(authInterceptor)
                    .addInterceptor(logging)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    // большой таймаут чтобы видео успевало грузиться на медленном инете
                    .writeTimeout(120, TimeUnit.SECONDS)
                    .build();

            // LENIENT - gson не падает на чуть кривом json от сервера
            Gson gson = new GsonBuilder().setStrictness(com.google.gson.Strictness.LENIENT).create(); // setLenient() устарел

            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl(BASE_URL)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create(gson))
                    .build();

            apiService = retrofit.create(ApiService.class);
        }
        return apiService;
    }

    // сервер отдаёт /files/xxx.jpg, а глайду нужен полный адрес.
    // если ссылка уже полная - возвращаю как есть, иначе приклеиваю BASE_URL (убрав у него последний слэш, чтобы не было //)
    public static String absoluteUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        return BASE_URL.replaceAll("/$", "") + (url.startsWith("/") ? url : "/" + url);
    }
}

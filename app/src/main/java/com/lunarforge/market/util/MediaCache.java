package com.lunarforge.market.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.lunarforge.market.api.ApiClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

// голосовые и кружки качаем ЦЕЛИКОМ и играем из файла на телефоне.
// почему: mp4/m4a с телефона пишутся с заголовком в КОНЦЕ файла. плеер, играющий по ссылке, качает начало,
// понимает что без заголовка нельзя, рвёт соединение и перезапрашивает конец - отсюда задержка перед стартом
// и ClientAbortException на сервере. из файла играет сразу, повтор - мгновенно (файл уже в кэше)
public final class MediaCache {

    // колбэк: файл скачан (onReady) или не вышло (onError). вызывается всегда в главном потоке
    public interface Ready {
        void onReady(File file);
        void onError();
    }

    private static final long MAX_BYTES = 150L * 1024 * 1024; // больше - удаляем самые старые
    // нажатия пользователя - отдельная очередь, чтобы не ждать, пока докачается предзагрузка тяжёлых кружков
    // 2 потока - можно одновременно нажать на голосовое и на кружок
    private static final ExecutorService URGENT = Executors.newFixedThreadPool(2);
    // предзагрузка - в фоне по одному файлу
    private static final ExecutorService BACKGROUND = Executors.newSingleThreadExecutor();
    // Handler главного потока: загрузка идёт в фоне, а результат надо вернуть в UI-поток (через MAIN.post)
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // свой OkHttpClient, а не из ApiClient: файлы раздаются открыто (/files/** на сервере permitAll), токен не нужен.
    // readTimeout побольше - видео-кружок может качаться долго
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    // что сейчас качается и кто ждёт результат. трогаем ТОЛЬКО из главного потока - без блокировок
    // ключ - имя файла в кэше, значение - кто ждёт этот файл и сколько загрузок по нему сейчас идёт
    private static final java.util.Map<String, Pending> IN_FLIGHT = new java.util.HashMap<>();

    // одна "заявка" на файл: список ждущих, сколько потоков его качают сейчас и запущена ли уже срочная загрузка
    private static final class Pending {
        final java.util.List<Ready> waiters = new java.util.ArrayList<>();
        int running = 0;
        boolean urgentStarted = false;
    }

    // конструктор приватный - класс только со static методами, экземпляры не нужны
    private MediaCache() {}

    // начать качать заранее (когда сообщение появилось на экране) - к нажатию файл уже будет готов
    public static void prefetch(Context context, @Nullable String url) {
        if (url == null || url.isEmpty()) return;
        // беру контекст приложения, а не Activity - иначе держали бы ссылку на закрытый экран и была бы утечка памяти
        Context app = context.getApplicationContext();
        File file = fileFor(app, url);
        // уже скачан или уже качается - второй раз не запускаем
        if ((file.exists() && file.length() > 0) || IN_FLIGHT.containsKey(file.getName())) return;
        Pending p = new Pending();
        IN_FLIGHT.put(file.getName(), p);
        // в фоне, без ждущих - просто чтобы файл лежал в кэше к моменту нажатия
        start(BACKGROUND, p, url, file);
    }

    // как prefetch, но с ответом: файл готов -> ready. не обгоняет нажатия пользователя (фоновая очередь)
    // используется для превью видео (VideoThumbs): нужен файл, но торопиться некуда
    public static void whenReady(Context context, String url, Ready ready) {
        Context app = context.getApplicationContext();
        File file = fileFor(app, url);
        if (file.exists() && file.length() > 0) {
            ready.onReady(file);
            return;
        }
        Pending p = IN_FLIGHT.get(file.getName());
        if (p == null) {
            p = new Pending();
            IN_FLIGHT.put(file.getName(), p);
            p.waiters.add(ready);
            start(BACKGROUND, p, url, file);
        } else {
            p.waiters.add(ready); // уже качается - просто ждём тот же файл
        }
    }

    // нажатие пользователя: если файл уже качается в фоне - всё равно запускаем срочную загрузку,
    // кто первый докачает - тот и отдаст файл всем ждущим
    // используется в чате и в просмотрщике, когда юзер нажал play
    public static void get(Context context, String url, @Nullable Ready ready) {
        Context app = context.getApplicationContext();
        File file = fileFor(app, url);
        if (file.exists() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis()); // чтобы при чистке не удалить то, что слушают
            if (ready != null) ready.onReady(file);
            return;
        }
        Pending p = IN_FLIGHT.get(file.getName());
        if (p == null) {
            p = new Pending();
            IN_FLIGHT.put(file.getName(), p);
        }
        if (ready != null) p.waiters.add(ready);
        // срочную загрузку запускаю только один раз на файл, иначе каждое нажатие плодило бы новый поток
        if (!p.urgentStarted) {
            p.urgentStarted = true;
            start(URGENT, p, url, file);
        }
    }

    // запускает загрузку в нужной очереди. running считаю, чтобы знать, сколько загрузок ещё идёт по этому файлу
    private static void start(ExecutorService pool, Pending p, String url, File file) {
        p.running++;
        // это уже фоновый поток - тут нельзя трогать вьюшки и IN_FLIGHT
        pool.execute(() -> {
            boolean ok = download(absolute(url), file);
            // после успешной загрузки проверяю размер кэша и чищу старое
            if (ok) trim(file.getParentFile());
            // обратно в главный поток, IN_FLIGHT меняется только там
            MAIN.post(() -> finished(file, ok));
        });
    }

    // одна из загрузок закончилась: успех - сразу отдаём всем; неудача - ждём остальные, если они ещё идут
    private static void finished(File file, boolean ok) {
        Pending p = IN_FLIGHT.get(file.getName());
        if (p == null) return; // уже отдали (другая загрузка оказалась быстрее)
        p.running--;
        // эта загрузка упала, но другая (фоновая или срочная) ещё идёт - может она докачает, ждём её
        if (!ok && p.running > 0) return;
        IN_FLIGHT.remove(file.getName());
        // всем, кто ждал - один результат
        for (Ready r : p.waiters) {
            if (ok) r.onReady(file); else r.onError();
        }
    }

    // уже скачан? (для превью кружка из файла, без сети)
    @Nullable
    public static File cachedFile(Context context, String url) {
        File f = fileFor(context.getApplicationContext(), url);
        return f.exists() && f.length() > 0 ? f : null;
    }

    // качает url в target. работает в фоновом потоке, возвращает true если файл на месте
    private static boolean download(String url, File target) {
        if (target.exists() && target.length() > 0) return true; // пока ждали очереди - уже скачал другой поток
        // качаем во временный файл и переименовываем только после полной загрузки:
        // недокачанный файл никогда не попадёт в плеер
        File part = new File(target.getParentFile(), target.getName() + "." + UUID.randomUUID() + ".part");
        Request request = new Request.Builder().url(url).build();
        // try-with-resources: response закроется сам, даже если вылетит исключение - иначе соединение повиснет
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return false;
            try (InputStream in = response.body().byteStream(); OutputStream out = new FileOutputStream(part)) {
                // читаю кусками по 64кб, весь файл в память не тяну (кружок может весить десятки мегабайт)
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            // renameTo может не сработать, если файл уже появился (его успел положить второй поток) - тогда тоже успех.
            // UUID в имени .part нужен, чтобы два потока не писали в один и тот же временный файл
            return part.renameTo(target) || (target.exists() && target.length() > 0);
        } catch (Exception e) {
            return false;
        } finally {
            // временный файл удаляю в любом случае: после удачного rename его уже нет, а после ошибки он мусор
            if (part.exists()) part.delete();
        }
    }

    // имя файла в кэше = последний кусок url. всё кроме букв/цифр/._- заменяю, чтобы не было кривых путей
    private static File fileFor(Context app, String url) {
        File dir = new File(app.getCacheDir(), "media");
        if (!dir.exists()) dir.mkdirs();
        String name = url.substring(url.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(dir, name.isEmpty() ? "file" : name);
    }

    // с сервера чаще всего приходит относительный путь /files/..., дописываю к нему адрес сервера
    private static String absolute(String url) {
        return url.startsWith("http") ? url : ApiClient.absoluteUrl(url);
    }

    // держим кэш в пределах MAX_BYTES: удаляем самые давно использованные файлы
    // synchronized - trim могут вызвать два потока загрузки сразу (URGENT на 2 потока + BACKGROUND),
    // без этого оба начали бы удалять одни и те же файлы
    private static synchronized void trim(@Nullable File dir) {
        // недокачанные .part не трогаю и не считаю - их сейчас пишет другой поток
        File[] files = dir == null ? null : dir.listFiles(f -> f.isFile() && !f.getName().endsWith(".part"));
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= MAX_BYTES) return;
        // сортирую от самого старого по lastModified (get() обновляет его при каждом проигрывании) и удаляю, пока не влезу в лимит
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= MAX_BYTES) break;
            total -= f.length();
            f.delete();
        }
    }
}

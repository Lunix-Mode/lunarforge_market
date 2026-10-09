package com.lunarforge.market.util;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// превью видео и кружков = кадр примерно с 1-й секунды (не самый первый: в начале фронталка
// ещё подстраивает яркость и кадр выходит засвеченный, почти белый).
// Glide по ссылке на видео кадр не достаёт, а по ссылке напрямую mp4 с телефона часто не читается,
// поэтому ждём, пока файл скачается в кэш (MediaCache, фоновая очередь), и берём кадр из него
public final class VideoThumbs {

    private static final int MAX_SIDE = 480; // для превью в чате больше не нужно
    // доставать кадр - тяжёлая работа, в главном потоке интерфейс бы подвисал.
    // 2 потока - чтобы при быстрой прокрутке не запускать десятки декодеров разом
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    // через этот Handler возвращаюсь в главный поток - трогать вьюшки можно только оттуда
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // готовые превью в памяти (~8 МБ) - при прокрутке назад появляются мгновенно
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        // размер записи считаю в байтах картинки, а не штуками - иначе лимит в 8 МБ не работал бы
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private VideoThumbs() {}

    // показать превью видео по его ссылке. зовётся из адаптера чата при каждой привязке строки
    public static void load(ImageView view, String url) {
        // строка могла раньше показывать фото: отменяем его незаконченную загрузку, иначе Glide перерисует превью
        com.bumptech.glide.Glide.with(view).clear(view);
        view.setTag(com.lunarforge.market.R.id.videoThumbTag, url); // строки переиспользуются - помним, чья это картинка
        // уже доставали этот кадр - показываю сразу из памяти
        Bitmap cached = MEMORY.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        // убираю старую картинку, чтобы пока грузится не висело превью чужого видео
        view.setImageDrawable(null);
        // кадр достаём из скачанного файла: по ссылке у mp4 с телефона это часто не получалось
        // (служебная часть файла лежит в конце) - отсюда и было пустое превью у кружков
        MediaCache.whenReady(view.getContext(), url, new MediaCache.Ready() {
            @Override
            public void onReady(File file) {
                // файл на диске - достаю кадр в фоновом потоке
                IO.execute(() -> {
                    Bitmap frame = firstFrame(file);
                    if (frame == null) return;
                    MEMORY.put(url, frame);
                    MAIN.post(() -> {
                        // пока доставали кадр, строку могли отдать другому сообщению - тогда не трогаем
                        if (url.equals(view.getTag(com.lunarforge.market.R.id.videoThumbTag))) view.setImageBitmap(frame);
                    });
                });
            }

            @Override
            public void onError() {
                // не скачалось - останется тёмный круг с кнопкой play
            }
        });
    }

    // достаю хороший кадр из файла и уменьшаю его. работает в фоновом потоке
    private static Bitmap firstFrame(File file) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            // через дескриптор файла - на некоторых телефонах по пути к файлу ретривер капризничает
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                r.setDataSource(in.getFD());
            }

            // длина видео в мс - чтобы у коротких кружков не просить кадр за пределами видео
            long durMs = 0;
            try {
                String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) durMs = Long.parseLong(d);
            } catch (Exception ignored) { }
            // основная точка - 1 секунда (или середина, если кружок короче 2 секунд)
            long oneSecUs = (durMs > 0 ? Math.min(1000, durMs / 2) : 1000) * 1000L;
            long twoSecUs = (durMs > 0 ? Math.min(2000, durMs * 3 / 4) : 2000) * 1000L;

            // пробую по очереди, пока не найду нормальный кадр (не сплошной белый/чёрный).
            // каждый способ в своём try (см. tryFrame/tryIndex) - если один упал, идём к следующему
            Bitmap fallback = null; // хоть какой-то кадр, если все "пустые"
            Bitmap frame;

            // 1) по номеру кадра: 10-й, потом 5-й (getFrameAtIndex есть только с Android 9 / API 28)
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                frame = tryIndex(r, 10);
                if (good(frame)) return shrink(frame, fallback);
                fallback = keep(fallback, frame);
                frame = tryIndex(r, 5);
                if (good(frame)) return shrink(frame, fallback);
                fallback = keep(fallback, frame);
            }
            // 2) по времени: ~1 с. CLOSEST - точно ближайший кадр, CLOSEST_SYNC - ближайший ключевой (быстрее)
            frame = tryFrame(r, oneSecUs, MediaMetadataRetriever.OPTION_CLOSEST);
            if (good(frame)) return shrink(frame, fallback);
            fallback = keep(fallback, frame);
            frame = tryFrame(r, oneSecUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (good(frame)) return shrink(frame, fallback);
            fallback = keep(fallback, frame);
            // 3) ~2 с - вдруг на 1-й секунде камера всё ещё засвечена
            frame = tryFrame(r, twoSecUs, MediaMetadataRetriever.OPTION_CLOSEST);
            if (good(frame)) return shrink(frame, fallback);
            fallback = keep(fallback, frame);
            // 4) последний шанс: любой кадр (-1 = "какой ретривер сочтёт нужным")
            frame = tryFrame(r, -1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (good(frame)) return shrink(frame, fallback);
            fallback = keep(fallback, frame);

            // нормального так и не нашёл - отдаю хоть что-то (может быть null, тогда останется тёмный круг)
            return fallback == null ? null : shrink(fallback, null);
        // OutOfMemoryError тоже ловлю: огромный кадр мог не влезть в память, лучше без превью, чем вылет
        } catch (Exception | OutOfMemoryError e) {
            return null; // файл совсем не открылся - останется тёмный круг с кнопкой play
        } finally {
            // ретривер держит нативные ресурсы, отпускаю всегда
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    // одна попытка достать кадр по времени; любая ошибка = просто null, чтобы перейти к следующему способу
    private static Bitmap tryFrame(MediaMetadataRetriever r, long timeUs, int option) {
        try {
            return r.getFrameAtTime(timeUs, option);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    // кадр по его номеру. getFrameAtIndex есть только с Android 9 (API 28), а minSdk у нас 24 -
    // поэтому проверка версии прямо здесь (студия видит её и не подчёркивает)
    private static Bitmap tryIndex(MediaMetadataRetriever r, int index) {
        if (android.os.Build.VERSION.SDK_INT < 28) return null;
        try {
            return r.getFrameAtIndex(index);
        } catch (Exception | OutOfMemoryError e) {
            return null; // например, в видео меньше кадров
        }
    }

    // кадр "нормальный", если он не сплошной: беру сетку 8x8 точек и смотрю разброс яркости.
    // засвеченный белый или чёрный кадр почти одного цвета - разброс маленький
    private static boolean good(Bitmap b) {
        if (b == null || b.getWidth() < 2 || b.getHeight() < 2) return false;
        int min = 255, max = 0;
        for (int i = 1; i <= 8; i++) {
            for (int j = 1; j <= 8; j++) {
                int c = b.getPixel(b.getWidth() * i / 9, b.getHeight() * j / 9);
                int y = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10; // яркость
                if (y < min) min = y;
                if (y > max) max = y;
            }
        }
        return max - min > 25; // есть хоть какие-то детали
    }

    // запасной кадр храню только один: первый попавшийся, остальные сразу освобождаю
    private static Bitmap keep(Bitmap fallback, Bitmap frame) {
        if (frame == null) return fallback;
        if (fallback == null) return frame;
        frame.recycle();
        return fallback;
    }

    // уменьшаю кадр, чтобы длинная сторона была не больше MAX_SIDE, и освобождаю запасной, если он не нужен
    private static Bitmap shrink(Bitmap frame, Bitmap fallback) {
        if (fallback != null && fallback != frame) fallback.recycle();
        // Math.min(1f, ...) - маленькие кадры не растягиваю
        float scale = Math.min(1f, MAX_SIDE / (float) Math.max(frame.getWidth(), frame.getHeight()));
        if (scale < 1f) {
            Bitmap small = Bitmap.createScaledBitmap(frame, Math.round(frame.getWidth() * scale),
                    Math.round(frame.getHeight() * scale), true);
            // большой оригинал больше не нужен - освобождаю память сразу, не дожидаясь сборщика
            if (small != frame) frame.recycle();
            return small;
        }
        return frame;
    }
}

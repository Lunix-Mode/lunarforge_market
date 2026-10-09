package com.lunarforge.market.util;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.MimeTypeMap;

import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Chat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// загрузка файлов на сервер (фото товара, аватар, вложения в чате: фото/видео/голосовые/кружки).
// сначала копирую файл из Uri во временный файл в кэше (фото по пути ещё сжимаю), потом шлю его multipart-ом.
// сервер в ответ отдаёт url, который уже и сохраняется в сообщении/лоте
public class FileUploadHelper {
    // результат загрузки - url файла на сервере или текст ошибки
    public interface UploadCallback {
        void onSuccess(String url);
        void onFailure(String message);
    }

    // один фоновый поток на все загрузки: файлы обрабатываются по очереди и не грузят телефон.
    // MAIN - чтобы вернуться в главный поток, трогать UI и стартовать Retrofit оттуда
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // копируем в фоне - большое видео иначе вешало интерфейс.
    // тип берём настоящий из ContentResolver, а не "image/*", иначе файл улетал с расширением .*
    public static void uploadFromUri(Context context, Uri uri, String fallbackMime, UploadCallback callback) {
        // беру контекст приложения, а не активити - фоновая задача может пережить экран
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                String mime = app.getContentResolver().getType(uri);
                if (mime == null || mime.endsWith("/*")) mime = fallbackMime;
                // фото с камеры весят 4-10 МБ и по домашнему wi-fi грузятся долго (а Glide может не дождаться).
                // ужимаем до 1600px и JPEG 85% - обычно 300-600 КБ, на экране телефона разницы не видно.
                // gif не трогаем (анимация), видео и голос - тоже. не вышло сжать - отправляем оригинал
                File temp = null;
                if (mime != null && mime.startsWith("image/") && !mime.equals("image/gif")) {
                    temp = compressImage(app, uri);
                    if (temp != null) mime = "image/jpeg";
                }
                if (temp == null) temp = copyToTempFile(app, uri, mime);
                String finalMime = mime;
                File finalTemp = temp;
                // дальше уже в главном потоке. переменные копирую в final, потому что в лямбду можно передать только final
                MAIN.post(() -> uploadFile(app, finalTemp, finalMime, callback));
            } catch (IOException e) {
                MAIN.post(() -> callback.onFailure("Не удалось прочитать файл: " + e.getMessage()));
            }
        });
    }

    // отправка готового файла. поле формы называется "file" - так его ждёт сервер.
    // если тип неизвестен - отправляю как application/octet-stream (просто байты)
    public static void uploadFile(Context context, File file, String mimeType, UploadCallback callback) {
        MediaType mediaType = MediaType.parse(mimeType != null ? mimeType : "application/octet-stream");
        RequestBody requestBody = RequestBody.create(file, mediaType);
        MultipartBody.Part part = MultipartBody.Part.createFormData("file", file.getName(), requestBody);

        ApiClient.getApiService(context).uploadFile(part).enqueue(new Callback<Chat.UploadResponse>() {
            @Override
            public void onResponse(Call<Chat.UploadResponse> call, Response<Chat.UploadResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    callback.onSuccess(response.body().url);
                } else {
                    callback.onFailure(ApiErrors.message(response, "Сервер отклонил файл"));
                }
                // временный файл больше не нужен - удаляю в любом случае, чтобы кэш не забивался
                file.delete();
            }

            @Override
            public void onFailure(Call<Chat.UploadResponse> call, Throwable t) {
                callback.onFailure(ApiErrors.network(t));
                file.delete();
            }
        });
    }

    // копирую содержимое Uri во временный файл. Uri от галереи - это не путь к файлу, а ссылка через ContentResolver,
    // напрямую отправить её Retrofit-у нельзя. try-with-resources сам закроет оба потока даже при ошибке
    private static File copyToTempFile(Context context, Uri uri, String mimeType) throws IOException {
        String ext = extensionFor(mimeType);
        File temp = new File(context.getCacheDir(), "upload_" + UUID.randomUUID() + "." + ext);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new IOException("Не удалось открыть файл");
            // читаю кусками по 64 КБ, чтобы не грузить в память всё видео целиком
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return temp;
    }

    // расширение по mime-типу. для голосовых (m4a), видео (mp4) и jpeg прописал руками, чтобы расширение
    // было точно то, которое я жду, остальное спрашиваю у MimeTypeMap. не знает - пусть будет .bin
    private static String extensionFor(String mime) {
        if (mime == null) return "bin";
        switch (mime) {
            case "audio/mp4": return "m4a";
            case "video/mp4": return "mp4";
            case "image/jpeg": return "jpg";
        }
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        return ext != null ? ext : "bin";
    }

    private static final int MAX_IMAGE_SIDE = 1600;

    // уменьшаем фото до MAX_IMAGE_SIDE по длинной стороне, учитываем поворот по EXIF, сохраняем JPEG 85%
    private static File compressImage(Context context, Uri uri) {
        try {
            android.content.ContentResolver cr = context.getContentResolver();
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            // inJustDecodeBounds = true: читаю только размеры картинки, не загружая сами пиксели в память
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = cr.openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            // inSampleSize должен быть степенью двойки: подбираю самый большой, при котором сторона всё ещё >= 1600.
            // так картинка сразу декодируется уменьшенной и 12-мегапиксельное фото не роняет приложение по памяти
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2;
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            android.graphics.Bitmap bmp;
            // поток открываю заново - первый уже прочитан до конца при определении размеров
            try (java.io.InputStream in = cr.openInputStream(uri)) {
                bmp = android.graphics.BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) return null;

            // камера часто сохраняет фото "лёжа" и пишет поворот в EXIF. если его не учесть, после сжатия
            // фото на сервере окажется повёрнутым на бок (сжатый JPEG EXIF уже не содержит)
            int rotation = 0;
            try (java.io.InputStream in = cr.openInputStream(uri)) {
                int o = new android.media.ExifInterface(in).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
                if (o == android.media.ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                else if (o == android.media.ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                else if (o == android.media.ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
            } catch (Exception ignored) {
                // нет exif - ну и ладно
            }
            // досжимаю точно до 1600 по длинной стороне (inSampleSize уменьшает только в 2, 4, 8 раз). больше 1 не увеличиваю
            float scale = Math.min(1f, MAX_IMAGE_SIDE / (float) Math.max(bmp.getWidth(), bmp.getHeight()));
            android.graphics.Matrix m = new android.graphics.Matrix();
            if (scale < 1f) m.postScale(scale, scale);
            if (rotation != 0) m.postRotate(rotation);
            // новую картинку делаю только если реально надо масштабировать или поворачивать.
            // старую освобождаю через recycle, чтобы не держать в памяти две большие картинки
            if (!m.isIdentity()) {
                android.graphics.Bitmap t = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (t != bmp) bmp.recycle();
                bmp = t;
            }
            File out = new File(context.getCacheDir(), "upload_" + java.util.UUID.randomUUID() + ".jpg");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos);
            }
            bmp.recycle();
            return out;
        // OutOfMemoryError тоже ловлю: на слабых телефонах огромное фото может не влезть в память
        } catch (Exception | OutOfMemoryError e) {
            return null; // не вышло - отправим оригинал
        }
    }
}

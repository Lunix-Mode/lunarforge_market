package com.lunarforge.market.ui.chat;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.lunarforge.market.R;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.util.BaseActivity;
import com.lunarforge.market.util.MediaCache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// просмотр фото/видео из чата внутри приложения + "Скачать" в галерею (альбом "Lunar Store").
// фото: зум двумя пальцами, перетаскивание, двойной тап - сброс. видео: встроенный плеер с перемоткой
// открывается из чата по нажатию на фото или видео: в интент кладут EXTRA_URL и EXTRA_IS_VIDEO
// наследуюсь от BaseActivity, как и все экраны приложения
public class MediaViewerActivity extends BaseActivity {

    // ключи для extras интента, константами - чтобы чат и этот экран не разошлись в написании
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_IS_VIDEO = "is_video";

    // один фоновый поток для сохранения в галерею: копирование файла нельзя делать в главном потоке,
    // иначе на больших видео интерфейс зависнет. одного потока хватает - сохраняют по одному файлу
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private String url;
    private boolean isVideo;
    private ImageView image;
    private VideoView video;
    private View progress;
    // текущий зум фото (1 = как есть) и последняя точка пальца для перетаскивания
    private float scale = 1f;
    private float lastX, lastY;

    // android 7-9: запись в общую папку требует разрешения. 10+ - не нужно (MediaStore)
    // registerForActivityResult обязательно создавать полем (до onCreate), иначе андроид кинет исключение.
    // после ответа юзера в диалоге разрешения сразу запускаю скачивание или объясняю, почему не вышло
    private final ActivityResultLauncher<String> storagePermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) download();
                else Toast.makeText(this, "Без доступа к памяти сохранить нельзя", Toast.LENGTH_LONG).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // достаю из интента что показывать и навешиваю кнопки "назад" и "скачать"
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_viewer);
        url = getIntent().getStringExtra(EXTRA_URL);
        isVideo = getIntent().getBooleanExtra(EXTRA_IS_VIDEO, false);
        image = findViewById(R.id.viewerImage);
        video = findViewById(R.id.viewerVideo);
        progress = findViewById(R.id.viewerProgress);
        findViewById(R.id.viewerBack).setOnClickListener(v -> finish());
        findViewById(R.id.viewerDownload).setOnClickListener(v -> onDownloadClick());
        // без ссылки показывать нечего - закрываю экран сразу, чтобы не упасть дальше на null
        if (url == null) {
            finish();
            return;
        }
        if (isVideo) showVideo(); else showPhoto();
    }

    // фото грузит Glide по полной ссылке (сервер отдаёт относительный путь /files/...)
    private void showPhoto() {
        image.setVisibility(View.VISIBLE);
        Glide.with(this).load(ApiClient.absoluteUrl(url)).into(image);
        progress.setVisibility(View.GONE);
        setupZoom();
    }

    // видео играем из скачанного файла (быстрый старт и перемотка без подгрузок)
    // MediaCache.get - срочная загрузка (юзер сам открыл видео), а не фоновая очередь
    private void showVideo() {
        video.setVisibility(View.VISIBLE);
        MediaCache.get(this, url, new MediaCache.Ready() {
            @Override
            public void onReady(File file) {
                // колбэк может прийти, когда экран уже закрыли - тогда вьюшки трогать нельзя, иначе вылет
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.GONE);
                // стандартная панель плеера (пауза, перемотка), привязываю её к самому видео
                MediaController controller = new MediaController(MediaViewerActivity.this);
                controller.setAnchorView(video);
                video.setMediaController(controller);
                video.setVideoPath(file.getAbsolutePath());
                // запускаю только когда плеер подготовил файл, иначе start() ничего не сделает.
                // панель показываю на 2.5 секунды, чтобы было видно, что перемотка есть
                video.setOnPreparedListener(mp -> {
                    video.start();
                    controller.show(2500);
                });
            }

            @Override
            public void onError() {
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.GONE);
                Toast.makeText(MediaViewerActivity.this, "Не удалось загрузить видео", Toast.LENGTH_LONG).show();
            }
        });
    }

    // зум двумя пальцами, перетаскивание приближенного, двойной тап - вернуть как было
    private void setupZoom() {
        // щипок двумя пальцами: масштаб от 1 до 5 раз. меньше 1 не даю - картинка бы съёжилась
        ScaleGestureDetector scaler = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                scale = Math.max(1f, Math.min(5f, scale * d.getScaleFactor()));
                image.setScaleX(scale);
                image.setScaleY(scale);
                // вернулись к исходному размеру - сбрасываю и сдвиг, а то картинка осталась бы смещённой
                if (scale == 1f) {
                    image.setTranslationX(0);
                    image.setTranslationY(0);
                }
                return true;
            }
        });
        // отдельный детектор для двойного тапа - плавно возвращаю всё как было за 150 мс
        GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                scale = 1f;
                image.animate().scaleX(1f).scaleY(1f).translationX(0).translationY(0).setDuration(150).start();
                return true;
            }
        });
        // все касания по картинке отдаю обоим детекторам, а перетаскивание одним пальцем считаю сам.
        // беру getRawX/getRawY (координаты экрана), потому что сама картинка двигается и её локальные координаты прыгают
        image.setOnTouchListener((v, e) -> {
            scaler.onTouchEvent(e);
            taps.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = e.getRawX();
                    lastY = e.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    // двигаю только если приближено, палец один и сейчас не идёт щипок
                    if (scale > 1f && e.getPointerCount() == 1 && !scaler.isInProgress()) {
                        // не даём утащить картинку дальше её увеличенных краёв
                        // на сколько увеличенная картинка вылезает за края view с каждой стороны - дальше этого не пускаю
                        float maxX = image.getWidth() * (scale - 1f) / 2f;
                        float maxY = image.getHeight() * (scale - 1f) / 2f;
                        image.setTranslationX(Math.max(-maxX, Math.min(maxX, image.getTranslationX() + e.getRawX() - lastX)));
                        image.setTranslationY(Math.max(-maxY, Math.min(maxY, image.getTranslationY() + e.getRawY() - lastY)));
                    }
                    lastX = e.getRawX();
                    lastY = e.getRawY();
                    break;
                default:
                    break;
            }
            // true - касания обработаны тут, иначе дальше не придут события MOVE
            return true;
        });
    }

    // кнопка "Скачать". на android 9 и ниже сначала проверяю разрешение на запись в память,
    // если его нет - прошу, а скачивание запустится уже из колбэка storagePermission
    private void onDownloadClick() {
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        download();
    }

    // берём файл из кэша (или докачиваем) и копируем в галерею
    private void download() {
        Toast.makeText(this, "Сохраняю…", Toast.LENGTH_SHORT).show();
        MediaCache.get(this, url, new MediaCache.Ready() {
            @Override
            public void onReady(File file) {
                // копирую в фоне, а результат (тост) показываю уже в главном потоке через runOnUiThread
                IO.execute(() -> {
                    boolean ok = saveToGallery(file);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        Toast.makeText(MediaViewerActivity.this, ok ? "Сохранено в галерею (альбом «Lunar Store»)"
                                : "Не удалось сохранить", Toast.LENGTH_LONG).show();
                    });
                });
            }

            @Override
            public void onError() {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(MediaViewerActivity.this, "Не удалось загрузить файл", Toast.LENGTH_LONG).show();
            }
        });
    }

    // подбираю расширение и mime-тип по имени файла, чтобы галерея правильно поняла что это.
    // имя делаю уникальным через текущее время, чтобы второе сохранение не перезаписало первое
    private boolean saveToGallery(File file) {
        String name = file.getName();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : (isVideo ? "mp4" : "jpg");
        String mime = isVideo ? "video/mp4"
                : ext.equals("png") ? "image/png" : ext.equals("webp") ? "image/webp"
                : ext.equals("gif") ? "image/gif" : ext.equals("heic") ? "image/heic" : "image/jpeg";
        String displayName = "LunarStore_" + System.currentTimeMillis() + "." + ext;
        // дальше два пути: новый (MediaStore) для android 10+ и старый (прямо в папку) для 7-9
        return Build.VERSION.SDK_INT >= 29 ? saveModern(file, displayName, mime) : saveLegacy(file, displayName);
    }

    // android 10+: через MediaStore, без разрешений
    private boolean saveModern(File file, String displayName, String mime) {
        // записываю в общую коллекцию через ContentResolver: сначала создаю пустую запись с именем, типом и папкой
        ContentResolver cr = getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                (isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/Lunar Store");
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1); // пока пишем - галерея файл не показывает
        // видео кладу в коллекцию видео (папка Movies), фото - в картинки (Pictures)
        Uri collection = isVideo
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        // insert вернул null - система не дала создать файл
        Uri item = cr.insert(collection, cv);
        if (item == null) return false;
        // try-with-resources сам закроет оба потока, даже если копирование упадёт
        try (InputStream in = new FileInputStream(file); OutputStream out = cr.openOutputStream(item)) {
            if (out == null) return false;
            copy(in, out);
        } catch (Exception e) {
            // не скопировалось - удаляю недописанную запись, чтобы в галерее не висел битый файл
            cr.delete(item, null, null);
            return false;
        }
        // файл дописан - снимаю IS_PENDING, и теперь галерея его видит
        cv.clear();
        cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(item, cv, null, null);
        return true;
    }

    // android 7-9: в общую папку + сообщаем галерее о новом файле
    @SuppressWarnings("deprecation") // getExternalStoragePublicDirectory устарел с 10, но там мы идём другим путём
    private boolean saveLegacy(File file, String displayName) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), "Lunar Store");
        // папки "Lunar Store" ещё нет - создаю. не создалась - сохранить некуда
        if (!dir.exists() && !dir.mkdirs()) return false;
        File target = new File(dir, displayName);
        try (InputStream in = new FileInputStream(file); OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        } catch (Exception e) {
            return false;
        }
        // без сканирования файл лежит в папке, но в галерее не появится, пока телефон сам не пересканирует память
        android.media.MediaScannerConnection.scanFile(this, new String[]{target.getAbsolutePath()}, null, null);
        return true;
    }

    // обычное копирование кусками по 64 КБ, чтобы не грузить весь файл в память разом
    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (video.isPlaying()) video.pause(); // ушли с экрана - звук не должен играть
    }

    // освобождаю плеер, когда экран закрывается совсем
    @Override
    protected void onDestroy() {
        super.onDestroy();
        video.stopPlayback();
    }
}

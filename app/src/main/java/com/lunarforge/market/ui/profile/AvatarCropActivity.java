package com.lunarforge.market.ui.profile;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import com.lunarforge.market.R;
import com.lunarforge.market.util.BaseActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

// экран обрезки аватарки. на вход uri фото из галереи, на выход путь к готовому jpg 640x640.
// открывается из профиля через startActivityForResult, путь отдаю обратно в EXTRA_RESULT_PATH,
// а загрузку на сервер делает уже экран профиля
public class AvatarCropActivity extends BaseActivity {

    public static final String EXTRA_RESULT_PATH = "result_path";
    private static final int OUT_SIZE = 640;       // итоговый аватар
    private static final int DECODE_MIN_SIDE = 1400; // грузим фото с запасом, чтобы при зуме не мылило

    // своя вьюшка, в ней фото можно двигать и зумить пальцами, а crop() вырезает квадрат
    private AvatarCropView cropView;
    // пока фото не загрузилось, кнопка "готово" ничего не делает
    private boolean ready = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_avatar_crop);
        cropView = findViewById(R.id.cropView);
        findViewById(R.id.cancelCropButton).setOnClickListener(v -> finish());
        findViewById(R.id.doneCropButton).setOnClickListener(v -> done());

        // uri картинки кладут в data интента. если его нет - делать тут нечего
        Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        // большое фото декодим не в главном потоке, иначе экран подвисает
        new Thread(() -> {
            Bitmap bmp = decode(uri);
            // setBitmap и Toast можно делать только в главном потоке, поэтому возвращаюсь через runOnUiThread
            runOnUiThread(() -> {
                // пока декодилось, экран могли закрыть - тогда ничего не трогаю, иначе краш
                if (isFinishing() || isDestroyed()) return;
                if (bmp == null) {
                    Toast.makeText(this, "Не получилось открыть фото", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                cropView.setBitmap(bmp);
                ready = true;
            });
        }).start();
    }

    // нажали "готово": вырезаю квадрат 640x640, сохраняю jpg в кэш приложения и возвращаю путь
    private void done() {
        if (!ready) return;
        Bitmap out = cropView.crop(OUT_SIZE);
        if (out == null) return;
        // имя файла с uuid, чтобы новый аватар не перезаписал старый файл, который ещё может грузиться
        File file = new File(getCacheDir(), "avatar_" + UUID.randomUUID() + ".jpg");
        // try-with-resources сам закроет поток файла, даже если будет ошибка
        try (FileOutputStream fos = new FileOutputStream(file)) {
            // 92 - качество jpeg, почти без потерь, но файл заметно меньше png
            out.compress(Bitmap.CompressFormat.JPEG, 92, fos);
        } catch (Exception e) {
            Toast.makeText(this, "Не получилось сохранить", Toast.LENGTH_SHORT).show();
            return;
        }
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT_PATH, file.getAbsolutePath()));
        finish();
    }

    // читаем фото уменьшенным только до разумного размера + поворачиваем по EXIF
    // (фотки с камеры часто лежат "на боку" и поворачиваются только тегом)
    private Bitmap decode(Uri uri) {
        try {
            // первый проход: inJustDecodeBounds = true читает только размеры фото, саму картинку в память не грузит
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            // inSampleSize уменьшает фото в 2, 4, 8... раз при декоде. подбираю максимальный, при котором
            // меньшая сторона всё ещё не меньше 1400 - иначе 12-мегапиксельное фото съело бы кучу памяти
            int sample = 1;
            int minSide = Math.min(bounds.outWidth, bounds.outHeight);
            while (minSide / (sample * 2) >= DECODE_MIN_SIDE) sample *= 2;

            // второй проход: уже грузим картинку с выбранным уменьшением. поток открываю заново, старый уже прочитан
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) return null;

            // третий раз открываю поток - читаю тег поворота из exif
            int rotation = 0;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (o == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                else if (o == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                else if (o == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
            } catch (Exception ignored) {
                // нет exif - ну и ладно
            }
            // поворачиваю саму картинку матрицей, чтобы дальше она была уже "правильная"
            if (rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotation);
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            }
            return bmp;
        // OutOfMemoryError ловлю отдельно - на слабых телефонах огромное фото может не влезть в память,
        // лучше показать "не получилось открыть", чем упасть
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}

package com.lunarforge.market.ui.profile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

// обрезка аватарки: фото под затемнением с круглым окошком.
// один палец - двигать, два - зум. фото всегда закрывает круг целиком (пустых краёв не бывает)
// своя View, без сторонних библиотек: экран кладёт сюда фото через setBitmap, а по кнопке "готово"
// забирает квадрат через crop(...)
public class AvatarCropView extends View {

    private static final float MAX_ZOOM = 6f; // во сколько раз можно приблизить от минимума

    private Bitmap bitmap;
    private float scale = 1f, minScale = 1f;  // масштаб картинки на экране
    private float tx, ty;                     // где на экране левый верхний угол картинки
    private float cx, cy, radius;             // круг

    private final Matrix matrix = new Matrix();
    // FILTER_BITMAP_FLAG - сглаживание при масштабировании, иначе фото будет в "лесенку"
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path dimPath = new Path();

    // стандартный андроидовский распознаватель щипка двумя пальцами
    private final ScaleGestureDetector scaleDetector;
    private float lastX, lastY;
    // id пальца, которым тащим. по id, а не по индексу - индексы меняются, когда пальцы добавляются/убираются
    private int activePointer = -1;

    // конструктор с AttributeSet - чтобы View можно было объявить прямо в xml разметке
    public AvatarCropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // чёрный с прозрачностью ~70% для затемнения вокруг круга
        dimPaint.setColor(0xB3000000);
        ringPaint.setColor(Color.WHITE);
        ringPaint.setStyle(Paint.Style.STROKE);
        // толщина обводки 2dp, перевожу в пиксели через density
        ringPaint.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomAround(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
    }

    // поставить фото для обрезки и сразу вписать его в круг
    public void setBitmap(Bitmap bmp) {
        bitmap = bmp;
        fitToCircle();
        invalidate();
    }

    // тут View узнаёт свой размер - считаю центр и радиус круга и готовлю путь затемнения
    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        // отступ 24dp от краёв, чтобы круг не прилипал к границам экрана
        float margin = 24 * getResources().getDisplayMetrics().density;
        cx = w / 2f;
        cy = h / 2f;
        radius = Math.min(w, h) / 2f - margin;
        dimPath.reset();
        dimPath.addRect(0, 0, w, h, Path.Direction.CW);
        dimPath.addCircle(cx, cy, radius, Path.Direction.CW);
        dimPath.setFillType(Path.FillType.EVEN_ODD); // прямоугольник минус круг = затемнение вокруг
        // фото могли поставить раньше, чем View получила размер - тогда вписываю его сейчас
        fitToCircle();
    }

    // старт: минимальный масштаб, при котором короткая сторона фото = диаметр круга, фото по центру
    private void fitToCircle() {
        // нет фото или размер ещё не известен - считать не из чего
        if (bitmap == null || radius <= 0) return;
        minScale = (2 * radius) / Math.min(bitmap.getWidth(), bitmap.getHeight());
        scale = minScale;
        tx = cx - bitmap.getWidth() * scale / 2f;
        ty = cy - bitmap.getHeight() * scale / 2f;
    }

    // зум вокруг точки между пальцами - она остаётся на месте, как в галерее
    private void zoomAround(float factor, float fx, float fy) {
        // ограничиваю масштаб: не меньше минимума (иначе в круге будут дыры) и не больше MAX_ZOOM
        float newScale = Math.max(minScale, Math.min(minScale * MAX_ZOOM, scale * factor));
        float k = newScale / scale;
        // сдвигаю угол картинки так, чтобы точка под пальцами осталась на том же месте экрана
        tx = fx - (fx - tx) * k;
        ty = fy - (fy - ty) * k;
        scale = newScale;
        clamp();
        invalidate();
    }

    // не даём утащить фото так, чтобы в круге появилась пустота
    private void clamp() {
        if (bitmap == null) return;
        float w = bitmap.getWidth() * scale, h = bitmap.getHeight() * scale;
        // левый край фото не правее левого края круга, правый край не левее правого края круга
        tx = Math.min(cx - radius, Math.max(cx + radius - w, tx));
        ty = Math.min(cy - radius, Math.max(cy + radius - h, ty));
    }

    // обработка касаний: сначала отдаю событие детектору щипка, потом сам обрабатываю перетаскивание
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // первый палец коснулся - запоминаю его и точку
                activePointer = e.getPointerId(0);
                lastX = e.getX();
                lastY = e.getY();
                break;
            case MotionEvent.ACTION_POINTER_UP: {
                // отпустили один из двух пальцев - продолжаем тащить оставшимся, без рывка
                int up = e.getActionIndex();
                if (e.getPointerId(up) == activePointer) {
                    int keep = up == 0 ? 1 : 0;
                    activePointer = e.getPointerId(keep);
                    lastX = e.getX(keep);
                    lastY = e.getY(keep);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                int i = e.findPointerIndex(activePointer);
                if (i < 0) break;
                float x = e.getX(i), y = e.getY(i);
                // во время щипка не двигаю - иначе фото дёргается от двух жестов сразу
                if (!scaleDetector.isInProgress()) {
                    tx += x - lastX;
                    ty += y - lastY;
                    clamp();
                    invalidate();
                }
                lastX = x;
                lastY = y;
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activePointer = -1;
                break;
        }
        // true - говорю системе, что касания мои, иначе MOVE просто не придут
        return true;
    }

    // рисую в три слоя: фото, затемнение с дыркой, белое кольцо
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap != null) {
            // матрица = масштаб + сдвиг, так одним вызовом рисую фото в нужном месте
            matrix.setScale(scale, scale);
            matrix.postTranslate(tx, ty);
            canvas.drawBitmap(bitmap, matrix, bitmapPaint);
        }
        canvas.drawPath(dimPath, dimPaint);
        canvas.drawCircle(cx, cy, radius, ringPaint);
    }

    // вырезаем квадрат вокруг круга ИЗ ОРИГИНАЛА (а не из того что на экране) - поэтому не мыльно
    public Bitmap crop(int outSize) {
        if (bitmap == null) return null;
        // перевожу экранные координаты квадрата вокруг круга в координаты оригинальной картинки:
        // вычитаю сдвиг и делю на масштаб
        float left = (cx - radius - tx) / scale;
        float top = (cy - radius - ty) / scale;
        float size = (2 * radius) / scale;
        Rect src = new Rect(Math.round(left), Math.round(top), Math.round(left + size), Math.round(top + size));
        // из-за округления рамка может вылезти на пиксель за фото - подрезаю по границам картинки
        src.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        // результат - квадрат outSize x outSize, круглым его делает уже Glide при показе
        Bitmap out = Bitmap.createBitmap(outSize, outSize, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(bitmap, src, new RectF(0, 0, outSize, outSize), bitmapPaint);
        return out;
    }
}

package com.lunarforge.market.ui.chat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

// кольцо вокруг кружка как в телеге: показывает, сколько проиграно, и по нему можно перематывать пальцем.
// касание в центре круга кольцо не забирает - оно уходит кружку (там пауза/продолжить)
// своя View, рисую всё сам на Canvas. в ChatMessageAdapter её кладут поверх видео-кружка и связывают с плеером:
// плеер зовёт setProgress, а кольцо при перемотке зовёт onSeek
public class NoteRingView extends View {

    // колбэк наружу - куда перемотать
    public interface OnSeek {
        void onSeek(float fraction); // 0..1 от начала
    }

    // серый фон кольца (вся дорожка)
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    // белая дуга - сколько уже проиграно
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    // белая точка-ползунок на конце дуги
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    // прямоугольник в который вписана дуга, создаю один раз, а не в onDraw - там нельзя плодить объекты, он вызывается очень часто
    private final RectF oval = new RectF();
    private final float density;
    // доля проигранного 0..1
    private float progress = 0f;
    // true пока палец держит кольцо
    private boolean seeking = false;
    private OnSeek onSeek;
    // как в телеге: пока кружок играет, кольца не видно. появляется, когда тянешь пальцем или на паузе
    private boolean autoHide = false;
    // прячу плавно через задержку, Runnable храню в поле чтобы можно было его отменить
    private final Runnable hideLater = () -> animate().alpha(0f).setDuration(200).start();

    public NoteRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // density - чтобы толщина линий в dp одинаково смотрелась на разных экранах
        density = getResources().getDisplayMetrics().density;
        float stroke = 4 * density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        // полупрозрачный белый
        track.setColor(0x55FFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(stroke);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(0xFFFFFFFF);
        knob.setColor(0xFFFFFFFF);
        // лёгкая тень у ползунка, чтобы его было видно на светлом видео
        knob.setShadowLayer(3 * density, 0, 0, 0x66000000);
    }

    // true - кружок играет: кольцо прячем (касаться его всё равно можно), false - пауза: показываем
    public void setAutoHide(boolean hide) {
        autoHide = hide;
        // убираю отложенное скрытие и текущую анимацию, иначе они могут перебить новое состояние
        removeCallbacks(hideLater);
        animate().cancel();
        if (hide) animate().alpha(0f).setDuration(200).start();
        else setAlpha(1f);
    }

    // сюда адаптер чата передаёт, что делать при перемотке (перемотать плеер)
    public void setOnSeekListener(OnSeek listener) {
        this.onSeek = listener;
    }

    // плеер обновляет прогресс ~20 раз в секунду; пока палец тащит ползунок - не мешаем ему
    public void setProgress(float fraction) {
        if (seeking) return;
        // на всякий случай зажимаю в 0..1, invalidate - попросить перерисовать
        progress = Math.max(0f, Math.min(1f, fraction));
        invalidate();
    }

    // радиус кольца: половина меньшей стороны минус отступ, чтобы толщина линии и ползунок не обрезались по краю
    private float radius() {
        return Math.min(getWidth(), getHeight()) / 2f - 8 * density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // центр view и радиус
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = radius();
        oval.set(cx - r, cy - r, cx + r, cy + r);
        // сначала вся дорожка, поверх неё дуга прогресса
        canvas.drawCircle(cx, cy, r, track);
        canvas.drawArc(oval, -90, 360 * progress, false, arc); // от "12 часов" по часовой
        // угол в радианах для позиции ползунка
        double a = 2 * Math.PI * progress;
        // sin по x и -cos по y - потому что отсчёт идёт от верхней точки, а не от правой как обычно в математике
        canvas.drawCircle(cx + (float) (r * Math.sin(a)), cy - (float) (r * Math.cos(a)), 6 * density, knob);
    }

    @Override
    // обработка пальца: перемотка только если тронули около кольца
    public boolean onTouchEvent(MotionEvent e) {
        // слушателя нет - касания вообще не беру, пусть идут дальше
        if (onSeek == null) return false;
        // расстояние от центра до пальца - по нему понимаю, попали в кольцо или в середину
        float dx = e.getX() - getWidth() / 2f, dy = e.getY() - getHeight() / 2f;
        float dist = (float) Math.hypot(dx, dy), r = radius();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // берём только касание у самого кольца; центр круга - кружку (пауза)
                // false значит касание не моё, android отдаст его view под кольцом (самому кружку)
                if (dist < r - 28 * density || dist > r + 16 * density) return false;
                seeking = true;
                removeCallbacks(hideLater);
                animate().cancel();
                setAlpha(1f); // начали перематывать - показываем кольцо
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true); // список не листается
                seekTo(dx, dy);
                return true;
            case MotionEvent.ACTION_MOVE:
                // тащим палец - перематываем следом
                if (seeking) seekTo(dx, dy);
                return seeking;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // отпустили палец - финальная перемотка и возвращаю списку право листать
                if (seeking) {
                    seekTo(dx, dy);
                    seeking = false;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    if (autoHide) postDelayed(hideLater, 700); // отпустили - через миг снова прячем
                }
                return true;
            default:
                return seeking;
        }
    }

    // atan2(dx, -dy) даёт угол от верхней точки по часовой, от -pi до pi
    // угол пальца -> доля: 0 наверху, по часовой до 1
    private void seekTo(float dx, float dy) {
        double angle = Math.atan2(dx, -dy);
        // отрицательный угол (левая половина) перевожу в 0..2pi
        if (angle < 0) angle += 2 * Math.PI;
        progress = (float) (angle / (2 * Math.PI));
        invalidate();
        onSeek.onSeek(progress); // перематываем сразу, пока ведёшь пальцем
    }
}

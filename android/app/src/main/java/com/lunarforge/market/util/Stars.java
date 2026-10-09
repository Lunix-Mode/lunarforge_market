package com.lunarforge.market.util;

import android.content.res.ColorStateList;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import com.lunarforge.market.R;

// звёзды рейтинга (скруглённые, ic_star_round). render - просто показать, picker - выбрать тапом
// используется в отзывах (показ оценки) и в окне "оставить отзыв" (выбор оценки). row - пустой горизонтальный LinearLayout из разметки
public final class Stars {
    // колбэк: юзер тапнул на звезду, score = 1..5
    public interface OnPick {
        void onPick(int score);
    }

    private Stars() {}

    // просто показать оценку (кликать нельзя)
    public static void render(LinearLayout row, int score, int sizeDp) {
        build(row, score, sizeDp, null);
    }

    // выбор оценки: initial - сколько звёзд закрашено сразу (например старая оценка при редактировании)
    public static void picker(LinearLayout row, int initial, int sizeDp, OnPick onPick) {
        build(row, initial, sizeDp, onPick);
    }

    // общий код: создаёт 5 звёзд заново, onPick == null значит звёзды не кликаются
    private static void build(LinearLayout row, int score, int sizeDp, OnPick onPick) {
        // сначала удаляю старые звёзды, иначе при повторном вызове их станет 10
        row.removeAllViews();
        // размер задаю в dp, а в LayoutParams нужны пиксели - умножаю на плотность экрана
        float d = row.getResources().getDisplayMetrics().density;
        int size = (int) (sizeDp * d);
        // зазор между звёздами ~1/8 размера, но не меньше 2dp
        int gap = (int) (Math.max(2, sizeDp / 8f) * d);
        for (int i = 1; i <= 5; i++) {
            ImageView star = new ImageView(row.getContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            star.setLayoutParams(lp);
            star.setImageResource(R.drawable.ic_star_round);
            // для TalkBack (озвучка для слабовидящих)
            star.setContentDescription(i + " из 5");
            row.addView(star);
            if (onPick != null) {
                // копия i в final переменную - в лямбду нельзя передать изменяемую i из цикла
                final int value = i;
                star.setOnClickListener(v -> {
                    // сразу перекрашиваю, чтобы юзер видел выбор без перерисовки всего ряда
                    tint(row, value);
                    onPick.onPick(value);
                });
            }
        }
        tint(row, score);
    }

    // картинка одна и та же, меняется только tint (цвет): первые score звёзд жёлтые, остальные серые.
    // цвета из ресурсов, поэтому в тёмной теме подстраиваются сами
    private static void tint(LinearLayout row, int score) {
        int on = ContextCompat.getColor(row.getContext(), R.color.c_star);
        int off = ContextCompat.getColor(row.getContext(), R.color.c_divider);
        for (int i = 0; i < row.getChildCount(); i++) {
            ((ImageView) row.getChildAt(i)).setImageTintList(ColorStateList.valueOf(i < score ? on : off));
        }
    }
}

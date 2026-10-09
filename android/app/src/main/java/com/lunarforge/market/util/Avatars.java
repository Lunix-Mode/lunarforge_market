package com.lunarforge.market.util;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.lunarforge.market.api.ApiClient;

import java.util.Calendar;

// аватарки. фото нет - круг в цветах текущего месяца + первая буква ника
// (октябрь оранжевый, июнь солнечный и т.д.), рисуется кодом, поэтому чёткий на любом размере.
// фото есть - грузим кругом, а пока грузится (или если не загрузилось) - тот же сезонный круг
// пользуюсь везде одной строкой: Avatars.load(imageView, url, nickname) - в чатах, профиле, отзывах
public final class Avatars {

    // {светлый, тёмный, цвет буквы}, январь..декабрь.
    // каждый сезон - в гамме цвета темы «Авто по временам года» (зима синий, весна зелёный, лето бирюза,
    // осень оранжевый), но три месяца внутри сезона разные. где белая буква читается хуже 2:1 - буква тёмная в тон
    private static final int[][] MONTH_COLORS = {
            {0xFF93C5FD, 0xFF1D4ED8, 0xFFFFFFFF}, // январь    - зима: ледяной синий
            {0xFFBFDBFE, 0xFF2563EB, 0xFFFFFFFF}, // февраль   - зима: зимняя лазурь
            {0xFFA7F3D0, 0xFF059669, 0xFFFFFFFF}, // март      - весна: мята
            {0xFFBBF7D0, 0xFF16A34A, 0xFF14532D}, // апрель    - весна: свежая зелень (тёмная буква)
            {0xFFC6F6B5, 0xFF2F9E44, 0xFF14532D}, // май       - весна: молодая листва (тёмная буква)
            {0xFFA5F3FC, 0xFF0E7490, 0xFFFFFFFF}, // июнь      - лето: лазурная вода
            {0xFF67E8F9, 0xFF0891B2, 0xFFFFFFFF}, // июль      - лето: морская бирюза
            {0xFF99F6E4, 0xFF0F766E, 0xFFFFFFFF}, // август    - лето: тёплая лагуна
            {0xFFFCD34D, 0xFFB45309, 0xFFFFFFFF}, // сентябрь  - осень: золото
            {0xFFFDBA74, 0xFFEA580C, 0xFFFFFFFF}, // октябрь   - осень: тыквенный оранжевый
            {0xFFFED7AA, 0xFF9A3412, 0xFFFFFFFF}, // ноябрь    - осень: ржавая листва
            {0xFFDBEAFE, 0xFF1E40AF, 0xFFFFFFFF}, // декабрь   - зима: снежное небо
    };

    // утилитный класс, объекты создавать незачем - конструктор закрыт
    private Avatars() {}

    // главный метод: показать аватарку в ImageView
    public static void load(ImageView view, @Nullable String url, @Nullable String nickname) {
        // экран уже закрыт - Glide на уничтоженном экране роняет приложение, поэтому просто выходим
        android.app.Activity a = activityOf(view.getContext());
        if (a != null && (a.isFinishing() || a.isDestroyed())) return;
        Drawable fallback = seasonal(nickname);
        if (url == null || url.isEmpty()) {
            Glide.with(view).clear(view); // строку могли переиспользовать - убираем старое фото
            view.setImageDrawable(fallback);
            return;
        }
        // сервер отдаёт относительный путь (/files/...), absoluteUrl дописывает адрес сервера.
        // circleCrop - обрезка кругом. placeholder - пока грузится, error - если не загрузилось,
        // fallback - если урл оказался null. во всех трёх случаях показываю букву в круге
        Glide.with(view).load(ApiClient.absoluteUrl(url)).circleCrop()
                .placeholder(fallback).error(fallback).fallback(fallback)
                .into(view);
    }

    // круг с буквой без фото. публичный - его можно поставить и просто как картинку
    public static Drawable seasonal(@Nullable String nickname) {
        // Calendar.MONTH считается с нуля (январь = 0), как раз индекс в массиве
        int[] c = MONTH_COLORS[Calendar.getInstance().get(Calendar.MONTH)];
        String n = nickname == null ? "" : nickname.trim();
        // первая "буква" целиком, даже если это эмодзи из двух char
        String letter = n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase();
        return new LetterCircle(c[0], c[1], c[2], letter);
    }

    // свой Drawable: круг с градиентом и буквой по центру. рисуется на canvas под любой размер,
    // поэтому не нужно хранить картинки-заглушки в ресурсах
    private static final class LetterCircle extends Drawable {
        private final int light, dark;
        private final String letter;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        LetterCircle(int light, int dark, int letterColor, String letter) {
            this.light = light;
            this.dark = dark;
            this.letter = letter;
            text.setColor(letterColor);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextAlign(Paint.Align.CENTER);
            // лёгкая тень - только под белой буквой, тёмную она бы размазывала
            if (letterColor == 0xFFFFFFFF) text.setShadowLayer(2f, 0f, 1f, 0x33000000);
        }

        // вызывается когда Drawable узнаёт свой размер - тут пересоздаю градиент и размер шрифта,
        // чтобы не делать это в draw на каждый кадр
        @Override
        protected void onBoundsChange(@NonNull Rect b) {
            super.onBoundsChange(b);
            // диагональный градиент: сверху-слева светлее, снизу-справа насыщеннее
            fill.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom, light, dark, Shader.TileMode.CLAMP));
            // буква примерно на 44% от размера круга - на глаз так смотрится лучше
            text.setTextSize(Math.min(b.width(), b.height()) * 0.44f);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float r = Math.min(b.width(), b.height()) / 2f;
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, fill);
            float y = b.exactCenterY() - (text.descent() + text.ascent()) / 2f; // по центру по вертикали
            canvas.drawText(letter, b.exactCenterX(), y, text);
        }

        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); text.setAlpha(alpha); }
        @Override public void setColorFilter(@Nullable ColorFilter cf) { fill.setColorFilter(cf); }
        // getOpacity устарел с Android 10, но он абстрактный - переопределять обязаны. глушим предупреждение осознанно
        @SuppressWarnings("deprecation")
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // достаю Activity из контекста вьюшки. контекст часто обёрнут (например ContextThemeWrapper),
    // поэтому разворачиваю обёртки по одной, пока не дойду до Activity. не нашёл - null
    @Nullable
    private static android.app.Activity activityOf(android.content.Context c) {
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof android.app.Activity) return (android.app.Activity) c;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}

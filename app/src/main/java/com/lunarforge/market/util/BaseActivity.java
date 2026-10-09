package com.lunarforge.market.util;

import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

// от него наследуются все экраны.
// новые андроиды (15+) рисуют приложение под статусбаром и полоской жестов,
// поэтому добавляем отступы. ime - чтобы поле ввода в чате не пряталось под клавиатурой
public class BaseActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // тему (светлая/тёмная/цветовая схема) ставлю до super.onCreate - после того как окно создано, менять поздно
        setTheme(ThemeManager.currentStyleRes(this));
        // рисуем на весь экран, включая область под системными панелями; отступы добавляю ниже сам
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    // переопределил setContentView, чтобы отступы навешивались автоматически в каждом экране,
    // и не надо было копировать этот код в каждую активити
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        View content = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            // снизу берём большее: когда клавиатура открыта, она выше полоски жестов
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            // CONSUMED - отступы уже учли, дочерним вьюшкам их не передаём, иначе отступ добавится дважды
            return WindowInsetsCompat.CONSUMED;
        });
    }

    // фон окна под статусбаром/полоской делаю цветом поверхности - для экранов, где шапка такого же цвета,
    // чтобы сверху не было полосы другого цвета
    protected void useSurfaceColorBehindSystemBars() {
        getWindow().setBackgroundDrawableResource(com.lunarforge.market.R.color.c_surface);
    }
}

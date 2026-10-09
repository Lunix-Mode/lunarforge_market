package com.lunarforge.market.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.lunarforge.market.R;

// светлая/тёмная (по умолчанию как в телефоне) + цвет акцента, всё хранится в prefs
// всё статическое, объект не создаю. ночной режим ставится в LunarApp при старте,
// а акцентная тема в BaseActivity через setTheme до создания экрана
public class ThemeManager {
    // имя файла настроек и ключи в нём
    private static final String PREFS = "lunarforge_theme";
    private static final String KEY_THEME = "accent_theme";
    private static final String KEY_NIGHT = "night_mode";

    // варианты ночного режима: подпись для настроек + режим AppCompatDelegate, который реально переключает тему
    public enum NightMode {
        SYSTEM("Как в системе", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("Светлая", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO),
        DARK("Тёмная", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);

        public final String label;
        public final int delegateMode;

        NightMode(String label, int delegateMode) {
            this.label = label;
            this.delegateMode = delegateMode;
        }
    }

    // читаю сохранённый режим. try - если в prefs осталось старое/кривое имя, valueOf кинет исключение, тогда беру "как в системе"
    public static NightMode getNightMode(Context context) {
        try {
            return NightMode.valueOf(prefs(context).getString(KEY_NIGHT, NightMode.SYSTEM.name()));
        } catch (IllegalArgumentException e) {
            return NightMode.SYSTEM;
        }
    }

    // сохраняю выбор и сразу применяю, android сам пересоздаст открытые экраны
    public static void setNightMode(Context context, NightMode mode) {
        prefs(context).edit().putString(KEY_NIGHT, mode.name()).apply();
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode.delegateMode);
    }

    // вызывается при старте приложения, чтобы с первого экрана была нужная тема
    public static void applySavedNightMode(Context context) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(getNightMode(context).delegateMode);
    }

    // цвет акцента: подпись, стиль из themes.xml и hex для кружочка-образца в настройках
    public enum AccentTheme {
        BLUE("Синяя", R.style.Theme_LunarForgeMarket, "#287BFF"),
        PURPLE("Фиолетовая", R.style.Theme_LunarForgeMarket_Purple, "#8B5CF6"),
        GREEN("Зелёная", R.style.Theme_LunarForgeMarket_Green, "#16A34A"),
        PINK("Розовая", R.style.Theme_LunarForgeMarket_Pink, "#EC4899"),
        TEAL("Бирюзовая", R.style.Theme_LunarForgeMarket_Teal, "#0891B2"),
        ORANGE("Оранжевая", R.style.Theme_LunarForgeMarket_Orange, "#EA580C"),
        // сам меняется по сезону: зима синий, весна зелёный, лето бирюза, осень оранжевый.
        // стиль и цвет у него свои не хранятся - берутся у сезонной темы (см. seasonNow)
        SEASONAL("Авто по временам года", 0, null);

        public final String label;
        public final int styleRes;
        public final String swatchHex;

        AccentTheme(String label, int styleRes, String swatchHex) {
            this.label = label;
            this.styleRes = styleRes;
            this.swatchHex = swatchHex;
        }
    }

    // текущий акцент, по умолчанию синий. так же как с ночным режимом защищаюсь от неизвестного имени
    public static AccentTheme getCurrent(Context context) {
        String name = prefs(context).getString(KEY_THEME, AccentTheme.BLUE.name());
        try {
            return AccentTheme.valueOf(name);
        } catch (IllegalArgumentException e) {
            return AccentTheme.BLUE;
        }
    }

    // только сохраняю - применится когда экран пересоздастся (setTheme работает только до setContentView)
    public static void setCurrent(Context context, AccentTheme theme) {
        prefs(context).edit().putString(KEY_THEME, theme.name()).apply();
    }

    // какой стиль ставить в setTheme. у сезонной темы своего стиля нет (0), поэтому подставляю тему текущего сезона
    public static int currentStyleRes(Context context) {
        AccentTheme t = getCurrent(context);
        return (t == AccentTheme.SEASONAL ? seasonNow() : t).styleRes;
    }

    // какой цвет сейчас по сезону. меняется при следующем открытии экрана после смены сезона
    public static AccentTheme seasonNow() {
        int month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH); // 0 = январь
        if (month == 11 || month <= 1) return AccentTheme.BLUE;   // зима
        if (month <= 4) return AccentTheme.GREEN;                 // весна
        if (month <= 7) return AccentTheme.TEAL;                  // лето
        return AccentTheme.ORANGE;                                // осень
    }

    // подпись для настроек, чтобы было понятно какой цвет сейчас выбрал авто-режим
    public static String seasonLabel() {
        switch (seasonNow()) {
            case BLUE: return "сейчас зима ❄️";
            case GREEN: return "сейчас весна 🌱";
            case TEAL: return "сейчас лето ☀️";
            default: return "сейчас осень 🍂";
        }
    }

    // getApplicationContext - чтобы не держать ссылку на активити
    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

package com.lunarforge.market;

import android.app.Application;

import com.lunarforge.market.util.ThemeManager;

// тема (светлая/тёмная) применяется тут, до первого экрана - иначе на старте мелькает не та тема
// это мой класс Application: андроид создаёт его один раз при старте процесса, раньше любой Activity.
// подключён в AndroidManifest.xml через android:name=".LunarApp". сюда кладу только то, что надо сделать один раз на всё приложение
public class LunarApp extends Application {
    @Override
    public void onCreate() {
        // сначала обязательно вызвать родителя, иначе андроид ругнётся
        super.onCreate();
        // читаю из настроек, какую тему выбрал юзер, и ставлю её до того как откроется первый экран
        ThemeManager.applySavedNightMode(this);
        // Glide по умолчанию ждёт картинку всего 2.5 сек - по домашнему wi-fi большие фото не успевали.
        // настраиваем один раз, до первой загрузки картинки
        com.bumptech.glide.Glide.init(this, new com.bumptech.glide.GlideBuilder()
                .setDefaultRequestOptions(new com.bumptech.glide.request.RequestOptions()
                        .set(com.bumptech.glide.load.model.stream.HttpGlideUrlLoader.TIMEOUT, 15_000)));
    }
}

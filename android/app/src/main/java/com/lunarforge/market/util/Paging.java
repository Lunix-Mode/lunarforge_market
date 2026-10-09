package com.lunarforge.market.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

// подгрузка списков порциями.
// размер порции = сколько строк влезает на экран + 50% запаса, а следующую порцию начинаем грузить,
// когда до конца загруженного осталось меньше половины экрана - человек листает и не упирается в "загрузку"
public final class Paging {

    // приватный конструктор - это просто набор статических методов, создавать объект незачем
    private Paging() {}

    // itemHeightDp - примерная высота одной строки списка
    public static int pageSize(Context c, int itemHeightDp) {
        // density - сколько пикселей в одном dp на этом телефоне, чтобы перевести высоту строки в пиксели
        float density = c.getResources().getDisplayMetrics().density;
        int screen = c.getResources().getDisplayMetrics().heightPixels;
        // сколько строк влезает на экран
        int visible = (int) Math.ceil(screen / (itemHeightDp * density));
        // запас 50%, но не меньше 10 и не больше 100 (на сервере тоже потолок 100)
        return Math.max(10, Math.min(100, (int) Math.ceil(visible * 1.5)));
    }

    // листаем вниз и подходим к концу -> loadMore
    public static void onNearEnd(RecyclerView list, Runnable loadMore) {
        // вешаю слушатель прокрутки на список, он срабатывает при каждом сдвиге
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                // dy > 0 - листают вниз. вверх не интересно. и работает только с LinearLayoutManager
                if (dy <= 0 || !(rv.getLayoutManager() instanceof LinearLayoutManager)) return;
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                int first = lm.findFirstVisibleItemPosition(), last = lm.findLastVisibleItemPosition();
                // -1 значит список пустой или ещё не разложен
                if (last < 0) return;
                int visible = last - first + 1;
                // сколько строк осталось ниже последней видимой
                int remaining = lm.getItemCount() - 1 - last;
                // осталось мало - грузим дальше. loadMore может вызваться несколько раз подряд,
                // поэтому на экране должен быть свой флаг "уже грузим", чтобы не слать одинаковые запросы
                if (remaining <= Math.max(2, visible / 2)) loadMore.run();
            }
        });
    }

    // листаем вверх и подходим к началу -> loadOlder (чат: старые сообщения)
    public static void onNearStart(RecyclerView list, Runnable loadOlder) {
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                // тут наоборот: dy < 0 - листают вверх
                if (dy >= 0 || !(rv.getLayoutManager() instanceof LinearLayoutManager)) return;
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                int first = lm.findFirstVisibleItemPosition(), last = lm.findLastVisibleItemPosition();
                if (first < 0) return;
                int visible = last - first + 1;
                // близко к самому верху - подгружаем более старые
                if (first <= Math.max(2, visible / 2)) loadOlder.run();
            }
        });
    }
}

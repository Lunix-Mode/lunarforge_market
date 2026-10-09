package com.lunarforge.market.util;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.lunarforge.market.R;
import com.lunarforge.market.model.Notification;
import com.lunarforge.market.ui.notifications.NotificationsActivity;
import com.lunarforge.market.ui.order.OrderStatusActivity;
import com.lunarforge.market.ui.support.UserTicketActivity;
import com.lunarforge.market.ui.wallet.TransactionDetailActivity;

import java.util.List;

// системные уведомления android + куда вести по нажатию.
// показываем только НОВЫЕ (id больше последнего показанного) - иначе при каждом опросе дубли.
// важно: работает пока приложение запущено. чтобы приходило при закрытом - нужен Firebase (FCM)
public final class Notifier {

    // канал уведомлений (с android 8 без канала уведомление просто не покажется)
    private static final String CHANNEL_ID = "lunar_main";
    // в SharedPreferences храню id последнего показанного уведомления - переживает перезапуск приложения
    private static final String PREFS = "notifier";
    private static final String KEY_LAST_SHOWN = "last_shown_id";

    private Notifier() {}

    // куда вести по уведомлению: заказ / заявка / операция / профиль.
    // null = вести некуда: внутри списка уведомлений тогда ничего не делаем
    // (раньше открывался тот же список -> петля, например у уведомления об отзыве)
    @androidx.annotation.Nullable
    public static Intent intentFor(Context c, Notification n) {
        if (n.refType == null || n.refId == null) return null;
        // refType приходит с сервера (см. Notification на бэке), refId - id нужного объекта
        switch (n.refType) {
            case "ORDER":
                return new Intent(c, OrderStatusActivity.class).putExtra(OrderStatusActivity.EXTRA_ORDER_ID, n.refId.longValue());
            case "TICKET":
                return new Intent(c, UserTicketActivity.class).putExtra(UserTicketActivity.EXTRA_TICKET_ID, n.refId.longValue());
            case "TRANSACTION":
                return new Intent(c, TransactionDetailActivity.class).putExtra(TransactionDetailActivity.EXTRA_TX_ID, n.refId.longValue());
            case "USER":
                // для USER смотрю ещё и на type - назначение/снятие модератора ведут не в профиль
                if ("MODERATOR_APPOINTED".equals(n.type)) {
                    return new Intent(c, com.lunarforge.market.ui.staff.TicketsActivity.class); // сразу к заявкам
                }
                if ("MODERATOR_DEMOTED".equals(n.type)) {
                    return new Intent(c, com.lunarforge.market.ui.support.MyTicketsActivity.class); // можно попросить вернуть
                }
                // отзыв, блокировка/разблокировка - профиль (у отзыва это мой профиль со списком отзывов)
                return new Intent(c, com.lunarforge.market.ui.profile.PublicProfileActivity.class)
                        .putExtra(com.lunarforge.market.ui.profile.PublicProfileActivity.EXTRA_USER_ID, n.refId.longValue());
            default:
                return null;
        }
    }

    // можно ли вообще показывать уведомления.
    // до android 13 разрешение не нужно, смотрим только не выключил ли их юзер в настройках.
    // с 13 (API 33) нужно runtime-разрешение POST_NOTIFICATIONS
    public static boolean canPost(Context c) {
        if (Build.VERSION.SDK_INT < 33) return NotificationManagerCompat.from(c).areNotificationsEnabled();
        return ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    // вызывается после очередного опроса сервера со списком уведомлений,
    // выкидывает в шторку только те, которых ещё не показывали
    @SuppressLint("MissingPermission") // проверяем canPost() выше
    public static void showNew(Context context, List<Notification> list) {
        // беру контекст приложения, а не активити - чтобы не держать ссылку на закрытый экран
        Context c = context.getApplicationContext();
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = p.getLong(KEY_LAST_SHOWN, -1);
        // сразу считаю максимальный id в пачке - его потом и запомню
        long max = last;
        for (Notification n : list) max = Math.max(max, n.id);
        // первый запуск: просто запоминаем, где мы, старые уведомления в шторку не валим.
        // то же самое если показывать нельзя - иначе после выдачи разрешения вывалились бы все старые
        if (last == -1 || !canPost(c)) {
            p.edit().putLong(KEY_LAST_SHOWN, max).apply();
            return;
        }
        ensureChannel(c);
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        for (Notification n : list) {
            // уже показывали или юзер уже прочитал внутри приложения - пропускаю
            if (n.id <= last || n.read) continue;
            Intent target = intentFor(c, n);
            // если конкретного экрана нет - открываю просто список уведомлений
            if (target == null) target = new Intent(c, NotificationsActivity.class);
            // NEW_TASK обязателен, т.к. запускаем не из активити, а из контекста приложения
            Intent intent = target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // requestCode = id уведомления, чтобы у каждого был свой PendingIntent, а не один общий
            // (иначе все уведомления вели бы на последний заказ). IMMUTABLE обязателен с android 12
            PendingIntent pi = PendingIntent.getActivity(c, (int) n.id, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            // BigTextStyle - чтобы длинный текст разворачивался, а не обрезался одной строкой.
            // AutoCancel - уведомление пропадает из шторки после нажатия
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notifier)
                    .setContentTitle(n.title)
                    .setContentText(n.body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(n.body))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            try {
                // id системного уведомления = id с сервера, так одно и то же не задублится в шторке
                nm.notify((int) n.id, b.build());
            } catch (SecurityException ignored) {
                // разрешение отозвали прямо сейчас - просто не показываем
            }
        }
        p.edit().putLong(KEY_LAST_SHOWN, max).apply();
    }

    // создаю канал один раз. на android ниже 8 каналов нет, там сразу выходим
    private static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager m = c.getSystemService(NotificationManager.class);
        if (m != null && m.getNotificationChannel(CHANNEL_ID) == null) {
            // название и описание видны юзеру в настройках уведомлений приложения
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Заказы и сообщения", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Новые заказы, деньги, заявки и решения модераторов");
            m.createNotificationChannel(ch);
        }
    }
}

package com.lunarforge.market.util;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;

import com.lunarforge.market.R;

// мелкие помощники для текста
// final и приватный конструктор - это просто набор статических методов, создавать объект не нужно
public final class Ui {

    private Ui() {}

    // "Продавец: " обычным цветом + "ник" цветом акцента и жирным
    // SpannableStringBuilder позволяет красить кусок строки. запоминаю, где закончилась подпись (start),
    // и крашу с этого места до конца. если значения нет - пишу прочерк, а не "null"
    public static CharSequence labelValue(Context ctx, String label, String value) {
        SpannableStringBuilder sb = new SpannableStringBuilder(label);
        int start = sb.length();
        sb.append(value == null ? "—" : value);
        sb.setSpan(new ForegroundColorSpan(accent(ctx)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    // цвет акцента текущей темы (синий/фиолетовый/...)
    // цвет акцента у меня зависит от выбранной темы, поэтому не беру фиксированный цвет из ресурсов,
    // а достаю атрибут appAccent из текущей темы
    public static int accent(Context ctx) {
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(R.attr.appAccent, tv, true);
        return tv.data;
    }

    // "👑 Создатель Lunix" / "🛡️ Модератор Ник"
    public static String staffLabel(String role, String nickname) {
        return ("ADMIN".equals(role) ? "👑 Создатель " : "🛡️ Модератор ") + nickname;
    }

    // короткие названия типов заявок для списка (на сервере это enum, сюда приходит строкой).
    // если пришёл новый тип, которого я ещё не знаю, - показываю как есть, чтобы не было пустоты
    public static String ticketType(String type) {
        if (type == null) return "";
        switch (type) {
            case "REFUND": return "Возврат";
            case "ORDER_PROBLEM": return "Заказ";
            case "ACCOUNT_PROBLEM": return "Аккаунт";
            case "COMPLAINT": return "Жалоба";
            case "UNBLOCK_APPEAL": return "Разблокировка";
            case "DECISION_APPEAL": return "Обжалование";
            case "MODERATOR_REINSTATEMENT": return "Возврат в модераторы";
            default: return type;
        }
    }

    // статус заявки по-русски. всё что не OPEN и не IN_PROGRESS - считаю закрытой
    public static String ticketStatus(String status) {
        if ("OPEN".equals(status)) return "ждёт модератора";
        if ("IN_PROGRESS".equals(status)) return "в работе";
        return "закрыта";
    }

    // чем закончилась заявка - перевожу код решения в понятный текст для пользователя
    public static String resolution(String r) {
        if (r == null) return "";
        switch (r) {
            case "FULL_REFUND": return "полный возврат";
            case "PARTIAL_REFUND": return "частичный возврат";
            case "NO_REFUND": return "в пользу продавца";
            case "BLOCKED_USER": return "нарушитель заблокирован";
            case "UNBLOCKED": return "разблокирован";
            case "UPHELD": return "решение оставлено";
            case "OVERTURNED": return "решение отменено";
            case "REINSTATED": return "возвращён в модераторы";
            case "SELLER_REFUND": return "возврат за счёт продавца";
            case "COMPENSATED": return "компенсация от площадки";
            default: return "закрыта";
        }
    }

    // "2026-10-05T10:15:30.123Z" -> "только что" / "5 мин назад" / "3 ч назад" / "05.10.2026".
    // без java.time - его нет на Android 7 (minSdk 24), там бы упало
    public static String createdAgo(String iso) {
        long then = parseIso(iso);
        // не смог разобрать дату - показываю хотя бы первые 10 символов (yyyy-MM-dd)
        if (then < 0) return iso == null ? "" : (iso.length() >= 10 ? iso.substring(0, 10) : iso);
        // разница с текущим временем в минутах
        long min = (System.currentTimeMillis() - then) / 60000;
        if (min < 1) return "только что";
        if (min < 60) return min + " мин назад";
        if (min < 24 * 60) return (min / 60) + " ч назад";
        // старше суток - уже просто дата
        return new java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).format(new java.util.Date(then));
    }

    // сервер присылает время в UTC: берём первые 19 символов "yyyy-MM-ddTHH:mm:ss", дробная часть не важна
    public static long parseIso(String iso) {
        if (iso == null || iso.length() < 19) return -1;
        try {
            // Locale.US, чтобы формат не зависел от языка телефона (на некоторых локалях цифры могут быть другими)
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = f.parse(iso.substring(0, 19));
            return d == null ? -1 : d.getTime();
        } catch (java.text.ParseException e) {
            return -1;
        }
    }

    // полные названия типов заявок - для экрана самой заявки, где места больше
    public static String ticketTypeLong(String type) {
        if (type == null) return "";
        switch (type) {
            case "REFUND": return "Возврат средств";
            case "ORDER_PROBLEM": return "Проблема с заказом";
            case "ACCOUNT_PROBLEM": return "Проблема с аккаунтом";
            case "COMPLAINT": return "Жалоба";
            case "UNBLOCK_APPEAL": return "Разблокировка";
            case "DECISION_APPEAL": return "Обжалование решения";
            case "MODERATOR_REINSTATEMENT": return "Возврат в модераторы";
            default: return type;
        }
    }
}

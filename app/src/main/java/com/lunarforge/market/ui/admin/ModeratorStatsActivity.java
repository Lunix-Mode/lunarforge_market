package com.lunarforge.market.ui.admin;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.lunarforge.market.R;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Ticket;
import com.lunarforge.market.ui.staff.TicketActivity;
import com.lunarforge.market.util.ApiErrors;
import com.lunarforge.market.util.BaseActivity;
import com.lunarforge.market.util.Ui;

import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// подробно о модераторе: цифры + все заявки, которые он брал или решал (тап - открыть), + все его блокировки
// открывается из админки, id модератора приходит через Intent (EXTRA_USER_ID).
// разметка тут почти пустая - карточки (TextView) я создаю кодом, потому что их количество заранее неизвестно
public class ModeratorStatsActivity extends BaseActivity {

    public static final String EXTRA_USER_ID = "user_id";
    private LinearLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_moderator_stats);
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        container = findViewById(R.id.statsContainer);
        // -1 если id не передали - тогда сервер просто вернёт ошибку и покажется тост
        long userId = getIntent().getLongExtra(EXTRA_USER_ID, -1);

        // enqueue = запрос в фоне, ответ придёт в колбэк уже в главном потоке, можно сразу трогать вьюшки

        ApiClient.getApiService(this).moderatorStats(userId).enqueue(new Callback<Ticket.ModeratorStats>() {
            @Override
            public void onResponse(Call<Ticket.ModeratorStats> call, Response<Ticket.ModeratorStats> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.body() == null) {
                    Toast.makeText(ModeratorStatsActivity.this, ApiErrors.message(response, "Не удалось загрузить"), Toast.LENGTH_LONG).show();
                    return;
                }
                bind(response.body());
            }

            @Override
            public void onFailure(Call<Ticket.ModeratorStats> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(ModeratorStatsActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    // раскладываю пришедшую статистику по карточкам: статус, рейтинг, цифры, потом списки заявок и блокировок
    private void bind(Ticket.ModeratorStats s) {
        Ticket.Moderator m = s.moderator;
        ((TextView) findViewById(R.id.titleText)).setText(m.nickname);
        // чищу контейнер, чтобы при повторном вызове карточки не задублировались
        container.removeAllViews();

        // статус: действующий, создатель (админ) или снят - и если снят, то когда, автоматом или вручную, и за что

        String status = "MODERATOR".equals(m.role) ? "🛡️ Действующий модератор"
                : "ADMIN".equals(m.role) ? "👑 Создатель"
                : (m.demotedAutomatically ? "Снят автоматически " : "Снят ") + Ui.createdAgo(m.demotedAt)
                        + (m.demotionReason != null ? "\n«" + m.demotionReason + "»" : "");
        card(status + (m.blocked ? "\n⛔ Сейчас заблокирован" : ""), R.color.c_text);

        // цвет рейтинга как светофор: от 70 зелёный, от 40 жёлтый, ниже красный
        int ratingColor = m.rating >= 70 ? R.color.c_success : m.rating >= 40 ? R.color.c_warning : R.color.c_danger;
        TextView rating = card("Рейтинг: " + m.rating + " / 100", ratingColor);
        rating.setTextSize(22);
        card(String.format(Locale.getDefault(),
                "Взял заявок: %d\nРешил: %d · сейчас в работе: %d\nОценки участников: 👍 %d · 👎 %d\n"
                        + "Отменено решений по обжалованию: %d\nБлокировок: %d, из них снято по апелляции: %d",
                m.claimedCount, m.resolvedCount, m.inProgressCount, m.likes, m.dislikes,
                m.overturned, m.blocksIssued, m.blocksReversed), R.color.c_text_soft);

        // списки могут прийти null (gson так делает, если поля нет в json), поэтому везде проверка
        header("Заявки (" + (s.tickets == null ? 0 : s.tickets.size()) + ")");
        if (s.tickets != null) {
            for (Ticket t : s.tickets) {
                String line = "#" + t.id + " · " + Ui.ticketTypeLong(t.type) + " · " + Ui.ticketStatus(t.status)
                        + ("RESOLVED".equals(t.status) ? " · " + Ui.resolution(t.resolution) : "")
                        + "\n" + Ui.createdAgo(t.createdAt) + " · " + t.reporterNickname + ": «" + shorten(t.reason) + "»"
                        + (t.myFeedback != null ? "" : "");
                // тап по заявке открывает её экран, чтобы посмотреть переписку и решение
                TextView row = card(line, R.color.c_text_soft);
                row.setOnClickListener(v -> {
                    Intent i = new Intent(this, TicketActivity.class);
                    i.putExtra(TicketActivity.EXTRA_TICKET_ID, t.id);
                    startActivity(i);
                });
            }
        }

        header("Блокировки (" + (s.blocks == null ? 0 : s.blocks.size()) + ")");
        if (s.blocks != null) {
            for (Ticket.Block b : s.blocks) {
                // liftedAt == null - блокировка ещё действует; снятые по апелляции выделяю жёлтым,
                // это значит модератор заблокировал зря
                String line = b.userNickname + " · " + Ui.createdAgo(b.createdAt) + "\n«" + b.reason + "»"
                        + (b.liftedAt == null ? "\nдействует" : b.reversedByAppeal ? "\n⚠️ снята по апелляции" : "\nснята");
                card(line, b.reversedByAppeal ? R.color.c_warning : R.color.c_text_soft);
            }
        }
    }

    // обрезаю длинную причину до 80 символов, чтобы карточка не растягивалась на пол-экрана
    private String shorten(String s) {
        return s == null ? "" : s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    // заголовок секции. отступы задаю в dp: умножаю на density, иначе на разных экранах размер будет разный
    private void header(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(ContextCompat.getColor(this, R.color.c_text_secondary));
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad / 2, pad * 2, 0, pad);
        container.addView(tv);
    }

    // одна карточка-плашка с текстом. возвращаю её, чтобы снаружи можно было повесить клик или поменять размер шрифта
    private TextView card(String text, int colorRes) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setLineSpacing(0, 1.15f);
        tv.setTextColor(ContextCompat.getColor(this, colorRes));
        tv.setBackgroundResource(R.drawable.rounded_bg);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = pad / 2;
        tv.setLayoutParams(lp);
        container.addView(tv);
        return tv;
    }
}

package com.lunarforge.market.ui.admin;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.lunarforge.market.R;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Ticket;
import com.lunarforge.market.util.ApiErrors;
import com.lunarforge.market.util.BaseActivity;
import com.lunarforge.market.util.Ui;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// сверху поле для id - через него админ назначает нового модератора.
// карточки строю кодом, а не через RecyclerView - модераторов мало, список короткий
// только для админа. "Команда" - действующие с рейтингом, "Снятые" - все бывшие модераторы
// (за рейтинг, за блокировку, вручную). тап по человеку - подробная статистика
public class ModeratorsActivity extends BaseActivity {

    // сюда складываю карточки модераторов
    private LinearLayout container;
    // какая вкладка открыта: false - "Команда", true - "Снятые"
    private boolean demotedTab = false;

    @Override
    // настраиваю шапку, кнопку назначения по id и переключение вкладок
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_moderators);
        ((TextView) findViewById(R.id.titleText)).setText("Модераторы");
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        container = findViewById(R.id.moderatorsContainer);

        EditText idField = findViewById(R.id.userIdEditText);
        // назначить модератора по введённому id
        findViewById(R.id.appointButton).setOnClickListener(v -> {
            String raw = idField.getText().toString().trim();
            // пустое поле - просто ничего не делаю
            if (raw.isEmpty()) return;
            try {
                // parseLong кинет исключение если ввели буквы - ловлю и показываю подсказку
                appoint(Long.parseLong(raw));
                idField.setText("");
            } catch (NumberFormatException e) {
                Toast.makeText(this, "ID - это число", Toast.LENGTH_SHORT).show();
            }
        });
        // при смене вкладки просто меняю флаг и перегружаю список
        findViewById(R.id.tabTeam).setOnClickListener(v -> { demotedTab = false; load(); });
        findViewById(R.id.tabDemoted).setOnClickListener(v -> { demotedTab = true; load(); });
    }

    @Override
    // гружу в onResume, а не в onCreate - вернулся из статистики модератора, а там могли что-то поменять, список свежий
    protected void onResume() {
        super.onResume();
        load();
    }

    // загрузка списка: в зависимости от вкладки дёргаю разный эндпоинт, ответ одинакового вида
    private void load() {
        highlightTabs();
        Call<List<Ticket.Moderator>> call = demotedTab
                ? ApiClient.getApiService(this).demotedModerators()
                : ApiClient.getApiService(this).moderators();
        call.enqueue(new Callback<List<Ticket.Moderator>>() {
            @Override
            public void onResponse(Call<List<Ticket.Moderator>> c, Response<List<Ticket.Moderator>> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.body() == null) {
                    Toast.makeText(ModeratorsActivity.this, ApiErrors.message(response, "Не удалось загрузить"), Toast.LENGTH_LONG).show();
                    return;
                }
                // чищу старые карточки, иначе при каждой перезагрузке список бы дублировался
                container.removeAllViews();
                for (Ticket.Moderator m : response.body()) container.addView(card(m));
                if (response.body().isEmpty()) {
                    container.addView(text(demotedTab ? "Снятых модераторов нет" : "Модераторов пока нет", R.color.c_text_secondary, 15));
                }
            }

            @Override
            public void onFailure(Call<List<Ticket.Moderator>> c, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(ModeratorsActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    // подсвечиваю активную вкладку: фон "выбранного чипа" и контрастный текст, у второй - обычный фон
    private void highlightTabs() {
        TextView team = findViewById(R.id.tabTeam), demoted = findViewById(R.id.tabDemoted);
        team.setBackgroundResource(demotedTab ? R.drawable.bg_search_field : R.drawable.bg_chip_selected);
        demoted.setBackgroundResource(demotedTab ? R.drawable.bg_chip_selected : R.drawable.bg_search_field);
        team.setTextColor(ContextCompat.getColor(this, demotedTab ? R.color.c_text : R.color.c_on_accent));
        demoted.setTextColor(ContextCompat.getColor(this, demotedTab ? R.color.c_on_accent : R.color.c_text));
    }

    // карточка модератора: имя, рейтинг цветом, цифры, для снятых - причина и кнопки
    private LinearLayout card(Ticket.Moderator m) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.rounded_bg);
        // 14dp перевожу в пиксели вручную, раз view создаю кодом, а не в xml
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = pad / 2;
        card.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        // у снятых роль уже USER, поэтому показываю просто ник; у действующих - ник со значком роли
        TextView name = text(demotedTab ? m.nickname : Ui.staffLabel(m.role, m.nickname), R.color.c_text, 17);
        // вес 1 - имя занимает всё свободное место, рейтинг прижимается вправо
        name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        head.addView(name);
        // у админа рейтинга нет, его не оценивают
        if (!"ADMIN".equals(m.role)) {
            // цвет рейтинга: от 70 зелёный, от 40 жёлтый, ниже красный
            int ratingColor = m.rating >= 70 ? R.color.c_success : m.rating >= 40 ? R.color.c_warning : R.color.c_danger;
            head.addView(text(m.rating + "/100", ratingColor, 17));
        }
        card.addView(head);
        card.addView(text("@" + m.username + " · ID " + m.id, R.color.c_text_secondary, 14));
        card.addView(text("Решено " + m.resolvedCount + " · в работе " + m.inProgressCount
                + " · 👍 " + m.likes + " · 👎 " + m.dislikes
                + "\nОтменено решений: " + m.overturned + " · блокировок: " + m.blocksIssued
                + " (снято по апелляции " + m.blocksReversed + ")", R.color.c_text_soft, 14));

        // на вкладке снятых показываю почему сняли и кнопку вернуть
        if (demotedTab) {
            String why = (m.demotedAutomatically ? "Снят автоматически" : "Снят") + " " + Ui.createdAgo(m.demotedAt)
                    + (m.demotionReason != null ? "\n«" + m.demotionReason + "»" : "");
            card.addView(text(why, R.color.c_warning, 14));
            // если человек ещё и заблокирован - кнопка разблокировки, иначе сразу "вернуть в модераторы"
            TextView action = text(m.blocked ? "🔓 Разблокировать" : "🛡️ Вернуть в модераторы", R.color.c_on_accent, 15);
            action.setBackgroundResource(R.drawable.bg_publish_product_button);
            action.setGravity(Gravity.CENTER);
            action.setPadding(pad, pad / 2, pad, pad / 2);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (46 * getResources().getDisplayMetrics().density));
            alp.topMargin = pad / 2;
            action.setLayoutParams(alp);
            // заблокированного сначала разблокировать - иначе сервер не даст назначить
            action.setOnClickListener(v -> { if (m.blocked) unblock(m); else appoint(m.id); });
            card.addView(action);
        // действующего модератора можно снять. админа снять нельзя, поэтому только для роли MODERATOR
        } else if ("MODERATOR".equals(m.role)) {
            TextView remove = text("Снять с должности", R.color.c_danger, 14);
            remove.setPadding(0, pad / 2, 0, 0);
            // спрашиваю подтверждение, чтобы не снять случайным тапом
            remove.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage("Снять " + m.nickname + " с модераторов? Его заявки вернутся в очередь.")
                    .setPositiveButton("Снять", (d, w) -> dismiss(m.id))
                    .setNegativeButton("Отмена", null)
                    .show());
            card.addView(remove);
        }
        // тап по всей карточке открывает подробную статистику этого модератора
        card.setOnClickListener(v -> {
            Intent i = new Intent(this, ModeratorStatsActivity.class);
            i.putExtra(ModeratorStatsActivity.EXTRA_USER_ID, m.id);
            startActivity(i);
        });
        return card;
    }

    // маленький помощник: TextView с текстом, цветом и размером, чтобы не писать одно и то же везде
    private TextView text(String s, int colorRes, int sp) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(ContextCompat.getColor(this, colorRes));
        tv.setPadding(0, 2, 0, 2);
        return tv;
    }

    // назначение модератором (и новое, и возврат снятого) - один и тот же запрос
    private void appoint(long userId) {
        ApiClient.getApiService(this).appointModerator(userId).enqueue(simple("Назначен модератором"));
    }

    // снятие с должности, по словам из диалога его заявки уходят обратно в общую очередь
    private void dismiss(long userId) {
        ApiClient.getApiService(this).dismissModerator(userId).enqueue(simpleVoid("Снят с должности"));
    }

    // разблокировка с причиной, она сохранится в истории
    private void unblock(Ticket.Moderator m) {
        ApiClient.getApiService(this).unblockUser(m.id, new Ticket.TextRequest("Разблокирован администратором"))
                .enqueue(simpleVoid("Разблокирован - теперь можно вернуть в модераторы"));
    }

    // общий колбэк для запросов, которые возвращают модератора: показал тост и при успехе обновил список
    private Callback<Ticket.Moderator> simple(String ok) {
        return new Callback<Ticket.Moderator>() {
            @Override
            public void onResponse(Call<Ticket.Moderator> call, Response<Ticket.Moderator> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(ModeratorsActivity.this, response.isSuccessful() ? ok : ApiErrors.message(response, "Не удалось"),
                        Toast.LENGTH_LONG).show();
                if (response.isSuccessful()) load();
            }

            @Override
            public void onFailure(Call<Ticket.Moderator> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(ModeratorsActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        };
    }

    // то же самое, но для запросов без тела ответа (Void)
    private Callback<Void> simpleVoid(String ok) {
        return new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(ModeratorsActivity.this, response.isSuccessful() ? ok : ApiErrors.message(response, "Не удалось"),
                        Toast.LENGTH_LONG).show();
                if (response.isSuccessful()) load();
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(ModeratorsActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        };
    }
}

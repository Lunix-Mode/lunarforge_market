package com.lunarforge.market.ui.support;

import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.lunarforge.market.R;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Ticket;
import com.lunarforge.market.ui.chat.ChatActivity;
import com.lunarforge.market.util.ApiErrors;
import com.lunarforge.market.util.BaseActivity;
import com.lunarforge.market.util.Ui;

import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// моя заявка: статус, кто разбирает, решение. после решения - 👍/👎 модератору и обжалование
// это экран для обычного пользователя (у модератора свой TicketActivity). id заявки приходит через Intent.
// само общение с модератором идёт в обычном чате, сюда только кнопка "открыть чат"
public class UserTicketActivity extends BaseActivity {

    public static final String EXTRA_TICKET_ID = "ticket_id";

    private long ticketId;
    // последняя загруженная заявка, null пока не пришёл ответ
    private Ticket ticket;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_ticket);
        ticketId = getIntent().getLongExtra(EXTRA_TICKET_ID, -1);
        ((TextView) findViewById(R.id.titleText)).setText("Заявка #" + ticketId);
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        findViewById(R.id.openChatButton).setOnClickListener(v -> {
            // пока заявка не загрузилась, не знаю id чата - кнопка ничего не делает
            if (ticket == null) return;
            // для возврата/проблемы с заказом это чат заказа, для остального - чат с поддержкой
            Intent i = new Intent(this, ChatActivity.class);
            i.putExtra(ChatActivity.EXTRA_THREAD_ID, ticket.threadId);
            i.putExtra(ChatActivity.EXTRA_OTHER_NICKNAME, ticket.orderId != null ? "Чат заказа #" + ticket.orderId : "Поддержка");
            startActivity(i);
        });
        findViewById(R.id.likeButton).setOnClickListener(v -> rate(true));
        findViewById(R.id.dislikeButton).setOnClickListener(v -> rate(false));
        findViewById(R.id.appealButton).setOnClickListener(v -> appeal());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // гружу в onResume, а не в onCreate: вернулся из чата - статус мог поменяться (модератор взял или решил)
        load();
    }

    // запросить заявку с сервера и показать
    private void load() {
        ApiClient.getApiService(this).ticket(ticketId).enqueue(new Callback<Ticket>() {
            @Override
            public void onResponse(Call<Ticket> call, Response<Ticket> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.isSuccessful() && response.body() != null) bind(response.body());
                else Toast.makeText(UserTicketActivity.this, ApiErrors.message(response, "Заявка не найдена"), Toast.LENGTH_LONG).show();
            }

            @Override
            public void onFailure(Call<Ticket> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(UserTicketActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    // собираю всю информацию о заявке в один текст. SpannableStringBuilder, а не обычная строка,
    // потому что Ui.labelValue возвращает текст, где значение выделено жирным и цветом - обычная строка это потеряла бы
    private void bind(Ticket t) {
        ticket = t;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(Ui.ticketTypeLong(t.type)).append(" · ").append(Ui.ticketStatus(t.status)).append('\n');
        // блок про заказ - только если заявка привязана к заказу; reporterRole подсказывает, кем я там был
        if (t.orderId != null) {
            sb.append(String.format(Locale.getDefault(), "Заказ #%d «%s»", t.orderId, t.orderTitle));
            if ("BUYER".equals(t.reporterRole)) sb.append(" · вы покупатель");
            else if ("SELLER".equals(t.reporterRole)) sb.append(" · вы продавец");
            sb.append('\n');
        }
        if (t.reportedUserNickname != null) sb.append(Ui.labelValue(this, "На: ", t.reportedUserNickname)).append('\n');
        if (t.appealOfId != null) sb.append("Обжалуется решение по заявке #").append(String.valueOf(t.appealOfId)).append('\n');
        sb.append("«").append(t.reason).append("»");
        if (t.assigneeNickname != null) {
            sb.append(Ui.labelValue(this, "\nРазбирает: ", Ui.staffLabel(t.assigneeRole, t.assigneeNickname)));
        } else if (!"RESOLVED".equals(t.status)) {
            sb.append("\nЖдёт, пока модератор возьмёт заявку");
        }
        // решение показываю только у закрытой заявки, сумму - если был возврат/компенсация
        if ("RESOLVED".equals(t.status)) {
            sb.append("\n\nРешение: ").append(Ui.resolution(t.resolution));
            if (t.refundAmount != null && t.refundAmount > 0) {
                sb.append(String.format(Locale.getDefault(), " (%.2f ₽)", t.refundAmount));
            }
            if (t.resolutionNote != null) sb.append("\n«").append(t.resolutionNote).append("»");
        }
        ((TextView) findViewById(R.id.ticketInfo)).setText(sb);

        // можно ли оценивать и обжаловать - решает сервер (canRate/canAppeal), я только прячу/показываю кнопки.
        // так правила в одном месте, и их нельзя обойти, подменив приложение
        findViewById(R.id.rateRow).setVisibility(t.canRate ? View.VISIBLE : View.GONE);
        TextView fb = findViewById(R.id.myFeedbackText);
        if (t.myFeedback != null) {
            fb.setText(t.myFeedback ? "Вы оценили работу модератора: 👍" : "Вы оценили работу модератора: 👎");
            fb.setVisibility(View.VISIBLE);
        } else {
            fb.setVisibility(View.GONE);
        }
        findViewById(R.id.appealButton).setVisibility(t.canAppeal ? View.VISIBLE : View.GONE);
    }

    // оценка модератора: лайк отправляю сразу, для дизлайка сначала диалог с комментарием
    private void rate(boolean positive) {
        if (positive) {
            sendRate(true, null);
            return;
        }
        // за 👎 просим пару слов - так админ понимает, что не так с модератором
        EditText input = new EditText(this);
        input.setHint("Что было не так? (по желанию)");
        // поле ввода оборачиваю в FrameLayout с отступами, иначе в диалоге оно прилипает к краям
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("👎 Плохо")
                .setView(box)
                .setPositiveButton("Отправить", (d, w) -> sendRate(false, input.getText().toString().trim()))
                .setNegativeButton("Отмена", null)
                .show();
    }

    // отправляю оценку. сервер возвращает обновлённую заявку - сразу перерисовываю,
    // и кнопки оценки пропадут, если повторно оценить уже нельзя
    private void sendRate(boolean positive, String comment) {
        ApiClient.getApiService(this).ticketFeedback(ticketId, new Ticket.FeedbackRequest(positive, comment))
                .enqueue(new Callback<Ticket>() {
                    @Override
                    public void onResponse(Call<Ticket> call, Response<Ticket> response) {
                        if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                        if (response.isSuccessful() && response.body() != null) {
                            bind(response.body());
                            Toast.makeText(UserTicketActivity.this, "Спасибо за оценку!", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(UserTicketActivity.this, ApiErrors.message(response, "Не удалось"), Toast.LENGTH_LONG).show();
                        }
                    }

                    @Override
                    public void onFailure(Call<Ticket> call, Throwable t) {
                        if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                        Toast.makeText(UserTicketActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
                    }
                });
    }

    // обжалование решения: спрашиваю причину, сервер создаёт новую заявку типа DECISION_APPEAL
    private void appeal() {
        EditText input = new EditText(this);
        input.setHint("С чем не согласны?");
        input.setMinLines(3);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Обжаловать решение")
                .setMessage("Решение пересмотрит другой модератор. Если его признают ошибочным, площадка может выплатить компенсацию.")
                .setView(box)
                .setPositiveButton("Отправить", (d, w) -> {
                    String text = input.getText().toString().trim();
                    // совсем короткий текст не пускаю. диалог при этом всё равно закроется - так работает AlertDialog
                    if (text.length() < 5) {
                        Toast.makeText(this, "Опишите подробнее", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ApiClient.getApiService(this).appealDecision(ticketId, new Ticket.TextRequest(text)).enqueue(new Callback<Ticket>() {
                        @Override
                        public void onResponse(Call<Ticket> call, Response<Ticket> response) {
                            if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                            if (response.isSuccessful() && response.body() != null) {
                                Toast.makeText(UserTicketActivity.this, "Обжалование отправлено (заявка #" + response.body().id + ")",
                                        Toast.LENGTH_LONG).show();
                                // перезагружаю свою заявку, чтобы обновились статус и кнопки
                                load();
                            } else {
                                Toast.makeText(UserTicketActivity.this, ApiErrors.message(response, "Не удалось"), Toast.LENGTH_LONG).show();
                            }
                        }

                        @Override
                        public void onFailure(Call<Ticket> call, Throwable t) {
                            if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                            Toast.makeText(UserTicketActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton("Отмена", null)
                .show();
    }
}

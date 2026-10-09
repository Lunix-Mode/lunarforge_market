package com.lunarforge.market.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.lunarforge.market.R;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Ticket;
import com.lunarforge.market.model.User;
import com.lunarforge.market.ui.chat.ChatActivity;
import com.lunarforge.market.util.ApiErrors;
import com.lunarforge.market.util.BaseActivity;
import com.lunarforge.market.util.SessionManager;
import com.lunarforge.market.util.Ui;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// заблокированный видит только это: причину, свою апелляцию, чат поддержки и выход.
// сюда перекидывает, когда сервер говорит что юзер blocked. остальное приложение ему недоступно,
// а сервер всё равно пускает заблокированного только к нужным запросам (свой профиль, заявки, уведомления, сообщения чата - см. JwtAuthFilter на бэке)
public class BlockedActivity extends BaseActivity {

    private TextView reasonText, appealStatus, appealButton, chatButton;
    private Ticket appeal; // последняя заявка на разблокировку

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blocked);
        reasonText = findViewById(R.id.blockReasonText);
        appealStatus = findViewById(R.id.appealStatusText);
        appealButton = findViewById(R.id.appealButton);
        chatButton = findViewById(R.id.supportChatButton);

        appealButton.setOnClickListener(v -> appealDialog());
        // чат поддержки - это чат, привязанный к заявке на разблокировку, поэтому без заявки открыть нечего
        chatButton.setOnClickListener(v -> {
            if (appeal == null) return;
            Intent i = new Intent(this, ChatActivity.class);
            i.putExtra(ChatActivity.EXTRA_THREAD_ID, appeal.threadId);
            i.putExtra(ChatActivity.EXTRA_OTHER_NICKNAME, "Поддержка");
            startActivity(i);
        });
        // выход: стираю токен и открываю логин. CLEAR_TASK убирает все экраны из стека,
        // чтобы кнопкой "назад" нельзя было вернуться сюда
        findViewById(R.id.logoutButton).setOnClickListener(v -> {
            new SessionManager(this).clear();
            Intent i = new Intent(this, LoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
        });
    }

    // onResume, а не onCreate - срабатывает и при возврате из чата поддержки
    @Override
    protected void onResume() {
        super.onResume();
        load(); // вдруг уже разблокировали
    }

    // два параллельных запроса: свой профиль (статус блокировки и причина) и свои заявки (ищу апелляцию)
    private void load() {
        ApiClient.getApiService(this).me().enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                User u = response.body();
                if (u == null) return;
                if (!u.blocked) { // разблокировали - назад в приложение
                    // с очисткой стека, чтобы экран блокировки не остался позади главного
                    Intent i = new Intent(BlockedActivity.this, com.lunarforge.market.ui.home.MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    return;
                }
                // labelValue делает "Причина:" одним стилем, а саму причину другим
                reasonText.setText(Ui.labelValue(BlockedActivity.this, "Причина: ",
                        u.blockReason != null ? u.blockReason : "не указана"));
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                reasonText.setText(ApiErrors.network(t));
            }
        });
        ApiClient.getApiService(this).myTickets().enqueue(new Callback<List<Ticket>>() {
            @Override
            public void onResponse(Call<List<Ticket>> call, Response<List<Ticket>> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                appeal = null;
                if (response.body() != null) {
                    // беру первую заявку типа UNBLOCK_APPEAL - она и есть самая свежая
                    for (Ticket t : response.body()) {
                        if ("UNBLOCK_APPEAL".equals(t.type)) { appeal = t; break; } // список уже от новых к старым
                    }
                }
                showAppeal();
            }

            // не загрузились заявки - не страшно, просто оставляю как было
            @Override
            public void onFailure(Call<List<Ticket>> call, Throwable t) { }
        });
    }

    // показываю состояние апелляции и решаю какие кнопки видны
    private void showAppeal() {
        // активная = есть и ещё не решена. пока она висит, вторую подать нельзя - прячу кнопку
        boolean active = appeal != null && !"RESOLVED".equals(appeal.status);
        appealButton.setVisibility(active ? View.GONE : View.VISIBLE);
        // чат доступен пока есть хоть какая-то заявка (даже решённая - чтобы видеть ответ)
        chatButton.setVisibility(appeal != null ? View.VISIBLE : View.GONE);
        if (appeal == null) {
            appealStatus.setVisibility(View.GONE);
            return;
        }
        // собираю текст статуса по кусочкам: номер, статус, кто рассматривает, решение
        String s = "Заявка #" + appeal.id + ": " + Ui.ticketStatus(appeal.status);
        if (appeal.assigneeNickname != null && active) s += "\nРассматривает: " + Ui.staffLabel(appeal.assigneeRole, appeal.assigneeNickname);
        if ("RESOLVED".equals(appeal.status)) {
            s += "\nРешение: " + ("UNBLOCKED".equals(appeal.resolution) ? "разблокировать" : "блокировка оставлена");
            if (appeal.resolutionNote != null) s += "\n«" + appeal.resolutionNote + "»";
        }
        appealStatus.setText(s);
        appealStatus.setVisibility(View.VISIBLE);
    }

    // диалог с полем ввода для текста апелляции
    private void appealDialog() {
        EditText input = new EditText(this);
        input.setHint("Почему блокировка ошибочна?");
        input.setMinLines(3);
        // EditText кладу в FrameLayout с отступами 20dp, иначе поле прилипает к краям диалога
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Заявка на разблокировку")
                .setMessage("Её рассмотрит модератор, который вас не блокировал. Ответ придёт в чат поддержки.")
                .setView(box)
                .setPositiveButton("Отправить", (d, w) -> {
                    String text = input.getText().toString().trim();
                    // совсем короткий текст не отправляю. диалог при этом всё равно закроется
                    if (text.length() < 5) {
                        Toast.makeText(this, "Опишите подробнее", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ApiClient.getApiService(this).unblockAppeal(new Ticket.TextRequest(text)).enqueue(new Callback<Ticket>() {
                        @Override
                        public void onResponse(Call<Ticket> call, Response<Ticket> response) {
                            if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                            if (response.isSuccessful()) {
                                Toast.makeText(BlockedActivity.this, "Заявка отправлена", Toast.LENGTH_SHORT).show();
                                // перезагружаю, чтобы появился статус заявки и кнопка чата
                                load();
                            } else {
                                // например уже есть открытая заявка - сервер пришлёт причину текстом
                                Toast.makeText(BlockedActivity.this, ApiErrors.message(response, "Не удалось отправить"), Toast.LENGTH_LONG).show();
                            }
                        }

                        @Override
                        public void onFailure(Call<Ticket> call, Throwable t) {
                            if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                            Toast.makeText(BlockedActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton("Отмена", null)
                .show();
    }
}

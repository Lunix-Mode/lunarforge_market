package com.lunarforge.market.ui.support;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lunarforge.market.R;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Order;
import com.lunarforge.market.model.Ticket;
import com.lunarforge.market.model.User;
import com.lunarforge.market.util.ApiErrors;
import com.lunarforge.market.util.BaseActivity;
import com.lunarforge.market.util.SessionManager;
import com.lunarforge.market.util.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// "Поддержка": мои заявки + создание новой (проблема с заказом / аккаунт / вернуть в модераторы)
// сами заявки смотрят и решают модераторы, а тут только сторона пользователя: список и кнопка "новая заявка".
// по нажатию на заявку открывается UserTicketActivity с чатом по ней
public class MyTicketsActivity extends BaseActivity {

    // список заявок, который показывает адаптер
    private final List<Ticket> items = new ArrayList<>();
    private RecyclerView.Adapter<?> adapter;
    private TextView emptyText;
    // был ли я раньше модератором - от этого зависит, показывать ли пункт "вернуть в модераторы"
    private boolean formerModerator = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_tickets);
        ((TextView) findViewById(R.id.titleText)).setText("Поддержка");
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        emptyText = findViewById(R.id.emptyText);
        findViewById(R.id.newTicketButton).setOnClickListener(v -> chooseType());

        // список заявок. адаптер сделал анонимным прямо тут - он нужен только на этом экране
        RecyclerView list = findViewById(R.id.ticketsRecyclerView);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                // пустой ViewHolder без своего класса: поля всё равно ищу через findViewById в onBindViewHolder
                return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_ticket, parent, false)) { };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
                Ticket t = items.get(position);
                // заголовок: номер и тип заявки, Ui.ticketType переводит код типа в понятный текст
                ((TextView) h.itemView.findViewById(R.id.ticketTitle)).setText("#" + t.id + " · " + Ui.ticketType(t.type));
                // если заявка про заказ - пишу какой заказ, иначе просто когда создана
                ((TextView) h.itemView.findViewById(R.id.ticketSubtitle)).setText(t.orderTitle != null
                        ? "Заказ #" + t.orderId + " «" + t.orderTitle + "»" : Ui.createdAgo(t.createdAt));
                ((TextView) h.itemView.findViewById(R.id.ticketReason)).setText(t.reason);
                String status = Ui.ticketStatus(t.status);
                // пока заявка не решена - показываю кто из модераторов её взял
                if (t.assigneeNickname != null && !"RESOLVED".equals(t.status)) {
                    status += " · " + Ui.staffLabel(t.assigneeRole, t.assigneeNickname);
                }
                // решённая - дописываю чем закончилась
                if ("RESOLVED".equals(t.status)) status += " · " + Ui.resolution(t.resolution);
                ((TextView) h.itemView.findViewById(R.id.ticketStatus)).setText(status);
                // по нажатию открываю саму заявку (там чат с модератором)
                h.itemView.setOnClickListener(v -> {
                    Intent i = new Intent(MyTicketsActivity.this, UserTicketActivity.class);
                    i.putExtra(UserTicketActivity.EXTRA_TICKET_ID, t.id);
                    startActivity(i);
                });
            }

            @Override
            public int getItemCount() {
                return items.size();
            }
        };
        list.setAdapter(adapter);

        // бывшему модератору покажем пункт "вернуть в модераторы"
        ApiClient.getApiService(this).me().enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.body() != null) formerModerator = response.body().formerModerator;
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) { }
        });
    }

    // список обновляю в onResume, а не в onCreate: так после возврата с экрана заявки
    // или после создания новой статус сразу свежий
    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    // загрузка моих заявок с сервера
    private void load() {
        ApiClient.getApiService(this).myTickets().enqueue(new Callback<List<Ticket>>() {
            @Override
            public void onResponse(Call<List<Ticket>> call, Response<List<Ticket>> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.body() == null) return;
                items.clear();
                items.addAll(response.body());
                // данные поменялись целиком - перерисовываю весь список
                adapter.notifyDataSetChanged();
                // пусто - показываю надпись "заявок нет"
                emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onFailure(Call<List<Ticket>> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(MyTicketsActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    // диалог выбора типа новой заявки. третий пункт есть только у бывших модераторов,
    // поэтому which == 2 может прийти только от него - отдельно его не проверяю, это ветка else
    private void chooseType() {
        List<String> labels = new ArrayList<>();
        labels.add("🧾 Проблема с заказом");
        labels.add("🛟 Проблема с аккаунтом");
        if (formerModerator) labels.add("🛡️ Вернуть меня в модераторы");
        new AlertDialog.Builder(this)
                .setTitle("О чём заявка?")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    // про заказ - сначала надо выбрать какой именно заказ
                    if (which == 0) chooseOrder();
                    else if (which == 1) askReason("Проблема с аккаунтом", "Опишите, что случилось",
                            text -> send(ApiClient.getApiService(this).accountProblem(new Ticket.TextRequest(text))));
                    else askReason("Вернуть в модераторы", "Почему вас стоит вернуть? Заявку прочитает администратор",
                            text -> send(ApiClient.getApiService(this).reinstatement(new Ticket.TextRequest(text))));
                })
                .show();
    }

    // проблема с заказом: показываем мои покупки и продажи, сервер сам поймёт кто я в заказе
    private void chooseOrder() {
        List<Order> all = new ArrayList<>();
        long me = new SessionManager(this).getUserId();
        // два запроса по очереди: сначала покупки, в его ответе - продажи. так обе части точно
        // соберутся в один список all до показа диалога
        ApiClient.getApiService(this).myPurchases().enqueue(new Callback<List<Order>>() {
            @Override
            public void onResponse(Call<List<Order>> call, Response<List<Order>> r1) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (r1.body() != null) all.addAll(r1.body());
                ApiClient.getApiService(MyTicketsActivity.this).mySales().enqueue(new Callback<List<Order>>() {
                    @Override
                    public void onResponse(Call<List<Order>> call, Response<List<Order>> r2) {
                        if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                        if (r2.body() != null) all.addAll(r2.body());
                        if (all.isEmpty()) {
                            Toast.makeText(MyTicketsActivity.this, "У вас пока нет заказов", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        // сортирую по id по убыванию - чем больше id, тем новее заказ
                        all.sort((a, b) -> Long.compare(b.id, a.id)); // свежие сверху
                        String[] labels = new String[all.size()];
                        for (int i = 0; i < all.size(); i++) {
                            Order o = all.get(i);
                            // подписываю каждый заказ: номер, название и кто я в этой сделке
                            labels[i] = String.format(Locale.getDefault(), "#%d «%s» · %s", o.id, o.listingTitle,
                                    o.buyerId == me ? "вы покупатель" : "вы продавец");
                        }
                        new AlertDialog.Builder(MyTicketsActivity.this)
                                .setTitle("Какой заказ?")
                                .setItems(labels, (d, which) -> {
                                    // запоминаю id выбранного заказа и спрашиваю, что случилось
                                    long orderId = all.get(which).id;
                                    askReason("Проблема с заказом #" + orderId, "Что не так с заказом?",
                                            text -> send(ApiClient.getApiService(MyTicketsActivity.this)
                                                    .orderProblem(new Ticket.OrderProblemRequest(orderId, text))));
                                })
                                .show();
                    }

                    // ошибку второго запроса (продажи) молча пропускаю - диалог выбора заказа тогда просто не откроется
                    @Override
                    public void onFailure(Call<List<Order>> call, Throwable t) { }
                });
            }

            @Override
            public void onFailure(Call<List<Order>> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(MyTicketsActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    // маленький колбэк, чтобы передать введённый текст дальше (в разные запросы на создание)
    interface OnText { void accept(String text); }

    // общий диалог с полем ввода для причины. EditText оборачиваю в FrameLayout,
    // чтобы сделать отступы по бокам, иначе поле прилипает к краям диалога
    private void askReason(String title, String hint, OnText onText) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setMinLines(3);
        // 20dp в пиксели
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Отправить", (d, w) -> {
                    String text = input.getText().toString().trim();
                    // совсем короткий текст не отправляю - модератору по "не работает" ничего не понять.
                    // диалог при этом закроется, юзер увидит тост и сможет открыть его снова
                    if (text.length() < 5) {
                        Toast.makeText(this, "Опишите подробнее", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    onText.accept(text);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // общая отправка любой заявки: какой именно запрос - передаю готовым Call. после успеха перезагружаю список
    private void send(Call<Ticket> call) {
        call.enqueue(new Callback<Ticket>() {
            @Override
            public void onResponse(Call<Ticket> c, Response<Ticket> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.isSuccessful()) {
                    Toast.makeText(MyTicketsActivity.this, "Заявка создана. Модератор ответит в чате", Toast.LENGTH_LONG).show();
                    load();
                } else {
                    // ApiErrors.message достаёт текст ошибки, который прислал сервер, а если его нет - пишет запасной
                    Toast.makeText(MyTicketsActivity.this, ApiErrors.message(response, "Не удалось создать заявку"), Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<Ticket> c, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(MyTicketsActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }
}

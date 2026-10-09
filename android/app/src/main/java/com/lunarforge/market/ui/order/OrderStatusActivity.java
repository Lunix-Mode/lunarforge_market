package com.lunarforge.market.ui.order;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.lunarforge.market.R;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Order;
import com.lunarforge.market.model.Rating;
import com.lunarforge.market.util.ApiErrors;
import com.lunarforge.market.util.SessionManager;

import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// экран одного заказа (безопасная сделка). его видят обе стороны - и покупатель, и продавец,
// а что показывать, решаю по тому, кто я в этом заказе. открывается по id заказа из EXTRA_ORDER_ID.
// отсюда: подтвердить получение, отменить, запросить возврат (спор), оценить продавца, написать в чат
public class OrderStatusActivity extends com.lunarforge.market.util.BaseActivity {
    public static final String EXTRA_ORDER_ID = "order_id";

    // id приходит из интента, сам заказ - с сервера (поле order запоминаю после загрузки)
    private long orderId;
    private Order order;
    // из сессии беру свой id, чтобы понять - я покупатель или продавец
    private SessionManager session;

    private TextView statusBadge, orderTitleText, orderAmountText, orderPartyText, orderDateText,
            confirmButton, cancelButton, rateSellerButton, openChatButton, hintText, refundButton, ticketInfoText;

    // нахожу все вьюшки и сразу гружу заказ
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_order_status);

        session = new SessionManager(this);
        // -1 - если id не передали, сервер тогда просто ответит ошибкой и экран закроется
        orderId = getIntent().getLongExtra(EXTRA_ORDER_ID, -1);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        statusBadge = findViewById(R.id.statusBadge);
        orderTitleText = findViewById(R.id.orderTitleText);
        orderAmountText = findViewById(R.id.orderAmountText);
        orderPartyText = findViewById(R.id.orderPartyText);
        orderDateText = findViewById(R.id.orderDateText);
        confirmButton = findViewById(R.id.confirmButton);
        cancelButton = findViewById(R.id.cancelButton);
        rateSellerButton = findViewById(R.id.rateSellerButton);
        openChatButton = findViewById(R.id.openChatButton);
        refundButton = findViewById(R.id.refundButton);
        findViewById(R.id.orderProblemButton).setOnClickListener(v -> orderProblemDialog());
        ticketInfoText = findViewById(R.id.ticketInfoText);
        hintText = findViewById(R.id.orderHintText);
        load();
    }

    // загрузка заказа с сервера. вызываю и при открытии, и после отправки заявки, чтобы статус обновился
    private void load() {
        ApiClient.getApiService(this).getOrder(orderId).enqueue(new Callback<Order>() {
            @Override
            public void onResponse(Call<Order> call, Response<Order> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.isSuccessful() && response.body() != null) {
                    // сравниваю id покупателя со своим - от этого зависят кнопки и подсказки
                    Order o = response.body();
                    bind(o, o.buyerId == session.getUserId());
                } else {
                    // заказ чужой или не найден - показываю ошибку и закрываю экран, показывать тут нечего
                    Toast.makeText(OrderStatusActivity.this,
                            ApiErrors.message(response, "Заказ не найден"), Toast.LENGTH_SHORT).show();
                    finish();
                }
            }

            @Override
            public void onFailure(Call<Order> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(OrderStatusActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    // кнопки разные: покупатель - подтвердить и оценить, продавец - отменить
    private void bind(Order o, boolean viewerIsBuyer) {
        this.order = o;

        // в заголовке название объявления и количество штук
        orderTitleText.setText(o.listingTitle + "  × " + o.quantity);
        // PENDING_CONFIRMATION - деньги покупателя заморожены, пока он не подтвердит получение
        boolean pending = "PENDING_CONFIRMATION".equals(o.status);
        // сумма + пометки: заморожена ли и сколько вернули по спору (если был возврат)
        orderAmountText.setText(String.format(Locale.getDefault(), "Сумма: %.2f ₽%s%s",
                o.amount, pending ? " · заморожена до подтверждения" : "",
                o.refundedAmount != null && o.refundedAmount > 0
                        ? String.format(Locale.getDefault(), " · возвращено %.2f ₽", o.refundedAmount) : ""));
        // показываю вторую сторону сделки: покупателю - продавца, продавцу - покупателя
        orderPartyText.setText(viewerIsBuyer
                ? com.lunarforge.market.util.Ui.labelValue(this, "Продавец: ", o.sellerNickname)
                : com.lunarforge.market.util.Ui.labelValue(this, "Покупатель: ", o.buyerNickname));
        orderDateText.setText("Заказ #" + o.id + " · создан " + formatDate(o.createdAt));

        // кнопка чата ведёт к собеседнику по этому заказу
        long otherId = viewerIsBuyer ? o.sellerId : o.buyerId;
        String otherNick = viewerIsBuyer ? o.sellerNickname : o.buyerNickname;
        openChatButton.setText(viewerIsBuyer ? "💬 Написать продавцу" : "💬 Написать покупателю");
        openChatButton.setOnClickListener(v -> openChat(otherId, otherNick));

        // сначала прячу все кнопки действий, а ниже в switch включаю только нужные для текущего статуса.
        // иначе после смены статуса (например подтвердили) старые кнопки остались бы на экране
        confirmButton.setVisibility(TextView.GONE);
        cancelButton.setVisibility(TextView.GONE);
        rateSellerButton.setVisibility(TextView.GONE);
        refundButton.setVisibility(TextView.GONE);
        // отдельным запросом подтягиваю заявку по заказу, если она есть
        loadTicket(o.id);

        hintText.setVisibility(TextView.VISIBLE); // после перезагрузки заказа - показываем заново, нужное скроем ниже
        switch (o.status) {
            // завершён: деньги уже у продавца, но вывести он их сможет только через 48 часов холда
            case "COMPLETED":
                statusBadge.setText("Завершён");
                statusBadge.setBackgroundResource(R.drawable.bg_status_completed);
                statusBadge.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.c_success));
                hintText.setText(viewerIsBuyer
                        ? "Сделка завершена. Вы можете оставить отзыв о продавце."
                        : "Сделка завершена. Деньги станут доступны для вывода через 48 часов после подтверждения.");
                if (viewerIsBuyer) {
                    rateSellerButton.setVisibility(TextView.VISIBLE);
                    rateSellerButton.setText("★ Оценить продавца");
                    // проверяю, оставлял ли я уже отзыв - от этого зависит текст кнопки
                    checkExistingRating();
                }
                break;
            case "DISPUTED":
                // спор открыт: деньги заморожены, подтвердить/отменить нельзя, решает модератор
                statusBadge.setText("Спор открыт");
                statusBadge.setBackgroundResource(R.drawable.bg_status_pending);
                statusBadge.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.c_warning));
                hintText.setText("Заказ заморожен до решения модератора. Он может написать вам в чат - "
                        + "отвечайте там. Возможен полный или частичный возврат.");
                break;
            case "CANCELLED":
                statusBadge.setText("Отменён");
                statusBadge.setBackgroundResource(R.drawable.bg_status_cancelled);
                statusBadge.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.c_danger));
                hintText.setText("Заказ отменён продавцом, деньги вернулись покупателю.");
                break;
            // остальное = заказ ещё ждёт выполнения (деньги заморожены)
            default:
                statusBadge.setText("Ожидает выполнения");
                statusBadge.setBackgroundResource(R.drawable.bg_status_pending);
                statusBadge.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.c_warning));
                // покупателю: подтвердить получение или попросить возврат
                if (viewerIsBuyer) {
                    hintText.setText("Деньги заморожены. Подтвердите получение только когда получили товар/услугу - "
                            + "после этого деньги уйдут продавцу. Если что-то не так - напишите продавцу в чат.");
                    confirmButton.setVisibility(TextView.VISIBLE);
                    confirmButton.setOnClickListener(v -> confirmDialog());
                    refundButton.setVisibility(TextView.VISIBLE);
                    refundButton.setOnClickListener(v -> refundDialog());
                // продавцу: выполнить заказ или отменить его с возвратом денег
                } else {
                    hintText.setText("Выполните заказ и сообщите покупателю в чате. Деньги придут после его подтверждения. "
                            + "Если выполнить не можете - отмените заказ, деньги вернутся покупателю.");
                    cancelButton.setText("Отменить и вернуть деньги");
                    cancelButton.setVisibility(TextView.VISIBLE);
                    cancelButton.setOnClickListener(v -> cancelDialog());
                }
                break;
        }
    }

    // открыть чат с другой стороной. startThread на сервере вернёт уже существующий диалог
    // или создаст новый, в ответе id чата - с ним и открываю ChatActivity
    private void openChat(long otherUserId, String otherNick) {
        ApiClient.getApiService(this).startThread(new com.lunarforge.market.model.Chat.StartThreadRequest(otherUserId, null))
                .enqueue(new Callback<com.lunarforge.market.model.Chat.Thread>() {
                    @Override
                    public void onResponse(Call<com.lunarforge.market.model.Chat.Thread> call,
                                           Response<com.lunarforge.market.model.Chat.Thread> response) {
                                               if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                        if (response.isSuccessful() && response.body() != null) {
                            android.content.Intent intent = new android.content.Intent(OrderStatusActivity.this,
                                    com.lunarforge.market.ui.chat.ChatActivity.class);
                            intent.putExtra(com.lunarforge.market.ui.chat.ChatActivity.EXTRA_THREAD_ID, response.body().id);
                            intent.putExtra(com.lunarforge.market.ui.chat.ChatActivity.EXTRA_OTHER_NICKNAME, otherNick);
                            intent.putExtra(com.lunarforge.market.ui.chat.ChatActivity.EXTRA_OTHER_USER_ID, otherUserId);
                            startActivity(intent);
                        } else {
                            Toast.makeText(OrderStatusActivity.this, ApiErrors.message(response, "Не удалось открыть чат"),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onFailure(Call<com.lunarforge.market.model.Chat.Thread> call, Throwable t) {
                        if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                        Toast.makeText(OrderStatusActivity.this, ApiErrors.network(t), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // из ISO строки беру только дату (первые 10 символов "ГГГГ-ММ-ДД"), время тут не нужно.
    // короткая или пустая строка - ставлю прочерк, чтобы substring не упал
    private String formatDate(String iso) {
        if (iso == null || iso.length() < 10) return "—";
        return iso.substring(0, 10);
    }

    // подтверждение получения - необратимое действие, поэтому сначала спрашиваю ещё раз
    private void confirmDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Подтвердить получение?")
                .setMessage("Деньги будут переведены продавцу. Это действие нельзя отменить.")
                .setPositiveButton("Подтвердить", (d, w) -> doConfirm())
                .setNegativeButton("Отмена", null)
                .show();
    }

    // отправка подтверждения. сервер переводит заказ в COMPLETED и начисляет продавцу его часть
    // (минус 5% комиссии), дальше деньги 48 часов на холде. в ответе уже обновлённый заказ - сразу перерисовываю
    private void doConfirm() {
        ApiClient.getApiService(this).confirmOrder(orderId).enqueue(new Callback<Order>() {
            @Override
            public void onResponse(Call<Order> call, Response<Order> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.isSuccessful() && response.body() != null) {
                    Toast.makeText(OrderStatusActivity.this, "Получение подтверждено, средства переведены продавцу", Toast.LENGTH_SHORT).show();
                    // true - подтвердить может только покупатель, так что это точно я
                    bind(response.body(), true);
                } else {
                    Toast.makeText(OrderStatusActivity.this, ApiErrors.message(response, "Не удалось подтвердить заказ"), Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<Order> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(OrderStatusActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    // отмена доступна только продавцу (кнопка показывается ему), тоже с переспросом
    private void cancelDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Отменить заказ?")
                .setMessage("Деньги вернутся покупателю, а товар снова появится в наличии.")
                .setPositiveButton("Отменить заказ", (d, w) -> doCancel())
                .setNegativeButton("Назад", null)
                .show();
    }

    private void doCancel() {
        ApiClient.getApiService(this).cancelOrder(orderId).enqueue(new Callback<Order>() {
            @Override
            public void onResponse(Call<Order> call, Response<Order> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.isSuccessful() && response.body() != null) {
                    Toast.makeText(OrderStatusActivity.this, "Заказ отменён, деньги возвращены покупателю", Toast.LENGTH_SHORT).show();
                    // false - отменял продавец, то есть я
                    bind(response.body(), false);
                } else {
                    Toast.makeText(OrderStatusActivity.this, ApiErrors.message(response, "Не удалось отменить заказ"), Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<Order> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(OrderStatusActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    // узнаю, есть ли уже мой отзыв к этому заказу. если есть - кнопка превращается в "изменить отзыв"
    private void checkExistingRating() {
        ApiClient.getApiService(this).myRatingForOrder(orderId).enqueue(new Callback<Rating>() {
            @Override
            public void onResponse(Call<Rating> call, Response<Rating> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Rating existing = response.isSuccessful() ? response.body() : null;
                if (existing != null) {
                    // отзыв уже оставлен - подсказка "можете оставить отзыв" больше не нужна
                    hintText.setVisibility(TextView.GONE);
                    rateSellerButton.setText("★ Изменить отзыв");
                    rateSellerButton.setOnClickListener(v -> showRateDialog(existing));
                } else {
                    rateSellerButton.setText("★ Оценить продавца");
                    rateSellerButton.setOnClickListener(v -> showRateDialog(null));
                }
            }

            @Override
            public void onFailure(Call<Rating> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                // не удалось проверить - пусть хотя бы можно будет оставить новый отзыв
                rateSellerButton.setOnClickListener(v -> showRateDialog(null));
            }
        });
    }

    // existing == null - новый отзыв, иначе редактирование (сервер пускает раз в сутки)
    private void showRateDialog(Rating existing) {
        // разметку диалога беру из xml, null вместо родителя - диалог сам её разместит
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_rate_seller, null);
        // выбранные звёзды храню в массиве из одного элемента: в лямбду можно передать только
        // effectively final переменную, а содержимое массива менять можно. по умолчанию 5 звёзд
        int[] chosen = {existing != null ? existing.score : 5};
        com.lunarforge.market.util.Stars.picker(dialogView.findViewById(R.id.starsPicker), chosen[0], 40,
                score -> chosen[0] = score);
        EditText commentEditText = dialogView.findViewById(R.id.commentEditText);

        if (existing != null) {
            commentEditText.setText(existing.comment);
        }

        // create, а не show - сам диалог нужен в колбэке, чтобы закрыть его только после успешного сохранения
        AlertDialog dialog = new AlertDialog.Builder(this).setView(dialogView).create();

        dialogView.findViewById(R.id.confirmRatingButton).setOnClickListener(v -> {
            // на всякий случай зажимаю оценку в 1..5, сервер всё равно проверит
            int score = Math.max(1, Math.min(5, chosen[0]));
            String comment = commentEditText.getText().toString().trim();

            // один колбэк на оба случая (новый отзыв и правка)
            Callback<Rating> callback = new Callback<Rating>() {
                @Override
                public void onResponse(Call<Rating> call, Response<Rating> response) {
                    if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                    if (response.isSuccessful()) {
                        Toast.makeText(OrderStatusActivity.this, "Спасибо за оценку!", Toast.LENGTH_SHORT).show();
                        // после сохранения перепроверяю отзыв, чтобы кнопка стала "Изменить отзыв"
                        checkExistingRating();
                        dialog.dismiss();
                    // 429 - сервер отказал, потому что сутки с прошлой правки ещё не прошли
                    } else if (response.code() == 429) {
                        Toast.makeText(OrderStatusActivity.this, "Отзыв можно менять не чаще раза в 24 часа", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(OrderStatusActivity.this, ApiErrors.message(response, "Не удалось сохранить отзыв"), Toast.LENGTH_LONG).show();
                    }
                }

                @Override
                public void onFailure(Call<Rating> call, Throwable t) {
                    if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                    Toast.makeText(OrderStatusActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
                }
            };

            // отзыв уже есть - правлю его по id, иначе создаю новый для этого заказа
            if (existing != null) {
                ApiClient.getApiService(this).updateRating(existing.id, new Rating.UpdateRequest(score, comment)).enqueue(callback);
            } else {
                ApiClient.getApiService(this).createRating(new Rating.CreateRequest(orderId, score, comment)).enqueue(callback);
            }
        });

        dialog.show();
    }

    // покупатель просит возврат: заказ уходит в спор, его разбирает модератор
    private void refundDialog() {
        // поле ввода делаю кодом и оборачиваю в FrameLayout ради отступов, отдельный xml под это не стал заводить
        android.widget.EditText reason = new android.widget.EditText(this);
        reason.setHint("Что случилось? Например: товар не пришёл");
        reason.setMinLines(3);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(reason);
        new AlertDialog.Builder(this)
                .setTitle("Запросить возврат")
                .setMessage("Заказ заморозится, и его разберёт модератор. Он посмотрит вашу переписку с продавцом "
                        + "и может задать вопросы прямо в чате.")
                .setView(box)
                .setPositiveButton("Отправить", (d, w) -> {
                    String text = reason.getText().toString().trim();
                    // совсем короткое описание не отправляю - модератору нужно понять суть
                    if (text.length() < 5) {
                        Toast.makeText(this, "Опишите проблему чуть подробнее", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ApiClient.getApiService(this).requestRefund(new com.lunarforge.market.model.Ticket.RefundRequest(orderId, text))
                            .enqueue(new Callback<com.lunarforge.market.model.Ticket>() {
                                @Override
                                public void onResponse(Call<com.lunarforge.market.model.Ticket> call,
                                                       Response<com.lunarforge.market.model.Ticket> response) {
                                                           if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                                    if (response.isSuccessful()) {
                                        Toast.makeText(OrderStatusActivity.this, "Заявка отправлена модераторам", Toast.LENGTH_LONG).show();
                                        load();
                                    } else {
                                        Toast.makeText(OrderStatusActivity.this, ApiErrors.message(response, "Не удалось отправить"), Toast.LENGTH_LONG).show();
                                    }
                                }

                                @Override
                                public void onFailure(Call<com.lunarforge.market.model.Ticket> call, Throwable t) {
                                    if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                                    Toast.makeText(OrderStatusActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
                                }
                            });
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // заявка по заказу (если есть): кто ведёт и чем закончилось
    // если по заказу есть заявка, показываю блок: номер, тип, статус, кто ведёт и решение
    private void loadTicket(long id) {
        ApiClient.getApiService(this).ticketForOrder(id).enqueue(new Callback<com.lunarforge.market.model.Ticket>() {
            @Override
            public void onResponse(Call<com.lunarforge.market.model.Ticket> call, Response<com.lunarforge.market.model.Ticket> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                // сервер отвечает 204 без тела, когда заявки нет - тогда body() будет null
                com.lunarforge.market.model.Ticket t = response.body();
                if (t == null) {           // 204 - заявок нет
                    ticketInfoText.setVisibility(TextView.GONE);
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("Заявка #").append(t.id).append(" · ").append(com.lunarforge.market.util.Ui.ticketType(t.type))
                        .append(" · ").append(com.lunarforge.market.util.Ui.ticketStatus(t.status));
                if (t.assigneeNickname != null) {
                    sb.append("\nВедёт: ").append(com.lunarforge.market.util.Ui.staffLabel(t.assigneeRole, t.assigneeNickname));
                }
                if ("RESOLVED".equals(t.status)) {
                    sb.append("\nРешение: ").append(com.lunarforge.market.util.Ui.resolution(t.resolution));
                    // при частичном/полном возврате пишу сколько вернули покупателю
                    if (t.refundAmount != null && t.refundAmount > 0) {
                        sb.append(String.format(Locale.getDefault(), " (%.2f ₽ покупателю)", t.refundAmount));
                    }
                    if (t.resolutionNote != null) sb.append("\n«").append(t.resolutionNote).append("»");
                }
                // setText принимает StringBuilder напрямую, он же CharSequence
                ticketInfoText.setText(sb);
                ticketInfoText.setVisibility(TextView.VISIBLE);
            }

            @Override
            public void onFailure(Call<com.lunarforge.market.model.Ticket> call, Throwable t) {
                // не страшно, просто не покажем блок заявки
            }
        });
    }

    // обеим сторонам: что-то пошло не так, но возврат не нужен (или ты продавец)
    private void orderProblemDialog() {
        // тут всё как в refundDialog, только другой запрос: заказ не замораживается, модератор просто подключается к чату
        android.widget.EditText reason = new android.widget.EditText(this);
        reason.setHint("Что случилось?");
        reason.setMinLines(3);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(reason);
        new AlertDialog.Builder(this)
                .setTitle("Проблема с заказом")
                .setMessage("Модератор посмотрит переписку и подключится к вашему чату. Заказ не замораживается.")
                .setView(box)
                .setPositiveButton("Отправить", (d, w) -> {
                    String text = reason.getText().toString().trim();
                    if (text.length() < 5) {
                        Toast.makeText(this, "Опишите подробнее", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ApiClient.getApiService(this).orderProblem(new com.lunarforge.market.model.Ticket.OrderProblemRequest(orderId, text))
                            .enqueue(new Callback<com.lunarforge.market.model.Ticket>() {
                                @Override
                                public void onResponse(Call<com.lunarforge.market.model.Ticket> call,
                                                       Response<com.lunarforge.market.model.Ticket> response) {
                                                           if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                                    Toast.makeText(OrderStatusActivity.this, response.isSuccessful()
                                            ? "Заявка отправлена модераторам"
                                            : ApiErrors.message(response, "Не удалось отправить"), Toast.LENGTH_LONG).show();
                                    if (response.isSuccessful()) load();
                                }

                                @Override
                                public void onFailure(Call<com.lunarforge.market.model.Ticket> call, Throwable t) {
                                    if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                                    Toast.makeText(OrderStatusActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
                                }
                            });
                })
                .setNegativeButton("Отмена", null)
                .show();
    }
}

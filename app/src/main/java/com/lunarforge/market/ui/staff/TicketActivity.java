package com.lunarforge.market.ui.staff;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lunarforge.market.R;
import com.lunarforge.market.adapter.ChatMessageAdapter;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Chat;
import com.lunarforge.market.model.Ticket;
import com.lunarforge.market.util.ApiErrors;
import com.lunarforge.market.util.BaseActivity;
import com.lunarforge.market.util.SessionManager;
import com.lunarforge.market.util.Ui;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// одна заявка глазами модератора: инфа, взять/перехватить, переписка сторон и решение.
// пишет модератор прямо в общий чат покупателя и продавца, у них его сообщения подписаны (🛡️/👑)
// открывается из списка заявок модератора, id заявки приходит в EXTRA_TICKET_ID.
// вебсокетов у меня нет, поэтому новые сообщения и состояние заявки просто опрашиваю раз в 4 секунды
public class TicketActivity extends BaseActivity {

    public static final String EXTRA_TICKET_ID = "ticket_id";
    // как часто опрашиваю сервер, 4 сек - компромисс между "быстро видно" и "не долбить сервер"
    private static final long POLL_MS = 4000;

    private long ticketId;
    private Ticket ticket;
    private ChatMessageAdapter adapter;
    private RecyclerView messagesView;
    private TextView info, claimButton, resolveButton;
    private View inputBar;
    private EditText input;
    // Handler на главном потоке - через него делаю повтор опроса с задержкой, и код выполняется в UI-потоке,
    // так что можно сразу трогать view
    private final Handler handler = new Handler(Looper.getMainLooper());
    // сам опрос: сходить за новыми сообщениями и запланировать себя же ещё раз через POLL_MS
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            pollMessages();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ticket);
        ticketId = getIntent().getLongExtra(EXTRA_TICKET_ID, -1);
        ((TextView) findViewById(R.id.titleText)).setText("Заявка #" + ticketId);
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        info = findViewById(R.id.ticketInfo);
        claimButton = findViewById(R.id.claimButton);
        resolveButton = findViewById(R.id.resolveButton);
        inputBar = findViewById(R.id.staffInputBar);
        input = findViewById(R.id.staffMessageEditText);

        // тот же адаптер, что и в обычном чате (фото, видео, голосовые, кружки). мой id - чтобы мои сообщения были справа
        adapter = new ChatMessageAdapter(new SessionManager(this).getUserId());
        messagesView = findViewById(R.id.messagesRecyclerView);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        // stackFromEnd - список прижат к низу, как в мессенджере: свежие сообщения внизу
        lm.setStackFromEnd(true);
        messagesView.setLayoutManager(lm);
        messagesView.setAdapter(adapter);

        claimButton.setOnClickListener(v -> claim());
        resolveButton.setOnClickListener(v -> showResolveDialog());
        findViewById(R.id.peopleButton).setOnClickListener(v -> showPeople());
        findViewById(R.id.staffSendButton).setOnClickListener(v -> send());

        // сначала грузим саму заявку, сообщения - уже после неё, им нужен threadId
        loadTicket();
    }

    @Override
    // опрос включаю только пока экран на виду. removeCallbacks сначала - чтобы не запустить два цикла сразу
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, POLL_MS);
    }

    @Override
    // ушли с экрана - останавливаю опрос (не тратим трафик и батарею) и глушу голосовое/видео, если играло
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
        adapter.stopPlayback();
    }

    // загрузка заявки. зовётся при открытии и потом на каждом опросе, чтобы видеть изменения (перехват, закрытие)
    private void loadTicket() {
        ApiClient.getApiService(this).staffTicket(ticketId).enqueue(new Callback<Ticket>() {
            @Override
            public void onResponse(Call<Ticket> call, Response<Ticket> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.isSuccessful() && response.body() != null) {
                    // first - это самая первая загрузка, только тогда тяну всю переписку целиком
                    boolean first = ticket == null;
                    bind(response.body());
                    if (first) loadAllMessages();
                } else {
                    Toast.makeText(TicketActivity.this, ApiErrors.message(response, "Заявка не найдена"), Toast.LENGTH_LONG).show();
                    // нет доступа или заявки нет - закрываю экран, тут больше нечего делать
                    finish();
                }
            }

            @Override
            public void onFailure(Call<Ticket> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(TicketActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    // рисую блок информации о заявке. текст разный в зависимости от типа заявки
    private void bind(Ticket t) {
        ticket = t;
        // SpannableStringBuilder, чтобы имена участников были цветом акцента
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append(Ui.ticketTypeLong(t.type)).append(" · ").append(Ui.ticketStatus(t.status)).append('\n');
        // для возврата показываю заказ и обе стороны, для остальных - на кого жалоба
        if ("REFUND".equals(t.type)) {
            // сумма может прийти null, тогда показываю 0, чтобы форматирование не упало
            sb.append(String.format(Locale.getDefault(), "Заказ #%d «%s» · %.2f ₽\n", t.orderId, t.orderTitle,
                    t.orderAmount == null ? 0 : t.orderAmount));
            sb.append(Ui.labelValue(this, "Покупатель: ", t.buyerNickname))
                    .append(Ui.labelValue(this, " · Продавец: ", t.sellerNickname)).append('\n');
        } else {
            sb.append(Ui.labelValue(this, "Жалоба на: ", t.reportedUserNickname)).append('\n');
        }
        if ("ORDER_PROBLEM".equals(t.type) && t.reporterRole != null) {
            sb.append("BUYER".equals(t.reporterRole) ? "Подал покупатель\n" : "Подал продавец\n");
        }
        if (t.appealOfId != null) sb.append("Обжалуется решение по заявке #").append(String.valueOf(t.appealOfId)).append('\n');
        if (t.blockReason != null) {
            sb.append(Ui.labelValue(this, "Заблокирован за: ", t.blockReason)).append('\n');
            if (t.blockedByLabel != null) sb.append(Ui.labelValue(this, "Заблокировал: ", t.blockedByLabel)).append('\n');
        }
        sb.append(Ui.labelValue(this, "Подал(а): ", t.reporterNickname)).append("\n«").append(t.reason).append("»");
        if (t.assigneeNickname != null) {
            sb.append(Ui.labelValue(this, "\nВедёт: ", Ui.staffLabel(t.assigneeRole, t.assigneeNickname)));
        }
        // подсказка "когда можно перехватить", только если я сам сейчас не веду заявку и она ещё открыта
        if (t.takeoverAvailableAt != null && !t.canAct && !"RESOLVED".equals(t.status)) {
            // время приходит в ISO ("2026-05-01T12:30:00Z"), обрезаю до "2026-05-01 12:30"
            sb.append("\nПерехватить можно после ").append(t.takeoverAvailableAt.replace('T', ' ').substring(0, 16)).append(" (UTC)");
        }
        if ("RESOLVED".equals(t.status)) {
            sb.append("\nРешение: ").append(Ui.resolution(t.resolution));
            if (t.refundAmount != null && t.refundAmount > 0) {
                sb.append(String.format(Locale.getDefault(), " (%.2f ₽ покупателю)", t.refundAmount));
            }
            if (t.resolutionNote != null) sb.append("\n«").append(t.resolutionNote).append("»");
        }
        info.setText(sb);

        // кнопки показываю по флагам с сервера: взять/перехватить, решить, и поле ввода только тому, кто ведёт
        claimButton.setVisibility(t.canClaim ? View.VISIBLE : View.GONE);
        claimButton.setText(t.assigneeNickname == null ? "Взять заявку" : "Перехватить");
        resolveButton.setVisibility(t.canAct ? View.VISIBLE : View.GONE);
        inputBar.setVisibility(t.canAct ? View.VISIBLE : View.GONE);
    }

    // взять заявку себе (или перехватить, если прошло время). сервер сам проверяет можно ли
    private void claim() {
        ApiClient.getApiService(this).claimTicket(ticketId).enqueue(new Callback<Ticket>() {
            @Override
            public void onResponse(Call<Ticket> call, Response<Ticket> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.isSuccessful() && response.body() != null) {
                    bind(response.body());
                    pollMessages(); // бот уже написал в чат, что модератор подключился
                } else {
                    Toast.makeText(TicketActivity.this, ApiErrors.message(response, "Не удалось взять заявку"), Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<Ticket> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(TicketActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    // сообщение модератора в чат заявки
    private void send() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || ticket == null) return;
        // поле чищу сразу, не дожидаясь ответа - чтобы не было ощущения что всё зависло
        input.setText("");
        ApiClient.getApiService(this).sendMessage(ticket.threadId, new Chat.SendMessageRequest(text))
                .enqueue(new Callback<Chat.Message>() {
                    @Override
                    public void onResponse(Call<Chat.Message> call, Response<Chat.Message> response) {
                        if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                        if (response.isSuccessful() && response.body() != null) {
                            // appendMessages возвращает сколько реально добавилось (дубли он отсекает), скроллю только если что-то новое
                            if (adapter.appendMessages(Collections.singletonList(response.body())) > 0) scrollDown();
                        } else {
                            Toast.makeText(TicketActivity.this, ApiErrors.message(response, "Не удалось отправить"), Toast.LENGTH_LONG).show();
                        }
                    }

                    @Override
                    public void onFailure(Call<Chat.Message> call, Throwable t) {
                        if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                        Toast.makeText(TicketActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
                    }
                });
    }

    // решение: для возврата - полный / частичный / в пользу продавца, для жалобы - просто закрыть
    // варианты решения зависят от типа заявки
    private void showResolveDialog() {
        if (ticket == null) return;
        // labels - что видит модератор, codes - что уходит на сервер, индексы совпадают
        String[] labels, codes;
        switch (ticket.type) {
            case "REFUND":
                labels = new String[]{"Полный возврат покупателю", "Частичный возврат…", "В пользу продавца"};
                codes = new String[]{"FULL_REFUND", "PARTIAL_REFUND", "NO_REFUND"};
                break;
            case "COMPLAINT":
                labels = new String[]{"Закрыть (нарушения нет)", "⛔ Заблокировать нарушителя"};
                codes = new String[]{"CLOSED", "BLOCKED_USER"};
                break;
            case "UNBLOCK_APPEAL":
                labels = new String[]{"🔓 Разблокировать", "Оставить блокировку"};
                codes = new String[]{"UNBLOCKED", "CLOSED"};
                break;
            case "DECISION_APPEAL":
                labels = new String[]{"Решение было верным", "Решение ошибочное…"};
                codes = new String[]{"UPHELD", "OVERTURNED"};
                break;
            case "MODERATOR_REINSTATEMENT":
                labels = new String[]{"🛡️ Вернуть в модераторы", "Отказать"};
                codes = new String[]{"REINSTATED", "CLOSED"};
                break;
            case "ORDER_PROBLEM":
                // что можно сделать - зависит от того, где сейчас деньги
                String st = ticket.orderStatus == null ? "" : ticket.orderStatus;
                // PENDING_CONFIRMATION / DISPUTED - покупатель ещё не подтвердил, деньги заморожены у площадки
                if (st.equals("PENDING_CONFIRMATION") || st.equals("DISPUTED")) {
                    // заказ не подтверждён - деньги ещё у площадки
                    labels = new String[]{"Полный возврат покупателю", "Частичный возврат…", "В пользу продавца", "Закрыть без действий"};
                    codes = new String[]{"FULL_REFUND", "PARTIAL_REFUND", "NO_REFUND", "CLOSED"};
                } else if (st.equals("COMPLETED")) {
                    // заказ завершён - деньги уже у продавца
                    labels = new String[]{"Вернуть покупателю за счёт продавца…", "Компенсация от площадки…", "Закрыть без действий"};
                    codes = new String[]{"SELLER_REFUND", "COMPENSATED", "CLOSED"};
                } else {
                    labels = new String[]{"Компенсация от площадки…", "Закрыть без действий"};
                    codes = new String[]{"COMPENSATED", "CLOSED"};
                }
                break;
            default: // ACCOUNT_PROBLEM
                labels = new String[]{"Закрыть заявку"};
                codes = new String[]{"CLOSED"};
        }
        // список вариантов, по выбору - следующий диалог с комментарием/суммой
        new AlertDialog.Builder(this)
                .setTitle("Решение по заявке")
                .setItems(labels, (d, which) -> askNoteAndResolve(codes[which]))
                .setNegativeButton("Отмена", null)
                .show();
    }

    // второй диалог: сумма (если нужна по типу решения) + комментарий, плюс пояснение куда пойдут деньги.
    // разметку собираю кодом, а не xml - она маленькая и зависит от решения
    private void askNoteAndResolve(String code) {
        boolean partial = "PARTIAL_REFUND".equals(code);
        boolean compensation = "OVERTURNED".equals(code) || "COMPENSATED".equals(code);
        boolean sellerRefund = "SELLER_REFUND".equals(code);
        boolean block = "BLOCKED_USER".equals(code);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        // 20dp в пиксели
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText amount = new EditText(this);
        // поле суммы добавляю только для решений, где надо вводить деньги
        if (partial || compensation || sellerRefund) {
            amount.setHint(partial
                    ? String.format(Locale.getDefault(), "Сколько вернуть покупателю (меньше %.2f ₽)",
                            ticket.orderAmount == null ? 0 : ticket.orderAmount)
                    : sellerRefund ? "Сколько вернуть покупателю за счёт продавца, ₽"
                    : "OVERTURNED".equals(code) ? "Компенсация от площадки, ₽ (можно 0)"
                    : "Сколько выплатить от площадки, ₽");
            amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            box.addView(amount);
        }
        // частичный возврат: пока вводишь сумму, сразу видно, как разойдутся деньги
        TextView split = new TextView(this);
        if (partial) {
            split.setPadding(0, pad / 3, 0, pad / 3);
            split.setTextSize(15);
            split.setText("Введите, сколько вернуть покупателю - остальное получит продавец");
            box.addView(split);
            // на каждое изменение текста пересчитываю расклад денег под полем
            amount.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
                @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
                @Override
                public void afterTextChanged(android.text.Editable e) {
                    split.setText(partialSplit(e.toString()));
                }
            });
        }
        EditText note = new EditText(this);
        note.setHint(block ? "Причина блокировки (её увидит человек)" : "Комментарий для участников (необязательно)");
        box.addView(note);

        // пояснение для модератора, что именно произойдёт с деньгами при этом решении
        String message = sellerRefund ? "Деньги спишутся у продавца: сначала из замороженных по этому заказу, потом с баланса. "
                + "Не хватит - сервер скажет, сколько можно; остальное - компенсацией."
                : "COMPENSATED".equals(code) ? "Платит площадка из своих денег, у продавца ничего не забираем. Получит тот, кто подал заявку."
                : partial ? "Остаток уйдёт продавцу (заморожен на 48 ч), комиссия - только с этой части."
                : compensation ? "Деньги платит площадка, у продавца ничего не забираем. Модератору, вынесшему решение, это минус в рейтинг."
                : block ? "Если это модератор - он потеряет должность и попадёт в «Снятые»."
                : null;
        new AlertDialog.Builder(this)
                .setTitle(partial ? "Частичный возврат" : sellerRefund ? "Возврат за счёт продавца"
                        : "COMPENSATED".equals(code) ? "Компенсация от площадки"
                        : compensation ? "Решение ошибочное" : "Подтвердите решение")
                .setMessage(message)
                .setView(box)
                // нажали "Готово": проверяю сумму и причину, потом шлю решение
                .setPositiveButton("Готово", (d, w) -> {
                    Double value = null;
                    // запятую меняю на точку - на русской клавиатуре вводят "10,5", а parseDouble понимает только точку
                    String raw = amount.getText().toString().trim().replace(',', '.');
                    boolean amountRequired = partial || sellerRefund || "COMPENSATED".equals(code);
                    // для OVERTURNED сумма необязательная (компенсация может быть 0), парсю только если что-то ввели
                    if (amountRequired || (compensation && !raw.isEmpty())) {
                        try {
                            value = Double.parseDouble(raw);
                        } catch (NumberFormatException e) {
                            Toast.makeText(this, "Введите сумму", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    String n = note.getText().toString().trim();
                    // причину блокировки требую, человек её увидит
                    if (block && n.length() < 3) {
                        Toast.makeText(this, "Укажите причину блокировки", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    resolve(code, value, n);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // быстро открыть профиль любого участника (там же кнопка блокировки)
    // собираю список людей в заявке без повторов (заявитель может быть и покупателем, тогда второй раз не пишу)
    private void showPeople() {
        if (ticket == null) return;
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Long> ids = new java.util.ArrayList<>();
        labels.add("Заявитель: " + ticket.reporterNickname); ids.add(ticket.reporterId);
        if (ticket.buyerId != null && !ticket.buyerId.equals(ticket.reporterId)) { labels.add("Покупатель: " + ticket.buyerNickname); ids.add(ticket.buyerId); }
        if (ticket.sellerId != null && !ticket.sellerId.equals(ticket.reporterId)) { labels.add("Продавец: " + ticket.sellerNickname); ids.add(ticket.sellerId); }
        if (ticket.reportedUserId != null) { labels.add("Нарушитель: " + ticket.reportedUserNickname); ids.add(ticket.reportedUserId); }
        new AlertDialog.Builder(this)
                .setTitle("Участники")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    android.content.Intent i = new android.content.Intent(this, com.lunarforge.market.ui.profile.PublicProfileActivity.class);
                    // longValue - чтобы ушёл именно long, профиль читает getLongExtra
                    i.putExtra(com.lunarforge.market.ui.profile.PublicProfileActivity.EXTRA_USER_ID, ids.get(which).longValue());
                    startActivity(i);
                })
                .show();
    }

    // отправка решения. пустой комментарий шлю как null
    private void resolve(String code, Double amount, String note) {
        ApiClient.getApiService(this).resolveTicket(ticketId, new Ticket.ResolveRequest(code, amount, note.isEmpty() ? null : note))
                .enqueue(new Callback<Ticket>() {
                    @Override
                    public void onResponse(Call<Ticket> call, Response<Ticket> response) {
                        if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                        if (response.isSuccessful() && response.body() != null) {
                            // обновляю экран по ответу и тяну сообщения - бот пишет в чат итог решения
                            bind(response.body());
                            pollMessages();
                            Toast.makeText(TicketActivity.this, "Решение принято", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(TicketActivity.this, ApiErrors.message(response, "Не удалось"), Toast.LENGTH_LONG).show();
                        }
                    }

                    @Override
                    public void onFailure(Call<Ticket> call, Throwable t) {
                        if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                        Toast.makeText(TicketActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
                    }
                });
    }

    // вся переписка целиком (after = null)
    private void loadAllMessages() {
        ApiClient.getApiService(this).messages(ticket.threadId, null).enqueue(new Callback<List<Chat.Message>>() {
            @Override
            public void onResponse(Call<List<Chat.Message>> call, Response<List<Chat.Message>> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.isSuccessful() && response.body() != null) {
                    adapter.submitList(response.body());
                    scrollDown();
                }
            }

            @Override
            // ошибку тут молча пропускаю - через 4 секунды опрос попробует снова
            public void onFailure(Call<List<Chat.Message>> call, Throwable t) { }
        });
    }

    // новые сообщения + заодно обновляем саму заявку (могли перехватить / закрыть)
    private void pollMessages() {
        if (ticket == null) return;
        // время последнего сообщения в списке - сервер отдаст только то, что новее
        String after = adapter.lastMessageTime();
        // если сообщений ещё нет совсем - грузим всё
        if (after == null) {
            loadAllMessages();
        } else {
            ApiClient.getApiService(this).messages(ticket.threadId, after).enqueue(new Callback<List<Chat.Message>>() {
                @Override
                public void onResponse(Call<List<Chat.Message>> call, Response<List<Chat.Message>> response) {
                    if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                    if (response.isSuccessful() && response.body() != null && adapter.appendMessages(response.body()) > 0) {
                        scrollDown();
                    }
                }

                @Override
                public void onFailure(Call<List<Chat.Message>> call, Throwable t) { }
            });
        }
        loadTicket();
    }

    // прокрутить в самый низ к последнему сообщению
    private void scrollDown() {
        if (!adapter.isEmpty()) messagesView.scrollToPosition(adapter.getItemCount() - 1);
    }

    // тот же расчёт, что на сервере (OrderService.resolveDispute), на BigDecimal - чтобы копейки совпали:
    // комиссия с остатка = комиссия заказа * остаток / сумма заказа (до копеек, HALF_UP)
    private CharSequence partialSplit(String raw) {
        // считаю на BigDecimal, а не double - на double 0.1+0.2 уже не ровно 0.3, и копейки разошлись бы с сервером
        if (ticket == null || ticket.orderAmount == null) return "";
        java.math.BigDecimal amount = java.math.BigDecimal.valueOf(ticket.orderAmount).setScale(2, java.math.RoundingMode.HALF_UP);
        java.math.BigDecimal refund;
        try {
            refund = new java.math.BigDecimal(raw.trim().replace(',', '.')).setScale(2, java.math.RoundingMode.HALF_UP);
        // ввели что-то не похожее на число (или пусто) - просто подсказка
        } catch (NumberFormatException ex) {
            return "Введите, сколько вернуть покупателю - остальное получит продавец";
        }
        if (refund.signum() <= 0) return "Сумма должна быть больше нуля";
        // сумма возврата должна быть строго меньше суммы заказа, для полного возврата есть отдельный вариант
        if (refund.compareTo(amount) >= 0) return "Должно быть меньше суммы заказа (" + amount + " ₽). Для полного - «Полный возврат»";
        // остаток после возврата делится между продавцом и комиссией площадки
        java.math.BigDecimal rest = amount.subtract(refund);
        java.math.BigDecimal commission = ticket.orderCommission == null ? java.math.BigDecimal.ZERO
                : java.math.BigDecimal.valueOf(ticket.orderCommission).setScale(2, java.math.RoundingMode.HALF_UP);
        java.math.BigDecimal commissionPart = commission.multiply(rest).divide(amount, 2, java.math.RoundingMode.HALF_UP);
        // продавцу = остаток минус доля комиссии
        java.math.BigDecimal seller = rest.subtract(commissionPart);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append(Ui.labelValue(this, "Покупателю: ", refund + " ₽")).append('\n')
                .append(Ui.labelValue(this, "Продавцу: ", seller + " ₽")).append(" (заморожено на 48 ч)\n")
                .append("Комиссия площадки: ").append(commissionPart.toPlainString()).append(" ₽");
        return sb;
    }
}

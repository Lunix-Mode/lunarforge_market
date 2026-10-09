package com.lunarforge.market.ui.staff;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lunarforge.market.R;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Ticket;
import com.lunarforge.market.util.ApiErrors;
import com.lunarforge.market.util.BaseActivity;
import com.lunarforge.market.util.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// список заявок для модераторов и админа.
// "Новые" - никем не взятые + те, которые можно перехватить (ответственный молчит 8ч), "Мои", "Все"
// сверху чипы-вкладки (новые/мои/в работе/решённые/все), под ними два спиннера: тип заявки и кто подал.
// все фильтры уходят на сервер параметрами запроса, сам список фильтрует сервер, а не телефон.
// тап по заявке открывает TicketActivity, где модератор уже что-то решает
public class TicketsActivity extends BaseActivity {

    // админ или модератор - передаёт экран, который открывает этот
    public static final String EXTRA_IS_ADMIN = "is_admin";

    // текущая вкладка, по умолчанию "Новые"
    private String filter = "new";
    private String typeFilter = null;   // null = все типы
    private String partyFilter = null;  // null = неважно кто подал
    // подписи и коды для спиннеров идут в одном порядке, позиция в спиннере = индекс в массиве кодов.
    // null в кодах = фильтр не применяется
    private static final String[] TYPE_LABELS = {"Все типы", "Возврат", "Проблема с заказом", "Жалоба", "Аккаунт",
            "Разблокировка", "Обжалование", "Возврат в модераторы"};
    private static final String[] TYPE_CODES = {null, "REFUND", "ORDER_PROBLEM", "COMPLAINT", "ACCOUNT_PROBLEM",
            "UNBLOCK_APPEAL", "DECISION_APPEAL", "MODERATOR_REINSTATEMENT"};
    private static final String[] PARTY_LABELS = {"Кто подал: все", "Покупатель", "Продавец"};
    private static final String[] PARTY_CODES = {null, "BUYER", "SELLER"};
    private final TicketAdapter adapter = new TicketAdapter();
    private TextView emptyText;

    // тут только настраиваю экран. сам список гружу в onResume, он вызывается и при первом открытии тоже
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tickets);
        ((TextView) findViewById(R.id.titleText)).setText("Заявки");
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        emptyText = findViewById(R.id.emptyText);

        RecyclerView list = findViewById(R.id.ticketsRecyclerView);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.filterOpen).setOnClickListener(v -> setFilter("new"));
        findViewById(R.id.filterMine).setOnClickListener(v -> setFilter("mine"));
        findViewById(R.id.filterInProgress).setOnClickListener(v -> setFilter("in_progress"));
        findViewById(R.id.filterResolved).setOnClickListener(v -> setFilter("resolved"));
        findViewById(R.id.filterAll).setOnClickListener(v -> setFilter("all"));

        // "Возврат в модераторы" видит только админ - модераторам этот пункт не показываем
        boolean admin = getIntent().getBooleanExtra(EXTRA_IS_ADMIN, false);
        // "Возврат в модераторы" стоит последним в массиве, поэтому модератору просто обрезаю массив на один элемент
        int typeCount = admin ? TYPE_LABELS.length : TYPE_LABELS.length - 1;
        android.widget.Spinner typeSpinner = findViewById(R.id.typeSpinner);
        typeSpinner.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                java.util.Arrays.copyOf(TYPE_LABELS, typeCount)));
        typeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                String code = TYPE_CODES[pos];
                // спиннер вызывает onItemSelected сразу при установке адаптера (позиция 0). если фильтр не поменялся -
                // ничего не гружу, иначе при открытии экрана был бы лишний запрос
                if (java.util.Objects.equals(code, typeFilter)) return;
                typeFilter = code;
                load();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        android.widget.Spinner partySpinner = findViewById(R.id.partySpinner);
        partySpinner.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, PARTY_LABELS));
        partySpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                String code = PARTY_CODES[pos];
                if (java.util.Objects.equals(code, partyFilter)) return;
                partyFilter = code;
                load();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        highlightFilter();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load(); // вернулись из заявки - статусы могли поменяться
    }

    // переключение вкладки: подсвечиваю выбранный чип и перезагружаю список
    private void setFilter(String f) {
        filter = f;
        highlightFilter();
        load();
    }

    // выбранный чип - с фоном акцента и контрастным текстом, остальные обычные.
    // ids и keys идут в одинаковом порядке
    private void highlightFilter() {
        int[] ids = {R.id.filterOpen, R.id.filterMine, R.id.filterInProgress, R.id.filterResolved, R.id.filterAll};
        String[] keys = {"new", "mine", "in_progress", "resolved", "all"};
        for (int i = 0; i < ids.length; i++) {
            TextView chip = findViewById(ids[i]);
            boolean on = keys[i].equals(filter);
            chip.setBackgroundResource(on ? R.drawable.bg_chip_selected : R.drawable.bg_search_field);
            chip.setTextColor(on ? androidx.core.content.ContextCompat.getColor(this, R.color.c_on_accent)
                    : androidx.core.content.ContextCompat.getColor(this, R.color.c_text));
        }
    }

    // загрузка заявок с сервера с текущими фильтрами. если пусто - показываю надпись "заявок нет"
    private void load() {
        ApiClient.getApiService(this).staffTickets(filter, typeFilter, partyFilter).enqueue(new Callback<List<Ticket>>() {
            @Override
            public void onResponse(Call<List<Ticket>> call, Response<List<Ticket>> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.isSuccessful() && response.body() != null) {
                    adapter.submit(response.body());
                    emptyText.setVisibility(response.body().isEmpty() ? View.VISIBLE : View.GONE);
                } else {
                    Toast.makeText(TicketsActivity.this, ApiErrors.message(response, "Не удалось загрузить заявки"), Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<List<Ticket>> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(TicketsActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    // адаптер списка заявок. внутренний (не static) класс, потому что ему нужен контекст активити для Ui.labelValue и Intent
    private class TicketAdapter extends RecyclerView.Adapter<TicketAdapter.VH> {
        private final List<Ticket> items = new ArrayList<>();

        // просто заменяю весь список - заявок немного, перерисовать всё дешевле, чем считать разницу
        void submit(List<Ticket> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_ticket, parent, false));
        }

        // заполняю карточку заявки: номер, тип и кто подал; заказ или на кого жалоба; причина; статус
        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Ticket t = items.get(position);
            h.title.setText("#" + t.id + " · " + Ui.ticketTypeLong(t.type)
                    + ("BUYER".equals(t.reporterRole) ? " · покупатель" : "SELLER".equals(t.reporterRole) ? " · продавец" : ""));
            // если заявка по заказу - показываю заказ, сумму и покупатель -> продавец.
            // сумма может прийти null, тогда пишу 0, иначе String.format упал бы
            if (t.orderId != null) {
                h.subtitle.setText(String.format(Locale.getDefault(), "Заказ #%d «%s» · %.2f ₽\n%s → %s",
                        t.orderId, t.orderTitle, t.orderAmount == null ? 0 : t.orderAmount,
                        t.buyerNickname, t.sellerNickname));
            } else {
                // заявки без заказа (жалоба на юзера и т.п.) - показываю на кого
                h.subtitle.setText(Ui.labelValue(TicketsActivity.this, "На ", t.reportedUserNickname));
            }
            h.reason.setText(t.reporterNickname + ": " + t.reason);
            String status = Ui.ticketStatus(t.status);
            // у открытой заявки показываю, кто ей занимается (модератор или админ), у закрытой - чем закончилась
            if (t.assigneeNickname != null && !"RESOLVED".equals(t.status)) {
                status += " · " + Ui.staffLabel(t.assigneeRole, t.assigneeNickname);
            }
            if ("RESOLVED".equals(t.status)) status += " · " + Ui.resolution(t.resolution);
            h.status.setText(status);
            // по тапу открываю саму заявку, передаю только id - всё остальное TicketActivity загрузит сама
            h.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(TicketsActivity.this, TicketActivity.class);
                intent.putExtra(TicketActivity.EXTRA_TICKET_ID, t.id);
                startActivity(intent);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        // держу ссылки на текстовые поля карточки, чтобы не искать их через findViewById при каждой прокрутке
        class VH extends RecyclerView.ViewHolder {
            final TextView title, subtitle, reason, status;

            VH(@NonNull View v) {
                super(v);
                title = v.findViewById(R.id.ticketTitle);
                subtitle = v.findViewById(R.id.ticketSubtitle);
                reason = v.findViewById(R.id.ticketReason);
                status = v.findViewById(R.id.ticketStatus);
            }
        }
    }
}

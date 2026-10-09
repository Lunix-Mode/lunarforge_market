package com.lunarforge.market.ui.order;

import com.lunarforge.market.util.ApiErrors;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.lunarforge.market.R;
import com.lunarforge.market.adapter.OrderAdapter;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Order;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// список моих заказов. один экран на два случая: "мои покупки" и "мои продажи",
// какой именно - передаётся через Intent (EXTRA_MODE). тап по заказу открывает его статус.
// список грузится порциями, как и витрина
public class OrderListActivity extends com.lunarforge.market.util.BaseActivity {

    // порции: сколько влезает на экран + 50%, следующая грузится заранее при прокрутке
    private int pageSize;
    private int page = 0;
    private boolean hasMore = false, loading = false;
    // номер "поколения" загрузки. при обновлении с начала увеличиваю его, и если потом придёт ответ
    // от старого запроса (номер не совпал) - выкидываю его, иначе старая порция могла бы влезть в новый список
    private int generation = 0;
    public static final String EXTRA_MODE = "mode";
    public static final String MODE_PURCHASES = "purchases";
    public static final String MODE_SALES = "sales";

    private OrderAdapter adapter;
    private SwipeRefreshLayout swipeRefresh;
    private TextView emptyText;
    private String mode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_order_list);

        mode = getIntent().getStringExtra(EXTRA_MODE);
        boolean isPurchases = MODE_PURCHASES.equals(mode);

        ((TextView) findViewById(R.id.titleText)).setText(isPurchases ? "Мои покупки" : "Мои продажи");
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new OrderAdapter(order -> {
            Intent intent = new Intent(this, OrderStatusActivity.class);
            intent.putExtra(OrderStatusActivity.EXTRA_ORDER_ID, order.id);
            startActivity(intent);
        });
        recyclerView.setAdapter(adapter);
        // 90 - примерная высота карточки заказа в dp, по ней считается размер порции
        pageSize = com.lunarforge.market.util.Paging.pageSize(this, 90);
        // когда долистали почти до конца - грузим следующую порцию
        com.lunarforge.market.util.Paging.onNearEnd(recyclerView, this::loadMore);

        emptyText = findViewById(R.id.emptyText);
        emptyText.setText(isPurchases ? "Вы пока ничего не покупали" : "У вас пока нет продаж");
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setOnRefreshListener(this::load);

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // перезагружаю при каждом возврате на экран - например вернулся из заказа, где подтвердил получение,
        // и статус в списке должен обновиться. при первом открытии load() вызовется и тут, и в onCreate,
        // но ответ первого запроса отбросится из-за generation
        load();
    }

    // с начала (открыли экран или потянули "обновить")
    private void load() {
        generation++;
        hasMore = false;
        // сбрасываю loading, чтобы новый запрос точно ушёл, даже если старый ещё висит
        loading = false;
        fetch(0);
    }

    // следующая порция. если уже грузим или сервер сказал что больше нет - ничего не делаю
    private void loadMore() {
        if (loading || !hasMore) return;
        fetch(page + 1);
    }

    // запрос порции номер p. gen запоминаю в момент отправки, чтобы в ответе сравнить с текущим
    private void fetch(int p) {
        loading = true;
        int gen = generation;
        if (p == 0) swipeRefresh.setRefreshing(true);
        // в зависимости от режима зову один из двух эндпоинтов, колбэк у них общий
        (MODE_PURCHASES.equals(mode)
                ? ApiClient.getApiService(this).myPurchasesPage(p, pageSize)
                : ApiClient.getApiService(this).mySalesPage(p, pageSize)).enqueue(new Callback<List<Order>>() {
            @Override
            public void onResponse(Call<List<Order>> call, Response<List<Order>> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (gen != generation) return;
                loading = false;
                swipeRefresh.setRefreshing(false);
                if (!response.isSuccessful() || response.body() == null) return;
                List<Order> items = response.body();
                page = p;
                hasMore = items.size() >= pageSize; // пришла полная порция - значит, может быть ещё
                // первая порция заменяет список целиком, остальные дописываются в конец
                if (p == 0) {
                    adapter.submitList(items);
                    emptyText.setVisibility(items.isEmpty() ? TextView.VISIBLE : TextView.GONE);
                } else {
                    adapter.appendList(items);
                }
            }

            @Override
            public void onFailure(Call<List<Order>> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (gen != generation) return;
                loading = false;
                swipeRefresh.setRefreshing(false);
                Toast.makeText(OrderListActivity.this, ApiErrors.network(t), Toast.LENGTH_SHORT).show();
            }
        });
    }
}

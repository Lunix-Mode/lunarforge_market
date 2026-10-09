package com.lunarforge.market.ui.wallet;

import com.lunarforge.market.util.ApiErrors;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.lunarforge.market.R;
import com.lunarforge.market.adapter.TransactionAdapter;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.WalletTransaction;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// экран "история операций" кошелька: пополнения, покупки, продажи, разморозки и т.д.
// список грузится страницами (бесконечная прокрутка) + свайп вниз для обновления.
// разметку беру общую со списком заказов (activity_order_list), там всё то же самое
public class TransactionHistoryActivity extends com.lunarforge.market.util.BaseActivity {

    // порции: сколько влезает на экран + 50%, следующая грузится заранее при прокрутке
    private int pageSize;
    // номер последней загруженной страницы
    private int page = 0;
    // hasMore - есть ли ещё что грузить, loading - запрос уже идёт (чтобы не слать второй такой же)
    private boolean hasMore = false, loading = false;
    // "поколение" загрузки. при обновлении увеличиваю, и ответы от старых запросов просто игнорируются -
    // иначе запоздавшая вторая страница могла бы дописаться к уже обновлённому списку
    private int generation = 0;
    private TransactionAdapter adapter;
    private SwipeRefreshLayout swipeRefresh;
    private TextView emptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_order_list);

        ((TextView) findViewById(R.id.titleText)).setText("История операций");
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TransactionAdapter();
        recyclerView.setAdapter(adapter);
        // 72 - примерная высота одной строки в dp, по ней Paging считает сколько строк влезет
        pageSize = com.lunarforge.market.util.Paging.pageSize(this, 72);
        // когда долистали почти до конца - подгружаем следующую страницу
        com.lunarforge.market.util.Paging.onNearEnd(recyclerView, this::loadMore);

        emptyText = findViewById(R.id.emptyText);
        emptyText.setText("Операций пока нет");
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setOnRefreshListener(this::load);

        load();
    }

    // с начала (открыли экран или потянули "обновить")
    private void load() {
        // новое поколение - все ответы на старые запросы теперь будут выкинуты
        generation++;
        hasMore = false;
        loading = false;
        fetch(0);
    }

    // следующая страница. если уже грузим или больше нечего - ничего не делаю
    private void loadMore() {
        if (loading || !hasMore) return;
        fetch(page + 1);
    }

    // сам запрос страницы p к серверу (асинхронно через enqueue, ответ придёт в главный поток)
    private void fetch(int p) {
        loading = true;
        // запоминаю поколение на момент отправки, чтобы в ответе сравнить
        int gen = generation;
        // крутилку показываю только для первой страницы, при догрузке она не нужна
        if (p == 0) swipeRefresh.setRefreshing(true);
        ApiClient.getApiService(this).myTransactionsPage(p, pageSize).enqueue(new Callback<List<WalletTransaction>>() {
            @Override
            public void onResponse(Call<List<WalletTransaction>> call, Response<List<WalletTransaction>> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                // ответ от устаревшего запроса (пока ждали, юзер обновил список) - выкидываю
                if (gen != generation) return;
                loading = false;
                swipeRefresh.setRefreshing(false);
                if (!response.isSuccessful() || response.body() == null) return;
                List<WalletTransaction> items = response.body();
                page = p;
                hasMore = items.size() >= pageSize; // пришла полная порция - значит, может быть ещё
                if (p == 0) {
                    // первая страница заменяет список целиком, и тут же решаю показывать ли "Операций пока нет"
                    adapter.submitList(items);
                    emptyText.setVisibility(items.isEmpty() ? TextView.VISIBLE : TextView.GONE);
                } else {
                    // следующие страницы дописываются в конец
                    adapter.appendList(items);
                }
            }

            @Override
            public void onFailure(Call<List<WalletTransaction>> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (gen != generation) return;
                // сбрасываю loading, чтобы при следующей прокрутке можно было попробовать ещё раз
                loading = false;
                swipeRefresh.setRefreshing(false);
                Toast.makeText(TransactionHistoryActivity.this, ApiErrors.network(t), Toast.LENGTH_SHORT).show();
            }
        });
    }
}

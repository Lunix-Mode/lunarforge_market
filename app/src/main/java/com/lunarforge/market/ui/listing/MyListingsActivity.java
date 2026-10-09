package com.lunarforge.market.ui.listing;

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
import com.lunarforge.market.adapter.ListingAdapter;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Listing;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// экран "Мои товары" - список лотов, которые выставил я сам. разметку беру от списка заказов, она подходит
public class MyListingsActivity extends com.lunarforge.market.util.BaseActivity {
    private ListingAdapter adapter;
    private SwipeRefreshLayout swipeRefresh;
    private TextView emptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_order_list);

        ((TextView) findViewById(R.id.titleText)).setText("Мои товары");
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        // по нажатию открываю карточку лота, там же его можно редактировать
        adapter = new ListingAdapter(listing -> {
            Intent intent = new Intent(this, ProductDetailActivity.class);
            intent.putExtra(ProductDetailActivity.EXTRA_LISTING_ID, listing.id);
            startActivity(intent);
        });
        recyclerView.setAdapter(adapter);

        emptyText = findViewById(R.id.emptyText);
        emptyText.setText("У вас пока нет товаров");
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setOnRefreshListener(this::load);

        load();
    }

    // onResume срабатывает и после возврата с карточки - список обновится, если я лот поменял или удалил.
    // при первом открытии из-за этого грузится два раза (onCreate + onResume), это не страшно
    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    // запрос асинхронный (enqueue), ответ придёт в главный поток в onResponse/onFailure
    private void load() {
        swipeRefresh.setRefreshing(true);
        ApiClient.getApiService(this).myListings().enqueue(new Callback<List<Listing>>() {
            @Override
            public void onResponse(Call<List<Listing>> call, Response<List<Listing>> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                swipeRefresh.setRefreshing(false);
                if (response.isSuccessful() && response.body() != null) {
                    adapter.submitList(response.body());
                    // пустой список - показываю надпись "пока нет товаров"
                    emptyText.setVisibility(response.body().isEmpty() ? TextView.VISIBLE : TextView.GONE);
                }
            }

            @Override
            public void onFailure(Call<List<Listing>> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                swipeRefresh.setRefreshing(false);
                Toast.makeText(MyListingsActivity.this, ApiErrors.network(t), Toast.LENGTH_SHORT).show();
            }
        });
    }
}

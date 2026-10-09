package com.lunarforge.market.ui.listing;

import com.lunarforge.market.util.ApiErrors;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.lunarforge.market.R;
import com.lunarforge.market.adapter.CategoryChipAdapter;
import com.lunarforge.market.adapter.ListingAdapter;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Category;
import com.lunarforge.market.model.Listing;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// список товаров. открывается в двух режимах:
// 1) нажали на игру на главной - передаётся EXTRA_GAME_ID, сверху чипы категорий;
// 2) поиск с главной - передаётся EXTRA_SEARCH_QUERY, ищем по всем играм.
// фильтры (поиск, тип, сортировка, категория) уходят на сервер, список грузится страницами
public class GameListingsActivity extends com.lunarforge.market.util.BaseActivity {
    // ключи для Intent, через них главный экран передаёт сюда данные
    public static final String EXTRA_GAME_ID = "game_id";
    public static final String EXTRA_GAME_NAME = "game_name";
    public static final String EXTRA_SEARCH_QUERY = "search_query";

    private ListingAdapter adapter;
    private CategoryChipAdapter categoryAdapter;
    private SwipeRefreshLayout swipeRefresh;
    private TextView emptyText;
    // -1 значит игры нет (режим поиска по всем играм)
    private long gameId = -1;
    private String searchQuery;

    // выбранная категория, null = "Все" (или "Другое", если selectedIsOther)
    private Long selectedCategoryId = null;

    // порции: сколько влезает на экран + 50%; следующая грузится заранее при прокрутке
    private int pageSize;
    private int page = 0;
    private boolean hasMore = false, loading = false;
    private int generation = 0; // меняется при смене фильтров - ответы для старых фильтров выбрасываем
    // Handler на главном потоке для "debounce": не дёргаю сервер на каждую букву в поиске,
    // а жду паузу в наборе и только потом перезагружаю
    private final android.os.Handler debounce = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable reloadRunnable = this::load;
    private android.widget.EditText listingSearchEditText;
    private android.widget.Spinner typeFilterSpinner, sortSpinner;
    // подписи для спиннеров и соответствующие коды, которые понимает сервер. индексы совпадают,
    // null у типа = без фильтра
    private static final String[] TYPE_LABELS = {"Все типы", "Предметы", "Услуги", "Донат"};
    private static final String[] TYPE_CODES = {null, "ITEM", "SERVICE", "DONATE"};
    private static final String[] SORT_LABELS = {"Сначала новые", "Сначала дешёвые", "Сначала дорогие"};
    private static final String[] SORT_CODES = {"new", "cheap", "expensive"};
    // выбран чип "Другое" - товары этой игры без категории
    private boolean selectedIsOther = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_listings);

        gameId = getIntent().getLongExtra(EXTRA_GAME_ID, -1);
        String gameName = getIntent().getStringExtra(EXTRA_GAME_NAME);
        searchQuery = getIntent().getStringExtra(EXTRA_SEARCH_QUERY);

        TextView titleText = findViewById(R.id.titleText);
        titleText.setText(searchQuery != null ? "Поиск: " + searchQuery : gameName);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        // "добавить товар" имеет смысл только внутри конкретной игры - сразу подставляю её в форму
        TextView addListingButton = findViewById(R.id.addListingButton);
        if (gameId != -1) {
            addListingButton.setOnClickListener(v -> {
                Intent intent = new Intent(this, AddProductActivity.class);
                intent.putExtra(AddProductActivity.EXTRA_PRESELECT_GAME_ID, gameId);
                startActivity(intent);
            });
        } else {
            addListingButton.setVisibility(TextView.GONE);
        }

        // горизонтальная лента чипов категорий - только в режиме игры, в поиске она не нужна
        RecyclerView categoriesRecyclerView = findViewById(R.id.categoriesRecyclerView);
        if (gameId != -1 && searchQuery == null) {
            categoriesRecyclerView.setLayoutManager(
                    new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            // нажали чип - запоминаю выбор и перезагружаю список с первой страницы
            categoryAdapter = new CategoryChipAdapter((categoryId, isOther) -> {
                selectedCategoryId = categoryId;
                selectedIsOther = isOther;
                load();
            });
            categoriesRecyclerView.setAdapter(categoryAdapter);
            loadCategories();
        } else {
            categoriesRecyclerView.setVisibility(RecyclerView.GONE);
        }

        // сам список товаров, по нажатию открываю карточку товара
        RecyclerView recyclerView = findViewById(R.id.listingsRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ListingAdapter(listing -> {
            Intent intent = new Intent(this, ProductDetailActivity.class);
            intent.putExtra(ProductDetailActivity.EXTRA_LISTING_ID, listing.id);
            startActivity(intent);
        });
        recyclerView.setAdapter(adapter);
        pageSize = com.lunarforge.market.util.Paging.pageSize(this, 120); // карточка товара ~120dp
        com.lunarforge.market.util.Paging.onNearEnd(recyclerView, this::loadMore);

        listingSearchEditText = findViewById(R.id.listingSearchEditText);
        typeFilterSpinner = findViewById(R.id.typeFilterSpinner);
        sortSpinner = findViewById(R.id.sortSpinner);
        typeFilterSpinner.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, TYPE_LABELS));
        sortSpinner.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, SORT_LABELS));
        // один слушатель на оба спиннера: выбрали что-то - перезагрузка без задержки.
        // через reloadSoon, а не load напрямую - спиннер дёргает onItemSelected сам при установке адаптера,
        // и так несколько вызовов подряд схлопываются в один запрос
        android.widget.AdapterView.OnItemSelectedListener reapply = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) { reloadSoon(0); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        };
        typeFilterSpinner.setOnItemSelectedListener(reapply);
        sortSpinner.setOnItemSelectedListener(reapply);
        // поиск по мере набора, с задержкой 350мс после последней буквы
        listingSearchEditText.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(android.text.Editable e) { reloadSoon(350); } // ждём, пока допечатают
        });

        emptyText = findViewById(R.id.emptyText);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setOnRefreshListener(this::load);

        load();
    }

    // убираю отложенную перезагрузку, иначе она могла бы сработать уже после закрытия экрана
    @Override
    protected void onDestroy() {
        super.onDestroy();
        debounce.removeCallbacks(reloadRunnable);
    }

    // категории выбранной игры для чипов
    private void loadCategories() {
        ApiClient.getApiService(this).categoriesByGame(gameId).enqueue(new Callback<List<Category>>() {
            @Override
            public void onResponse(Call<List<Category>> call, Response<List<Category>> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.isSuccessful() && response.body() != null) {
                    categoryAdapter.submitCategories(response.body());
                }
            }

            // не загрузились - останутся только стандартные чипы, список товаров всё равно работает
            @Override
            public void onFailure(Call<List<Category>> call, Throwable t) {
            }
        });
    }

    // отложенная перезагрузка: старую отменяю и ставлю новую. если вызовы идут часто -
    // выполнится только последний
    private void reloadSoon(long delayMs) {
        debounce.removeCallbacks(reloadRunnable);
        debounce.postDelayed(reloadRunnable, delayMs);
    }

    // с начала: фильтры поменялись или потянули "обновить"
    private void load() {
        generation++;
        page = 0;
        hasMore = false;
        loading = false;
        fetch(0);
    }

    // следующая страница при прокрутке, если ещё есть и сейчас ничего не грузится
    private void loadMore() {
        if (loading || !hasMore) return;
        fetch(page + 1);
    }

    // поиск, тип и сортировка теперь на сервере - в телефон приходит только нужная порция
    private void fetch(int p) {
        // защита: спиннеры при установке адаптера могут вызвать перезагрузку раньше, чем всё создано
        if (adapter == null || listingSearchEditText == null) return;
        loading = true;
        int gen = generation;
        if (p == 0) swipeRefresh.setRefreshing(true);
        // если в поле что-то набрали - ищу по нему, иначе по запросу с главного экрана (может быть null)
        String typed = listingSearchEditText.getText().toString().trim();
        String q = !typed.isEmpty() ? typed : searchQuery;
        // max(0, ...) - на случай если спиннер вернул -1 (ничего не выбрано)
        String type = TYPE_CODES[Math.max(0, typeFilterSpinner.getSelectedItemPosition())];
        String sort = SORT_CODES[Math.max(0, sortSpinner.getSelectedItemPosition())];
        Long game = gameId != -1 ? gameId : null;
        // при "Другое" categoryId не шлю, вместо этого флаг other = true
        ApiClient.getApiService(this).browseListings(game, selectedIsOther ? null : selectedCategoryId, selectedIsOther,
                q, type, sort, p, pageSize).enqueue(new Callback<com.lunarforge.market.model.PageResponse<Listing>>() {
            @Override
            public void onResponse(Call<com.lunarforge.market.model.PageResponse<Listing>> call,
                                   Response<com.lunarforge.market.model.PageResponse<Listing>> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (gen != generation) return; // пока грузилось - поменяли фильтры
                loading = false;
                swipeRefresh.setRefreshing(false);
                com.lunarforge.market.model.PageResponse<Listing> body = response.body();
                if (!response.isSuccessful() || body == null || body.items == null) {
                    Toast.makeText(GameListingsActivity.this, ApiErrors.message(response, "Не удалось загрузить товары"), Toast.LENGTH_SHORT).show();
                    return;
                }
                page = p;
                // тут сервер сам говорит есть ли ещё страницы (PageResponse.hasMore)
                hasMore = body.hasMore;
                // первая страница заменяет список, остальные дописываются в конец
                if (p == 0) adapter.submitList(body.items); else adapter.appendList(body.items);
                if (p == 0) emptyText.setVisibility(body.items.isEmpty() ? TextView.VISIBLE : TextView.GONE);
            }

            @Override
            public void onFailure(Call<com.lunarforge.market.model.PageResponse<Listing>> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (gen != generation) return;
                loading = false;
                swipeRefresh.setRefreshing(false);
                Toast.makeText(GameListingsActivity.this, ApiErrors.network(t), Toast.LENGTH_SHORT).show();
            }
        });
    }
}

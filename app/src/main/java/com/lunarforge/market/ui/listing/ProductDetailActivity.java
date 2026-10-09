package com.lunarforge.market.ui.listing;

import com.lunarforge.market.util.ApiErrors;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.lunarforge.market.R;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Chat;
import com.lunarforge.market.model.Listing;
import com.lunarforge.market.model.Order;
import com.lunarforge.market.ui.chat.ChatActivity;
import com.lunarforge.market.ui.order.OrderStatusActivity;
import com.lunarforge.market.util.SessionManager;

import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// экран одного товара: фото (листаются свайпом), цена, наличие, описание, продавец.
// чужой товар можно купить или написать продавцу, свой - скрыть/вернуть на витрину.
// открывается из витрины, id товара приходит в intent-е (EXTRA_LISTING_ID)
public class ProductDetailActivity extends com.lunarforge.market.util.BaseActivity {
    public static final String EXTRA_LISTING_ID = "listing_id";

    // загруженный товар, null пока не пришёл ответ сервера
    private Listing listing;
    // сколько штук покупаем, меняется кнопками +/-
    private int qty = 1;
    private SessionManager session;
    private TextView buyButton;

    // тут только разметка и кнопка назад, всё остальное рисую после загрузки товара в bind()
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_product_detail);
        // цвет под системными панелями как у фона экрана (метод из BaseActivity)
        useSurfaceColorBehindSystemBars();

        session = new SessionManager(this);
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        buyButton = findViewById(R.id.buyButton);

        // -1 если id вдруг не передали - тогда сервер вернёт 404 и экран закроется с тостом
        long listingId = getIntent().getLongExtra(EXTRA_LISTING_ID, -1);
        loadListing(listingId);
    }

    // запрос товара. retrofit enqueue - асинхронно, ответ приходит в главный поток в onResponse/onFailure
    private void loadListing(long id) {
        ApiClient.getApiService(this).getListing(id).enqueue(new Callback<Listing>() {
            @Override
            public void onResponse(Call<Listing> call, Response<Listing> response) {
                // если ушли с экрана до ответа - глайд упадёт, поэтому выходим
                if (isFinishing() || isDestroyed()) return;
                if (response.isSuccessful() && response.body() != null) {
                    listing = response.body();
                    bind(listing);
                } else {
                    Toast.makeText(ProductDetailActivity.this, "Товар не найден", Toast.LENGTH_SHORT).show();
                    // товара нет (удалили или кривой id) - показывать нечего, закрываю экран
                    finish();
                }
            }

            @Override
            public void onFailure(Call<Listing> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                // нет сети или сервер недоступен - тост с понятным текстом из ApiErrors
                Toast.makeText(ProductDetailActivity.this, ApiErrors.network(t), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // раскладываю данные товара по view
    private void bind(Listing l) {
        ((TextView) findViewById(R.id.titleText)).setText(l.title);
        ((TextView) findViewById(R.id.productTitleText)).setText(l.title);
        ((TextView) findViewById(R.id.categoryTypeText)).setText(
                typeLabel(l.type) + " · " + (l.categoryName != null ? l.categoryName : "Другое"));
        // показываю цену для покупателя (уже с комиссией 5%), а не цену продавца
        ((TextView) findViewById(R.id.productPriceText)).setText(
                String.format(Locale.getDefault(), "%.2f ₽ / шт.", l.buyerPrice));
        ((TextView) findViewById(R.id.productQuantityText)).setText(l.unlimited
                ? "В наличии: без ограничений"
                : String.format(Locale.getDefault(), "В наличии: %d шт.", l.quantity));
        ((TextView) findViewById(R.id.deliveryMethodText)).setText(
                l.deliveryMethod == null ? "Не указан" : l.deliveryMethod);
        ((TextView) findViewById(R.id.descriptionText)).setText(
                l.description == null || l.description.isEmpty() ? "Без описания" : l.description);
        // ник продавца красится акцентным цветом темы, аватар грузится через Avatars (если фото нет - буква ника)
        TextView sellerNicknameText = findViewById(R.id.sellerNicknameText);
        sellerNicknameText.setText(l.sellerNickname);
        sellerNicknameText.setTextColor(com.lunarforge.market.util.Ui.accent(this));
        com.lunarforge.market.util.Avatars.load((android.widget.ImageView) findViewById(R.id.sellerAvatarImageView), l.sellerAvatarUrl, l.sellerNickname);
        // вся строка продавца (аватар, "Продавец", ник) ведёт в его профиль; у кнопки "Написать" своё действие
        findViewById(R.id.sellerRow).setOnClickListener(v -> {
            Intent intent = new Intent(this, com.lunarforge.market.ui.profile.PublicProfileActivity.class);
            intent.putExtra(com.lunarforge.market.ui.profile.PublicProfileActivity.EXTRA_USER_ID, l.sellerId);
            startActivity(intent);
        });

        bindGallery(l);

        // сравниваю id продавца с моим id из сессии
        // свой товар: вместо покупки кнопка скрыть/показать, "написать" себе не нужна
        boolean isOwnListing = l.sellerId == session.getUserId();

        if (isOwnListing) {
            findViewById(R.id.qtyStepper).setVisibility(View.GONE);
            findViewById(R.id.chatWithSellerButton).setVisibility(View.GONE);
            // alpha сбрасываю - кнопка могла быть полупрозрачной от прошлого состояния
            buyButton.setAlpha(1f);
            buyButton.setText(l.active ? "Скрыть с витрины" : "Вернуть на витрину");
            buyButton.setOnClickListener(v -> toggleActive(!l.active));
        } else {
            // явно показываем, не надеемся на состояние по умолчанию
            findViewById(R.id.qtyStepper).setVisibility(View.VISIBLE);
            findViewById(R.id.chatWithSellerButton).setVisibility(View.VISIBLE);
            findViewById(R.id.qtyMinusButton).setOnClickListener(v -> changeQty(-1));
            findViewById(R.id.qtyPlusButton).setOnClickListener(v -> changeQty(+1));
            updateBuyButton();
            buyButton.setOnClickListener(v -> buy());
        }

        // страховка: даже если кнопку как-то нажали на своём товаре - не даю писать самому себе
        findViewById(R.id.chatWithSellerButton).setOnClickListener(v -> {
            if (isOwnListing) {
                Toast.makeText(this, "Это ваш собственный товар", Toast.LENGTH_SHORT).show();
                return;
            }
            startChatWithSeller(null, false);
        });
    }

    // после оплаты сразу в чат с продавцом, бот там уже написал про заказ
    // блокирую кнопку на время запроса, иначе двойной тап = две покупки и два списания
    private void buy() {
        buyButton.setEnabled(false);
        Order.CreateRequest request = new Order.CreateRequest(listing.id, qty);
        ApiClient.getApiService(this).purchase(request).enqueue(new Callback<Order>() {
            @Override
            public void onResponse(Call<Order> call, Response<Order> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                buyButton.setEnabled(true);
                if (response.isSuccessful() && response.body() != null) {
                    Toast.makeText(ProductDetailActivity.this, "Оплачено. Деньги заморожены до подтверждения", Toast.LENGTH_SHORT).show();
                    // заказ создан - открываю чат с продавцом и передаю id заказа, этот экран закрываю
                    startChatWithSeller(response.body().id, true);
                } else {
                    // текст ошибки берём с сервера (например "недостаточно средств"), если его нет - запасной
                    Toast.makeText(ProductDetailActivity.this, ApiErrors.message(response, "Не удалось купить: проверьте баланс"), Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<Order> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                buyButton.setEnabled(true);
                Toast.makeText(ProductDetailActivity.this, ApiErrors.network(t), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // кол-во от 1 до остатка на складе. у бесконечного товара потолок 999 просто чтобы был предел
    private void changeQty(int delta) {
        int max = listing.unlimited ? 999 : Math.max(1, listing.quantity);
        qty = Math.max(1, Math.min(max, qty + delta));
        updateBuyButton();
    }

    // обновляю цифру и итоговую сумму на кнопке
    private void updateBuyButton() {
        ((TextView) findViewById(R.id.qtyText)).setText(String.valueOf(qty));
        buyButton.setText(String.format(Locale.getDefault(), "Купить за %.2f ₽", listing.buyerPrice * qty));
    }

    // открыть (или найти существующий) чат с продавцом. сервер сам вернёт старый чат, если он уже есть.
    // orderId передаю в чат, если пришли после покупки; finishAfter - закрыть ли этот экран
    private void startChatWithSeller(Long orderId, boolean finishAfter) {
        Chat.StartThreadRequest request = new Chat.StartThreadRequest(listing.sellerId, listing.id);
        ApiClient.getApiService(this).startThread(request).enqueue(new Callback<Chat.Thread>() {
            @Override
            public void onResponse(Call<Chat.Thread> call, Response<Chat.Thread> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.isSuccessful() && response.body() != null) {
                    Intent intent = new Intent(ProductDetailActivity.this, ChatActivity.class);
                    intent.putExtra(ChatActivity.EXTRA_THREAD_ID, response.body().id);
                    intent.putExtra(ChatActivity.EXTRA_OTHER_NICKNAME, listing.sellerNickname);
                    intent.putExtra(ChatActivity.EXTRA_OTHER_USER_ID, listing.sellerId);
                    // longValue - иначе putExtra положит Long как Serializable, а чат читает getLongExtra
                    if (orderId != null) intent.putExtra(ChatActivity.EXTRA_ORDER_ID, orderId.longValue());
                    startActivity(intent);
                    if (finishAfter) finish();
                } else {
                    Toast.makeText(ProductDetailActivity.this, ApiErrors.message(response, "Не удалось открыть чат"), Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<Chat.Thread> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(ProductDetailActivity.this, ApiErrors.network(t), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // тип товара с сервера -> подпись на русском
    private String typeLabel(String type) {
        if (type == null) return "Предмет";
        switch (type) {
            case "SERVICE": return "Услуга";
            case "DONATE": return "Донат";
            default: return "Предмет";
        }
    }

    // скрыть/показать свой товар. распроданный вернуть нельзя - проверяю ещё тут, чтобы не гонять запрос зря
    // (на сервере та же проверка)
    private void toggleActive(boolean makeActive) {
        if (makeActive && !listing.unlimited && listing.quantity <= 0) {
            Toast.makeText(this, "Товара нет в наличии - создайте новое объявление", Toast.LENGTH_LONG).show();
            return;
        }
        buyButton.setEnabled(false);
        ApiClient.getApiService(this).setListingActive(listing.id, makeActive).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                buyButton.setEnabled(true);
                if (response.isSuccessful()) {
                    Toast.makeText(ProductDetailActivity.this,
                            makeActive ? "Товар снова на витрине" : "Товар скрыт - покупатели его не видят", Toast.LENGTH_SHORT).show();
                    // перезагружаю товар, чтобы кнопка и данные обновились по тому что реально сохранено
                    loadListing(listing.id);
                } else {
                    Toast.makeText(ProductDetailActivity.this, ApiErrors.message(response, "Не удалось изменить видимость"),
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                buyButton.setEnabled(true);
                Toast.makeText(ProductDetailActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    // главное фото + доп. фото, свайп, счётчик внизу справа
    private void bindGallery(Listing l) {
        // собираю список фото: главное + дополнительные, пустые ссылки пропускаю
        java.util.List<String> photos = new java.util.ArrayList<>();
        if (l.imageUrl != null && !l.imageUrl.isEmpty()) photos.add(l.imageUrl);
        if (l.extraImageUrls != null) {
            for (String u : l.extraImageUrls) if (u != null && !u.isEmpty()) photos.add(u);
        }
        // если фото нет совсем - кладу null, адаптер покажет заглушку, чтобы галерея не была пустой
        if (photos.isEmpty()) photos.add(null);

        androidx.recyclerview.widget.RecyclerView pager = findViewById(R.id.photosPager);
        TextView counter = findViewById(R.id.photoCounter);
        // настройку списка делаю один раз - bind вызывается снова после скрытия/показа товара,
        // и без этой проверки слушатели прокрутки добавлялись бы каждый раз заново
        if (pager.getAdapter() == null) {
            androidx.recyclerview.widget.LinearLayoutManager lm = new androidx.recyclerview.widget.LinearLayoutManager(
                    this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false);
            pager.setLayoutManager(lm);
            // PagerSnapHelper - после свайпа фото доводится ровно до центра, как в галерее
            new androidx.recyclerview.widget.PagerSnapHelper().attachToRecyclerView(pager);
            pager.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView rv, int dx, int dy) {
                    // номер фото, которое полностью видно. во время свайпа может быть -1, тогда не трогаю счётчик
                    int pos = lm.findFirstCompletelyVisibleItemPosition();
                    if (pos >= 0 && rv.getAdapter() != null) {
                        counter.setText((pos + 1) + "/" + rv.getAdapter().getItemCount());
                    }
                }
            });
        }
        // по тапу на фото - открываю его на весь экран
        com.lunarforge.market.adapter.PhotoPagerAdapter adapter =
                new com.lunarforge.market.adapter.PhotoPagerAdapter(position -> openFullScreen(photos.get(position)));
        pager.setAdapter(adapter);
        adapter.submit(photos);
        counter.setText("1/" + photos.size());
        // счётчик прячу, если фото одно
        counter.setVisibility(photos.size() > 1 ? View.VISIBLE : View.GONE);
    }

    // полноэкранный просмотр: простой Dialog с ImageView, тап по картинке - закрыть
    private void openFullScreen(String url) {
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(0xFF000000);
        image.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(image);
        // Glide грузит картинку в фоне и кэширует. absoluteUrl - сервер отдаёт ссылки вида /files/..., дописываю адрес сервера
        Glide.with(this).load(ApiClient.absoluteUrl(url)).into(image);
        dialog.show();
    }
}

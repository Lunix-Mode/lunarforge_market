package com.lunarforge.market.ui.profile;

import com.lunarforge.market.util.ApiErrors;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lunarforge.market.R;
import com.lunarforge.market.adapter.ListingAdapter;
import com.lunarforge.market.adapter.PublicReviewAdapter;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Listing;
import com.lunarforge.market.model.PublicProfile;
import com.lunarforge.market.model.Rating;
import com.lunarforge.market.ui.listing.ProductDetailActivity;
import com.lunarforge.market.util.BaseActivity;

import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// публичный профиль любого пользователя (продавца): ник, рейтинг, его товары и отзывы о нём.
// отсюда можно написать человеку или пожаловаться, а модератор/админ видит ещё
// кнопки блокировки и историю блокировок. id пользователя приходит через EXTRA_USER_ID
public class PublicProfileActivity extends BaseActivity {
    public static final String EXTRA_USER_ID = "user_id";

    private long userId;
    private PublicProfile profile;   // кого смотрим
    private String myRole;           // кто смотрит (USER / MODERATOR / ADMIN)
    private ListingAdapter listingAdapter;
    private PublicReviewAdapter reviewAdapter;
    private RecyclerView listingsRecyclerView, reviewsRecyclerView;
    private TextView listingsHeaderText, listingsToggleArrow, reviewsHeaderText, reviewsToggleArrow;
    // списки товаров и отзывов сворачиваются, по умолчанию оба свёрнуты, чтобы профиль был компактным
    private boolean listingsExpanded = false, reviewsExpanded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_public_profile);

        // без id показывать нечего - сразу закрываю
        userId = getIntent().getLongExtra(EXTRA_USER_ID, -1);
        if (userId == -1) {
            finish();
            return;
        }

        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        View writeButton = findViewById(R.id.writeButton);
        // себе писать нельзя, на своём профиле кнопку прячем (и жаловаться на себя тоже)
        if (userId == new com.lunarforge.market.util.SessionManager(this).getUserId()) {
            writeButton.setVisibility(View.GONE);
            findViewById(R.id.complainButton).setVisibility(View.GONE);
        } else {
            writeButton.setVisibility(View.VISIBLE);
            writeButton.setOnClickListener(v -> openChat());
            findViewById(R.id.complainButton).setOnClickListener(v -> complainDialog());
        }

        listingsHeaderText = findViewById(R.id.listingsHeaderText);
        listingsToggleArrow = findViewById(R.id.listingsToggleArrow);
        reviewsHeaderText = findViewById(R.id.reviewsHeaderText);
        reviewsToggleArrow = findViewById(R.id.reviewsToggleArrow);

        // товары продавца, по нажатию - карточка товара
        listingsRecyclerView = findViewById(R.id.listingsRecyclerView);
        listingsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        listingAdapter = new ListingAdapter(listing -> {
            Intent intent = new Intent(this, ProductDetailActivity.class);
            intent.putExtra(ProductDetailActivity.EXTRA_LISTING_ID, listing.id);
            startActivity(intent);
        });
        listingsRecyclerView.setAdapter(listingAdapter);

        reviewsRecyclerView = findViewById(R.id.reviewsRecyclerView);
        reviewsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        reviewAdapter = new PublicReviewAdapter();
        reviewsRecyclerView.setAdapter(reviewAdapter);

        // нажатие на заголовок секции - свернуть/развернуть
        findViewById(R.id.listingsHeader).setOnClickListener(v -> {
            listingsExpanded = !listingsExpanded;
            applyExpanded();
        });
        findViewById(R.id.reviewsHeader).setOnClickListener(v -> {
            reviewsExpanded = !reviewsExpanded;
            applyExpanded();
        });

        // четыре запроса параллельно: профиль, моя роль, товары, отзывы.
        // кнопки модерации зависят сразу от двух ответов (профиль + моя роль), поэтому setupStaffTools
        // вызывается из обоих мест, а сработает тот вызов, который придёт вторым
        loadProfile();
        ApiClient.getApiService(this).me().enqueue(new Callback<com.lunarforge.market.model.User>() {
            @Override
            public void onResponse(Call<com.lunarforge.market.model.User> call, Response<com.lunarforge.market.model.User> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.body() == null) return;
                myRole = response.body().role;
                setupStaffTools();
            }

            // не узнали роль - просто не будет кнопок модерации
            @Override
            public void onFailure(Call<com.lunarforge.market.model.User> call, Throwable t) { }
        });
        loadListings();
        loadReviews();
    }

    // показываю/прячу списки и меняю стрелочку в заголовке
    private void applyExpanded() {
        listingsRecyclerView.setVisibility(listingsExpanded ? View.VISIBLE : View.GONE);
        listingsToggleArrow.setText(listingsExpanded ? "▴" : "▾");
        reviewsRecyclerView.setVisibility(reviewsExpanded ? View.VISIBLE : View.GONE);
        reviewsToggleArrow.setText(reviewsExpanded ? "▴" : "▾");
    }

    // основная инфа о человеке. вызываю ещё и после блокировки/разблокировки, чтобы обновить баннер и кнопку
    private void loadProfile() {
        ApiClient.getApiService(this).publicProfile(userId).enqueue(new Callback<PublicProfile>() {
            @Override
            public void onResponse(Call<PublicProfile> call, Response<PublicProfile> response) {
                // профиль не пришёл (например такого id нет) - показывать нечего, закрываю экран
                if (!response.isSuccessful() || response.body() == null) {
                    Toast.makeText(PublicProfileActivity.this, "Профиль не найден", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                PublicProfile p = response.body();
                ((TextView) findViewById(R.id.titleText)).setText(p.nickname);
                ((TextView) findViewById(R.id.nicknameText)).setText(p.nickname);
                // значок роли показываю только у модераторов и админа
                TextView badge = findViewById(R.id.roleBadge);
                boolean staff = "MODERATOR".equals(p.role) || "ADMIN".equals(p.role);
                badge.setVisibility(staff ? View.VISIBLE : View.GONE);
                if (staff) badge.setText("ADMIN".equals(p.role) ? "👑 Создатель" : "🛡️ Модератор");
                // на создателя жаловаться нельзя (сервер тоже не примет)
                if ("ADMIN".equals(p.role)) findViewById(R.id.complainButton).setVisibility(View.GONE);
                profile = p;
                // красная плашка "пользователь заблокирован"
                findViewById(R.id.blockedBanner).setVisibility(p.blocked ? View.VISIBLE : View.GONE);
                setupStaffTools();
                // проверка isDestroyed перед загрузкой картинки: Glide падает, если активити уже уничтожена
                if (!isDestroyed()) { // без фото - сезонный круг с буквой
                    com.lunarforge.market.util.Avatars.load((android.widget.ImageView) findViewById(R.id.avatarImage), p.avatarUrl, p.nickname);
                }
                ((TextView) findViewById(R.id.usernameText)).setText("@" + p.username + " · ID " + p.id);
                // рейтинг с одним знаком после запятой. Locale - чтобы разделитель был как принято на телефоне
                ((TextView) findViewById(R.id.ratingText)).setText(p.ratingCount == 0
                        ? "★ — (пока нет отзывов)"
                        : String.format(Locale.getDefault(), "★ %.1f (%d отзывов)", p.ratingAverage, p.ratingCount));
                // дата приходит строкой вида 2026-01-15T..., беру только первые 10 символов (гггг-мм-дд)
                String date = p.registeredAt != null && p.registeredAt.length() >= 10 ? p.registeredAt.substring(0, 10) : "—";
                ((TextView) findViewById(R.id.registeredText)).setText("На платформе с " + date);
            }

            @Override
            public void onFailure(Call<PublicProfile> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(PublicProfileActivity.this, ApiErrors.network(t), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // товары продавца, в заголовке пишу их количество
    private void loadListings() {
        ApiClient.getApiService(this).listingsBySeller(userId).enqueue(new Callback<List<Listing>>() {
            @Override
            public void onResponse(Call<List<Listing>> call, Response<List<Listing>> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.isSuccessful() && response.body() != null) {
                    listingAdapter.submitList(response.body());
                    listingsHeaderText.setText("Товары (" + response.body().size() + ")");
                }
            }

            @Override
            public void onFailure(Call<List<Listing>> call, Throwable t) { }
        });
    }

    // отзывы о продавце (на бэке это findBySellerIdOrderByCreatedAtDesc - новые сверху)
    private void loadReviews() {
        ApiClient.getApiService(this).ratingsForSeller(userId).enqueue(new Callback<List<Rating>>() {
            @Override
            public void onResponse(Call<List<Rating>> call, Response<List<Rating>> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.isSuccessful() && response.body() != null) {
                    reviewAdapter.submitList(response.body());
                    reviewsHeaderText.setText("Отзывы (" + response.body().size() + ")");
                }
            }

            @Override
            public void onFailure(Call<List<Rating>> call, Throwable t) { }
        });
    }

    // открыть личный чат. startThread на сервере вернёт существующий чат или создаст новый,
    // второй параметр null - чат не привязан к товару
    private void openChat() {
        // ник беру прямо с экрана, чтобы показать его в шапке чата
        String nick = ((TextView) findViewById(R.id.nicknameText)).getText().toString();
        ApiClient.getApiService(this).startThread(new com.lunarforge.market.model.Chat.StartThreadRequest(userId, null))
                .enqueue(new Callback<com.lunarforge.market.model.Chat.Thread>() {
                    @Override
                    public void onResponse(Call<com.lunarforge.market.model.Chat.Thread> call,
                                           Response<com.lunarforge.market.model.Chat.Thread> response) {
                                               if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                        if (response.isSuccessful() && response.body() != null) {
                            Intent intent = new Intent(PublicProfileActivity.this, com.lunarforge.market.ui.chat.ChatActivity.class);
                            intent.putExtra(com.lunarforge.market.ui.chat.ChatActivity.EXTRA_THREAD_ID, response.body().id);
                            intent.putExtra(com.lunarforge.market.ui.chat.ChatActivity.EXTRA_OTHER_NICKNAME, nick);
                            intent.putExtra(com.lunarforge.market.ui.chat.ChatActivity.EXTRA_OTHER_USER_ID, userId);
                            startActivity(intent);
                        } else {
                            Toast.makeText(PublicProfileActivity.this,
                                    ApiErrors.message(response, "Не удалось открыть чат"), Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onFailure(Call<com.lunarforge.market.model.Chat.Thread> call, Throwable t) {
                        if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                        Toast.makeText(PublicProfileActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
                    }
                });
    }

    // жалоба на пользователя: диалог с полем ввода, отправка создаёт заявку для модераторов
    private void complainDialog() {
        android.widget.EditText reason = new android.widget.EditText(this);
        reason.setHint("Что произошло?");
        reason.setMinLines(3);
        // отступы 20dp вокруг поля ввода внутри диалога
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(reason);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Пожаловаться")
                .setMessage("Модератор посмотрит вашу переписку с этим человеком и разберётся.")
                .setView(box)
                .setPositiveButton("Отправить", (d, w) -> {
                    String text = reason.getText().toString().trim();
                    // слишком короткое описание не отправляю
                    if (text.length() < 5) {
                        Toast.makeText(this, "Опишите подробнее", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ApiClient.getApiService(this).complain(new com.lunarforge.market.model.Ticket.ComplaintRequest(userId, text))
                            .enqueue(new Callback<com.lunarforge.market.model.Ticket>() {
                                @Override
                                public void onResponse(Call<com.lunarforge.market.model.Ticket> call,
                                                       Response<com.lunarforge.market.model.Ticket> response) {
                                                           if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                                    // успех или текст ошибки от сервера (например жалоба уже подана)
                                    Toast.makeText(PublicProfileActivity.this, response.isSuccessful()
                                            ? "Жалоба отправлена модераторам"
                                            : ApiErrors.message(response, "Не удалось отправить"), Toast.LENGTH_LONG).show();
                                }

                                @Override
                                public void onFailure(Call<com.lunarforge.market.model.Ticket> call, Throwable t) {
                                    if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                                    Toast.makeText(PublicProfileActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
                                }
                            });
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // кнопки модерации. показываем, когда знаем и кто смотрит, и кого смотрим.
    // модератора напрямую блокирует только админ (остальные - через решение по жалобе)
    private void setupStaffTools() {
        // ещё не пришёл один из двух ответов - ждём второй
        if (profile == null || myRole == null) return;
        boolean meStaff = "MODERATOR".equals(myRole) || "ADMIN".equals(myRole);
        boolean self = userId == new com.lunarforge.market.util.SessionManager(this).getUserId();
        boolean targetStaff = "MODERATOR".equals(profile.role) || "ADMIN".equals(profile.role);
        // блокировать можно: я модератор/админ, это не я, это не админ,
        // и если цель модератор - то только когда я админ
        boolean canBlock = meStaff && !self && !"ADMIN".equals(profile.role) && (!targetStaff || "ADMIN".equals(myRole));
        findViewById(R.id.staffTools).setVisibility(meStaff && !self ? View.VISIBLE : View.GONE);
        // если человек уже заблокирован - показываю кнопку (теперь это "разблокировать"),
        // окончательно можно ли - всё равно проверит сервер
        TextView block = findViewById(R.id.blockButton);
        block.setVisibility(canBlock || profile.blocked ? View.VISIBLE : View.GONE);
        block.setText(profile.blocked ? "🔓 Разблокировать" : "⛔ Заблокировать");
        block.setOnClickListener(v -> {
            if (profile.blocked) unblockDialog(); else blockDialog();
        });
        findViewById(R.id.blockHistoryButton).setOnClickListener(v -> showBlockHistory());
    }

    // блокировка: причина обязательна, её увидит заблокированный на своём экране
    private void blockDialog() {
        android.widget.EditText reason = new android.widget.EditText(this);
        reason.setHint("Причина (её увидит человек)");
        reason.setMinLines(2);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(reason);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Заблокировать " + profile.nickname + "?")
                .setMessage("Человек сможет только подать заявку на разблокировку. Если её одобрит другой модератор, "
                        + "блокировка засчитается как необоснованная - это минус в ваш рейтинг.")
                .setView(box)
                .setPositiveButton("Заблокировать", (d, w) -> {
                    String text = reason.getText().toString().trim();
                    if (text.length() < 3) {
                        Toast.makeText(this, "Укажите причину", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ApiClient.getApiService(this).blockUser(userId, new com.lunarforge.market.model.Ticket.BlockRequest(text))
                            .enqueue(new Callback<com.lunarforge.market.model.Ticket.Block>() {
                                @Override
                                public void onResponse(Call<com.lunarforge.market.model.Ticket.Block> call,
                                                       Response<com.lunarforge.market.model.Ticket.Block> response) {
                                                           if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                                    Toast.makeText(PublicProfileActivity.this, response.isSuccessful() ? "Заблокирован"
                                            : ApiErrors.message(response, "Не удалось"), Toast.LENGTH_LONG).show();
                                    // перечитываю профиль - появится баннер и кнопка сменится на "разблокировать"
                                    if (response.isSuccessful()) loadProfile();
                                }

                                @Override
                                public void onFailure(Call<com.lunarforge.market.model.Ticket.Block> call, Throwable t) {
                                    if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                                    Toast.makeText(PublicProfileActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
                                }
                            });
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // разблокировка, комментарий необязательный (может уйти пустой строкой)
    private void unblockDialog() {
        android.widget.EditText reason = new android.widget.EditText(this);
        reason.setHint("Комментарий (необязательно)");
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(reason);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Разблокировать " + profile.nickname + "?")
                .setView(box)
                // сервер на успех ничего не возвращает, поэтому Callback<Void>
                .setPositiveButton("Разблокировать", (d, w) -> ApiClient.getApiService(this)
                        .unblockUser(userId, new com.lunarforge.market.model.Ticket.TextRequest(reason.getText().toString().trim()))
                        .enqueue(new Callback<Void>() {
                            @Override
                            public void onResponse(Call<Void> call, Response<Void> response) {
                                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                                Toast.makeText(PublicProfileActivity.this, response.isSuccessful() ? "Разблокирован"
                                        : ApiErrors.message(response, "Не удалось"), Toast.LENGTH_LONG).show();
                                if (response.isSuccessful()) loadProfile();
                            }

                            @Override
                            public void onFailure(Call<Void> call, Throwable t) {
                                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                                Toast.makeText(PublicProfileActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
                            }
                        }))
                .setNegativeButton("Отмена", null)
                .show();
    }

    // история блокировок человека - для модераторов. показываю простым текстом в диалоге
    private void showBlockHistory() {
        ApiClient.getApiService(this).userBlocks(userId).enqueue(new Callback<List<com.lunarforge.market.model.Ticket.Block>>() {
            @Override
            public void onResponse(Call<List<com.lunarforge.market.model.Ticket.Block>> call,
                                   Response<List<com.lunarforge.market.model.Ticket.Block>> response) {
                                       if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.body() == null) {
                    Toast.makeText(PublicProfileActivity.this, ApiErrors.message(response, "Не удалось"), Toast.LENGTH_LONG).show();
                    return;
                }
                // собираю текст через StringBuilder: когда, кто заблокировал, причина,
                // и если снята - кем/почему (по апелляции или вручную)
                StringBuilder sb = new StringBuilder();
                for (com.lunarforge.market.model.Ticket.Block b : response.body()) {
                    sb.append("• ").append(com.lunarforge.market.util.Ui.createdAgo(b.createdAt))
                            .append(" - ").append(b.blockedByLabel != null ? b.blockedByLabel : "?").append('\n')
                            .append("   «").append(b.reason).append("»\n");
                    if (b.liftedAt != null) {
                        sb.append("   снята").append(b.reversedByAppeal ? " по апелляции" : "")
                                .append(b.liftReason != null ? ": " + b.liftReason : "").append('\n');
                    }
                    sb.append('\n');
                }
                // пустой список - так и пишу, trim убирает лишний перенос в конце
                new androidx.appcompat.app.AlertDialog.Builder(PublicProfileActivity.this)
                        .setTitle("История блокировок")
                        .setMessage(sb.length() == 0 ? "Блокировок не было" : sb.toString().trim())
                        .setPositiveButton("OK", null)
                        .show();
            }

            @Override
            public void onFailure(Call<List<com.lunarforge.market.model.Ticket.Block>> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(PublicProfileActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }
}

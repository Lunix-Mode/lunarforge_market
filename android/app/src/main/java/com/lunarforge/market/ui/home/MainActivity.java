package com.lunarforge.market.ui.home;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.lunarforge.market.R;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.User;
import com.lunarforge.market.ui.chat.ChatListFragment;
import com.lunarforge.market.ui.listing.GameListingsActivity;
import com.lunarforge.market.ui.profile.ProfileFragment;
import com.lunarforge.market.util.SessionManager;
import com.lunarforge.market.util.ThemeManager;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// главный экран после входа. сам по себе это "рамка": сверху поиск и колокольчик,
// снизу три вкладки (главная / чаты / профиль), а середина - контейнер, куда я подставляю фрагменты.
// ещё тут боковое меню (бургер) со всеми разделами, выбор темы и опрос непрочитанных уведомлений
public class MainActivity extends com.lunarforge.market.util.BaseActivity {

    private boolean isAdmin = false; // из /me - для пунктов меню и фильтров заявок

    // колокольчик: опрашиваем число непрочитанных раз в 30 сек, пока экран открыт
    private static final long BELL_POLL_MS = 30_000;
    // Handler на главном потоке - через него ставлю отложенный повтор опроса
    private final android.os.Handler bellHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private long lastUnread = -1; // -1 = ещё ни разу не опрашивали
    // опрос сам себя перезапускает через 30 сек - получается бесконечный цикл, пока его не снимут в onPause
    private final Runnable bellPoll = new Runnable() {
        @Override
        public void run() {
            pollUnread();
            bellHandler.postDelayed(this, BELL_POLL_MS);
        }
    };
    // запрос разрешения на уведомления (android 13+). результат не важен: не дали - просто не показываем
    private final androidx.activity.result.ActivityResultLauncher<String> notifPermission =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(), granted -> { });
    // dimOverlay - затемнение под меню, по нажатию на него меню закрывается
    private View dimOverlay, slidingMenu;
    private TextView menuNicknameText;
    private SessionManager session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        useSurfaceColorBehindSystemBars();

        session = new SessionManager(this);

        // "назад": сначала закрываем боковое меню, и только потом выходим
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (slidingMenu.getVisibility() == View.VISIBLE) {
                    closeMenu();
                } else {
                    // меню закрыто - временно выключаю свой обработчик и отдаю "назад" системе,
                    // иначе он бы снова попал сюда и экран никогда не закрылся
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });
        // нижние вкладки - просто меняют фрагмент в середине экрана
        findViewById(R.id.homeButton).setOnClickListener(v -> showFragment(new HomeFragment()));
        findViewById(R.id.chatButton).setOnClickListener(v -> showFragment(new ChatListFragment()));
        // колокольчик - список уведомлений
        findViewById(R.id.notifierButton).setOnClickListener(v ->
                startActivity(new Intent(this, com.lunarforge.market.ui.notifications.NotificationsActivity.class)));
        askNotificationPermissionOnce();
        findViewById(R.id.profileButton).setOnClickListener(v -> showFragment(new ProfileFragment()));

        dimOverlay = findViewById(R.id.dimOverlay);
        slidingMenu = findViewById(R.id.slidingMenu);
        menuNicknameText = findViewById(R.id.menuNicknameText);

        findViewById(R.id.burgerButton).setOnClickListener(v -> openMenu());
        findViewById(R.id.closeMenuButton).setOnClickListener(v -> closeMenu());
        dimOverlay.setOnClickListener(v -> closeMenu());

        // пункты бокового меню. везде сначала закрываю меню, потом перехожу, чтобы при возврате оно не висело открытым
        // "финансы" - это вкладка профиля, там баланс и кнопки пополнить/вывести
        findViewById(R.id.menuFinance).setOnClickListener(v -> {
            closeMenu();
            showFragment(new ProfileFragment());
        });
        findViewById(R.id.menuMyListings).setOnClickListener(v -> {
            closeMenu();
            startActivity(new Intent(this, com.lunarforge.market.ui.listing.MyListingsActivity.class));
        });
        // покупки и продажи - один и тот же экран списка заказов, режим передаю через extra
        findViewById(R.id.menuMyPurchases).setOnClickListener(v -> {
            closeMenu();
            Intent intent = new Intent(this, com.lunarforge.market.ui.order.OrderListActivity.class);
            intent.putExtra(com.lunarforge.market.ui.order.OrderListActivity.EXTRA_MODE,
                    com.lunarforge.market.ui.order.OrderListActivity.MODE_PURCHASES);
            startActivity(intent);
        });
        findViewById(R.id.menuMySales).setOnClickListener(v -> {
            closeMenu();
            Intent intent = new Intent(this, com.lunarforge.market.ui.order.OrderListActivity.class);
            intent.putExtra(com.lunarforge.market.ui.order.OrderListActivity.EXTRA_MODE,
                    com.lunarforge.market.ui.order.OrderListActivity.MODE_SALES);
            startActivity(intent);
        });
        findViewById(R.id.menuTransactions).setOnClickListener(v -> {
            closeMenu();
            startActivity(new Intent(this, com.lunarforge.market.ui.wallet.TransactionHistoryActivity.class));
        });
        findViewById(R.id.menuSupport).setOnClickListener(v -> {
            closeMenu();
            startActivity(new Intent(this, com.lunarforge.market.ui.support.MyTicketsActivity.class));
        });
        // очередь заявок для модераторов/админа. пункт виден только им (см. loadNickname),
        // флаг isAdmin передаю, чтобы там показать админские фильтры
        findViewById(R.id.menuTickets).setOnClickListener(v -> {
            closeMenu();
            startActivity(new Intent(this, com.lunarforge.market.ui.staff.TicketsActivity.class)
                    .putExtra(com.lunarforge.market.ui.staff.TicketsActivity.EXTRA_IS_ADMIN, isAdmin));
        });
        // дальше два пункта только для админа
        findViewById(R.id.menuModerators).setOnClickListener(v -> {
            closeMenu();
            startActivity(new Intent(this, com.lunarforge.market.ui.admin.ModeratorsActivity.class));
        });
        findViewById(R.id.menuAdminRevenue).setOnClickListener(v -> {
            closeMenu();
            startActivity(new Intent(this, com.lunarforge.market.ui.admin.AdminRevenueActivity.class));
        });
        findViewById(R.id.menuTheme).setOnClickListener(v -> {
            closeMenu();
            showThemePicker();
        });
        // выход: стираю сессию (токен) и ухожу на логин, этот экран закрываю
        findViewById(R.id.menuLogout).setOnClickListener(v -> {
            session.clear();
            startActivity(new Intent(this, com.lunarforge.market.ui.auth.LoginActivity.class));
            finish();
        });

        // поиск игр сверху фильтрует список прямо на главной, при каждом изменении текста
        EditText searchEditText = findViewById(R.id.searchEditText);
        searchEditText.setHint("Поиск игр и приложений");
        searchEditText.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override
            public void afterTextChanged(android.text.Editable e) {
                Fragment current = getSupportFragmentManager().findFragmentById(R.id.contentContainer);
                if (!(current instanceof HomeFragment)) {
                    // начали печатать на другой вкладке - переключаю на главную.
                    // commit асинхронный, поэтому executePendingTransactions - чтобы фрагмент
                    // точно был создан до вызова filterGames
                    HomeFragment home = new HomeFragment();
                    showFragment(home);
                    getSupportFragmentManager().executePendingTransactions();
                    home.filterGames(e.toString());
                } else {
                    ((HomeFragment) current).filterGames(e.toString());
                }
            }
        });

        // savedInstanceState != null - экран пересоздан (поворот, смена темы), фрагмент система восстановит сама.
        // если добавить ещё раз - будут два фрагмента друг на друге
        if (savedInstanceState == null) {
            showFragment(new HomeFragment());
        }

        loadNickname();
    }

    // подтягиваю свой профиль с сервера: ник и аватар в меню, роль (какие пункты показывать)
    // и заодно проверка на блокировку
    private void loadNickname() {
        // пока ждём сервер, показываю сохранённый ник, чтобы меню не было пустым
        menuNicknameText.setText(session.getNickname());
        ApiClient.getApiService(this).me().enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.isSuccessful() && response.body() != null) {
                    if (response.body().blocked) { // заблокирован - только экран блокировки
                        // CLEAR_TASK - чтобы "назад" не вернуло в главное меню
                        Intent i = new Intent(MainActivity.this, com.lunarforge.market.ui.auth.BlockedActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                        return;
                    }
                    menuNicknameText.setText(response.body().nickname);
                    com.lunarforge.market.util.Avatars.load((android.widget.ImageView) findViewById(R.id.menuAvatarImage),
                            response.body().avatarUrl, response.body().nickname);
                    // на всякий сверяем свой id с сервером. было: после очистки базы телефон помнил
                    // старый id,
                    // и приложение путало где мой товар/профиль (пропадала кнопка "написать")
                    SessionManager s = new SessionManager(MainActivity.this);
                    if (s.getUserId() != response.body().id) {
                        s.saveSession(s.getToken(), response.body().id, response.body().nickname);
                    }
                    // по роли прячу/показываю пункты меню. это только для удобства -
                    // настоящую проверку прав всё равно делает сервер
                    String role = response.body().role;
                    boolean admin = "ADMIN".equals(role);
                    isAdmin = admin;
                    findViewById(R.id.menuAdminRevenue).setVisibility(admin ? View.VISIBLE : View.GONE);
                    findViewById(R.id.menuModerators).setVisibility(admin ? View.VISIBLE : View.GONE);
                    findViewById(R.id.menuTickets).setVisibility(admin || "MODERATOR".equals(role) ? View.VISIBLE : View.GONE);
                }
            }

            // нет сети - остаётся сохранённый ник, остальное попробуем при следующем открытии
            @Override
            public void onFailure(Call<User> call, Throwable t) {
            }
        });
    }

    // подставляю фрагмент в середину экрана (replace - старый убирается) и подсвечиваю нужную вкладку
    private void showFragment(Fragment fragment) {
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        tx.replace(R.id.contentContainer, fragment);
        tx.commit();
        highlightTab(fragment instanceof HomeFragment ? R.id.homeButton
                : fragment instanceof ChatListFragment ? R.id.chatButton : R.id.profileButton);
    }

    // активная вкладка цветом темы, остальные серые
    private void highlightTab(int activeId) {
        // цвет акцента зависит от выбранной темы, поэтому достаю его из атрибута темы, а не из colors.xml
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(R.attr.appAccent, tv, true);
        int accent = tv.data;
        int inactive = androidx.core.content.ContextCompat.getColor(this, R.color.c_text_secondary);
        for (int id : new int[]{R.id.homeButton, R.id.chatButton, R.id.profileButton}) {
            android.widget.ImageButton b = findViewById(id);
            b.setImageTintList(android.content.res.ColorStateList.valueOf(id == activeId ? accent : inactive));
        }
    }

    // меню - обычная вьюшка в разметке, просто показываю/прячу её вместе с затемнением
    private void openMenu() {
        dimOverlay.setVisibility(View.VISIBLE);
        slidingMenu.setVisibility(View.VISIBLE);
    }

    private void closeMenu() {
        dimOverlay.setVisibility(View.GONE);
        slidingMenu.setVisibility(View.GONE);
    }


    // режим (система/светлая/тёмная) + цвет акцента. при смене режима экраны пересоздаются сами
    private void showThemePicker() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_theme_picker, null);
        android.widget.LinearLayout container = dialogView.findViewById(R.id.themeOptionsContainer);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(dialogView).create();
        ThemeManager.AccentTheme current = ThemeManager.getCurrent(this);

        // первая секция: дневной/ночной режим. строки создаю кодом из одной разметки item_theme_option
        container.addView(sectionHeader("Режим"));
        ThemeManager.NightMode currentNight = ThemeManager.getNightMode(this);
        // иконки идут в том же порядке, что и значения enum NightMode
        String[] icons = {"⚙️", "☀️", "🌙"};
        ThemeManager.NightMode[] modes = ThemeManager.NightMode.values();
        for (int i = 0; i < modes.length; i++) {
            ThemeManager.NightMode mode = modes[i];
            View row = LayoutInflater.from(this).inflate(R.layout.item_theme_option, container, false);
            ((TextView) row.findViewById(R.id.labelText)).setText(icons[i] + "  " + mode.label);
            // у режима цветного кружка нет
            row.findViewById(R.id.swatch).setVisibility(View.GONE);
            // INVISIBLE, а не GONE - чтобы место под галочку оставалось и строки не прыгали
            row.findViewById(R.id.checkmark).setVisibility(mode == currentNight ? View.VISIBLE : View.INVISIBLE);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                // тот же режим - ничего не делаю, иначе экран зря пересоздастся
                if (mode != currentNight) ThemeManager.setNightMode(this, mode);
            });
            container.addView(row);
        }

        // вторая секция: цвет акцента, у каждого варианта кружок с его цветом
        container.addView(sectionHeader("Цвет акцента"));
        for (ThemeManager.AccentTheme theme : ThemeManager.AccentTheme.values()) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_theme_option, container, false);
            // "сезонная" тема сама меняет цвет по времени года, в подписи показываю текущий сезон
            boolean seasonal = theme == ThemeManager.AccentTheme.SEASONAL;
            ((TextView) row.findViewById(R.id.labelText)).setText(seasonal
                    ? theme.label + " (" + ThemeManager.seasonLabel() + ")" : theme.label);

            View swatch = row.findViewById(R.id.swatch);
            GradientDrawable swatchBg;
            if (seasonal) { // кружок из всех четырёх сезонных цветов - видно, что меняется
                swatchBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{
                        android.graphics.Color.parseColor(ThemeManager.AccentTheme.BLUE.swatchHex),
                        android.graphics.Color.parseColor(ThemeManager.AccentTheme.GREEN.swatchHex),
                        android.graphics.Color.parseColor(ThemeManager.AccentTheme.TEAL.swatchHex),
                        android.graphics.Color.parseColor(ThemeManager.AccentTheme.ORANGE.swatchHex)});
            } else {
                swatchBg = new GradientDrawable();
                swatchBg.setColor(android.graphics.Color.parseColor(theme.swatchHex));
            }
            swatchBg.setShape(GradientDrawable.OVAL);
            swatch.setBackground(swatchBg);

            row.findViewById(R.id.checkmark).setVisibility(theme == current ? View.VISIBLE : View.INVISIBLE);

            // акцент применяется только при создании активити (через тему), поэтому сохраняю выбор
            // и перезапускаю главный экран с нуля
            row.setOnClickListener(v -> {
                ThemeManager.setCurrent(this, theme);
                dialog.dismiss();
                Intent intent = new Intent(this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                finish();
            });

            container.addView(row);
        }

        dialog.show();
    }

    // заголовок секции в диалоге темы, делаю кодом чтобы не заводить отдельную разметку
    private TextView sectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.c_text_secondary));
        // 8dp в пикселях
        int p = (int) (8 * getResources().getDisplayMetrics().density);
        tv.setPadding(0, p * 2, 0, p / 2);
        return tv;
    }

    // экран снова виден - запускаю опрос колокольчика. сначала removeCallbacks,
    // чтобы не получилось два параллельных цикла опроса
    @Override
    protected void onResume() {
        super.onResume();
        bellHandler.removeCallbacks(bellPoll);
        bellHandler.post(bellPoll);
    }

    // экран ушёл в фон - останавливаю опрос, нечего зря дёргать сервер и батарею
    @Override
    protected void onPause() {
        super.onPause();
        bellHandler.removeCallbacks(bellPoll);
    }

    // спрашиваю у сервера только число непрочитанных - это лёгкий запрос, а весь список тяну только если число поменялось
    private void pollUnread() {
        ApiClient.getApiService(this).unreadCount().enqueue(new retrofit2.Callback<com.lunarforge.market.model.Notification.UnreadCount>() {
            @Override
            public void onResponse(retrofit2.Call<com.lunarforge.market.model.Notification.UnreadCount> call,
                                   retrofit2.Response<com.lunarforge.market.model.Notification.UnreadCount> response) {
                                       if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.body() == null) return;
                long count = response.body().count;
                // красный кружок с числом на колокольчике, больше 99 не помещается
                TextView badge = findViewById(R.id.notifierBadge);
                badge.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
                badge.setText(count > 99 ? "99+" : String.valueOf(count));
                // первый опрос - всегда (запомнить, на чём остановились), дальше - только если число изменилось
                if (lastUnread == -1 || count != lastUnread) showSystemNotifications();
                lastUnread = count;
            }

            // ошибка сети - молча ждём следующего опроса через 30 сек
            @Override
            public void onFailure(retrofit2.Call<com.lunarforge.market.model.Notification.UnreadCount> call, Throwable t) { }
        });
    }

    // тяну список уведомлений и отдаю в Notifier, он сам решит какие новые и покажет их в шторке
    private void showSystemNotifications() {
        ApiClient.getApiService(this).notifications().enqueue(new retrofit2.Callback<java.util.List<com.lunarforge.market.model.Notification>>() {
            @Override
            public void onResponse(retrofit2.Call<java.util.List<com.lunarforge.market.model.Notification>> call,
                                   retrofit2.Response<java.util.List<com.lunarforge.market.model.Notification>> response) {
                                       if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.body() != null) com.lunarforge.market.util.Notifier.showNew(MainActivity.this, response.body());
            }

            @Override
            public void onFailure(retrofit2.Call<java.util.List<com.lunarforge.market.model.Notification>> call, Throwable t) { }
        });
    }

    // android 13+: разрешение на уведомления спрашиваем один раз, дальше не надоедаем
    private void askNotificationPermissionOnce() {
        // ниже 13 такого разрешения нет вообще
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        // флаг "уже спрашивали" храню в тех же настройках, что и Notifier
        android.content.SharedPreferences p = getSharedPreferences("notifier", MODE_PRIVATE);
        if (p.getBoolean("asked", false)) return;
        p.edit().putBoolean("asked", true).apply();
        if (!com.lunarforge.market.util.Notifier.canPost(this)) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS);
    }
}

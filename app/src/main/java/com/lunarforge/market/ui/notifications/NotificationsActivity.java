package com.lunarforge.market.ui.notifications;

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
import com.lunarforge.market.model.Notification;
import com.lunarforge.market.util.ApiErrors;
import com.lunarforge.market.util.BaseActivity;
import com.lunarforge.market.util.Notifier;
import com.lunarforge.market.util.Ui;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// колокольчик: последние 100 уведомлений. открыли экран - всё помечается прочитанным
// грузит список с /api/notifications, тап по уведомлению открывает то, к чему оно относится (заказ, заявку, операцию)
public class NotificationsActivity extends BaseActivity {

    // список, который показывает адаптер. сам объект не пересоздаю, только clear/addAll - адаптер держит ссылку на него
    private final List<Notification> items = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
    private TextView emptyText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notifications);
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        emptyText = findViewById(R.id.emptyText);
        RecyclerView list = findViewById(R.id.notificationsRecyclerView);
        list.setLayoutManager(new LinearLayoutManager(this));
        // адаптер сделал анонимным прямо тут - он нужен только этому экрану, отдельный класс ради 3 полей не стал заводить.
        // ViewHolder тоже пустой анонимный: поля не кэширую, ищу вьюшки через findViewById в onBindViewHolder (для 100 строк норм)
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                // { } в конце - потому что RecyclerView.ViewHolder абстрактный, напрямую new не сделать
                return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_notification, parent, false)) { };
            }

            // вызывается для каждой видимой строки и при прокрутке - строки переиспользуются, поэтому ВСЕ поля выставляю заново
            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
                Notification n = items.get(position);
                ((TextView) h.itemView.findViewById(R.id.notifTitle)).setText(n.title);
                TextView body = h.itemView.findViewById(R.id.notifBody);
                body.setText(n.body);
                // если текста нет - прячу поле, чтобы не было пустой дыры
                body.setVisibility(n.body == null || n.body.isEmpty() ? View.GONE : View.VISIBLE);
                ((TextView) h.itemView.findViewById(R.id.notifTime)).setText(Ui.createdAgo(n.createdAt));
                // INVISIBLE, а не GONE - место под точку остаётся, и заголовки всех строк стоят ровно
                h.itemView.findViewById(R.id.unreadDot).setVisibility(n.read ? View.INVISIBLE : View.VISIBLE);
                // куда вести по тапу - решает Notifier по типу ссылки (ORDER/TICKET/TRANSACTION/...). null = никуда
                android.content.Intent target = Notifier.intentFor(NotificationsActivity.this, n);
                h.itemView.setOnClickListener(target == null ? null : v -> startActivity(target)); // вести некуда - не кликается
            }

            @Override
            public int getItemCount() {
                return items.size();
            }
        };
        list.setAdapter(adapter);
    }

    // грузим в onResume, а не в onCreate: вернулись с другого экрана - список обновится сам.
    // уведомления приходят пока юзер где-то ещё в приложении
    @Override
    protected void onResume() {
        super.onResume();
        ApiClient.getApiService(this).notifications().enqueue(new Callback<List<Notification>>() {
            @Override
            public void onResponse(Call<List<Notification>> call, Response<List<Notification>> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                // ошибка сервера - просто оставляю что было
                if (response.body() == null) return;
                items.clear();
                items.addAll(response.body());
                // notifyDataSetChanged - перерисовать всё. для списка до 100 штук DiffUtil не нужен
                adapter.notifyDataSetChanged();
                // если пусто - показываю надпись "уведомлений нет" вместо пустого экрана
                emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                // посмотрел - значит прочитал. точки "не прочитано" остаются до следующего открытия, чтобы было видно новое
                // ответ этого запроса не важен: не получилось - пометим в следующий раз, поэтому колбэки пустые
                ApiClient.getApiService(NotificationsActivity.this).readAllNotifications().enqueue(new Callback<Void>() {
                    @Override public void onResponse(Call<Void> c, Response<Void> r) { }
                    @Override public void onFailure(Call<Void> c, Throwable t) { }
                });
            }

            @Override
            public void onFailure(Call<List<Notification>> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(NotificationsActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }
}

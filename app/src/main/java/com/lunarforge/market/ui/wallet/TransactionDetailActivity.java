package com.lunarforge.market.ui.wallet;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.lunarforge.market.R;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.WalletTransaction;
import com.lunarforge.market.ui.order.OrderStatusActivity;
import com.lunarforge.market.ui.profile.PublicProfileActivity;
import com.lunarforge.market.util.ApiErrors;
import com.lunarforge.market.util.BaseActivity;
import com.lunarforge.market.util.Ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// одна операция кошелька по-человечески: что это, сумма, с кем, сообщение, заказ, когда разморозится.
// открывается по нажатию на операцию в истории кошелька, id операции приходит в EXTRA_TX_ID.
// карточки собираю прямо в коде (не через xml), потому что набор зависит от типа операции
public class TransactionDetailActivity extends BaseActivity {

    public static final String EXTRA_TX_ID = "tx_id";
    // контейнер, в который добавляю карточки (собеседник, сообщение, заказ, заморозка)
    private LinearLayout cards;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transaction_detail);
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        cards = findViewById(R.id.txCards);
        // -1 если id не передали, сервер тогда просто вернёт ошибку
        long id = getIntent().getLongExtra(EXTRA_TX_ID, -1);
        ApiClient.getApiService(this).transaction(id).enqueue(new Callback<WalletTransaction>() {
            @Override
            public void onResponse(Call<WalletTransaction> call, Response<WalletTransaction> response) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                if (response.body() != null) bind(response.body());
                else Toast.makeText(TransactionDetailActivity.this, ApiErrors.message(response, "Операция не найдена"), Toast.LENGTH_LONG).show();
            }

            @Override
            public void onFailure(Call<WalletTransaction> call, Throwable t) {
                if (isFinishing() || isDestroyed()) return; // экран уже закрыли - ничего не трогаем
                Toast.makeText(TransactionDetailActivity.this, ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    // заполняю шапку (иконка, заголовок, сумма, дата) и потом собираю карточки
    private void bind(WalletTransaction t) {
        String type = t.type == null ? "" : t.type;
        ((TextView) findViewById(R.id.txIcon)).setText(icon(type));
        ((TextView) findViewById(R.id.txTitle)).setText(t.title != null ? t.title : t.description);

        TextView amount = findViewById(R.id.txAmount);
        // frozen - продажа, деньги по которой ещё на 48ч холде. такие красим жёлтым
        boolean frozen = "SALE_FROZEN".equals(type) && !Boolean.TRUE.equals(t.released);
        boolean neutral = "SALE_RELEASED".equals(type); // те же деньги, просто стали доступны
        // плюс рисую только у положительных сумм, минус у отрицательных и так есть в самом числе
        amount.setText(String.format(Locale.getDefault(), "%s%.2f ₽", t.amount > 0 && !neutral ? "+" : "", t.amount));
        // цвет суммы: серый для разморозки, жёлтый для замороженной, зелёный приход, красный расход
        amount.setTextColor(ContextCompat.getColor(this, neutral ? R.color.c_text_soft
                : frozen ? R.color.c_warning : t.amount >= 0 ? R.color.c_success : R.color.c_danger));
        ((TextView) findViewById(R.id.txDate)).setText(fullDate(t.createdAt));
        ((TextView) findViewById(R.id.txNumber)).setText("Операция #" + t.id);

        // сначала чищу контейнер, потом добавляю только те карточки, для которых есть данные
        cards.removeAllViews();
        if (t.counterpartyId != null) cards.addView(personCard(counterpartyLabel(type), t));
        if (t.message != null && !t.message.isEmpty()) {
            TextView msg = card("💬  «" + t.message + "»", R.color.c_text);
            msg.setTextSize(16);
            addCaption("Сообщение");
            cards.addView(msg);
        }
        if (t.orderId != null) {
            TextView order = card("🧾  Заказ #" + t.orderId + (t.orderTitle != null ? "\n«" + t.orderTitle + "»" : "") + "  ›", R.color.c_text);
            // тап по заказу открывает экран статуса заказа
            order.setOnClickListener(v -> startActivity(new Intent(this, OrderStatusActivity.class)
                    .putExtra(OrderStatusActivity.EXTRA_ORDER_ID, t.orderId.longValue())));
            addCaption("Заказ");
            cards.addView(order);
        }
        // для замороженной продажи показываю когда деньги станут доступны или что уже разморозились
        if ("SALE_FROZEN".equals(type) && t.releaseAt != null) {
            addCaption("Заморозка");
            cards.addView(card(Boolean.TRUE.equals(t.released)
                    ? "✅  Разморожено - деньги можно тратить и выводить"
                    : "❄️  Станут доступны " + fullDate(t.releaseAt), R.color.c_text));
        }
        // старые операции (до этого обновления) без подробностей - показываем хотя бы описание
        if (cards.getChildCount() == 0 && t.description != null) cards.addView(card(t.description, R.color.c_text_soft));
    }

    // иконка (эмодзи) по типу операции, в шапке экрана
    private String icon(String type) {
        switch (type) {
            case "TRANSFER_IN": return "💸";
            case "TRANSFER_OUT": return "📤";
            case "PURCHASE": return "🛒";
            case "SALE_FROZEN": return "💰";
            case "SALE_RELEASED": return "🔓";
            case "REFUND": return "↩️";
            case "TOP_UP": return "💳";
            case "WITHDRAWAL": return "🏦";
            case "COMPENSATION": return "🎁";
            case "CLAWBACK": return "↩️";
            default: return "💼";
        }
    }

    // подпись над карточкой собеседника - зависит от того, кем он был в этой операции
    private String counterpartyLabel(String type) {
        switch (type) {
            case "TRANSFER_IN": return "От кого";
            case "TRANSFER_OUT": return "Кому";
            case "PURCHASE": case "REFUND": return "Продавец";
            case "SALE_FROZEN": case "SALE_RELEASED": case "CLAWBACK": return "Покупатель";
            default: return "Участник";
        }
    }

    // карточка человека: аватар + ник + @username, тап - профиль
    private View personCard(String caption, WalletTransaction t) {
        addCaption(caption);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.rounded_bg);
        // размеры в коде задаю в px, поэтому умножаю dp на density - иначе на разных экранах будет разный размер
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = pad / 2;
        row.setLayoutParams(lp);

        int size = (int) (44 * getResources().getDisplayMetrics().density);
        ImageView avatar = new ImageView(this);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        // аватарку грузит общий хелпер Avatars (через глайд), если её нет - рисует букву ника
        com.lunarforge.market.util.Avatars.load(avatar, t.counterpartyAvatarUrl, t.counterpartyNickname);
        row.addView(avatar);

        TextView name = new TextView(this);
        name.setText((t.counterpartyNickname != null ? t.counterpartyNickname : "")
                + (t.counterpartyUsername != null ? "\n@" + t.counterpartyUsername : ""));
        name.setTextSize(16);
        name.setTextColor(ContextCompat.getColor(this, R.color.c_text));
        name.setPadding(pad, 0, 0, 0);
        // weight = 1 - имя занимает всё свободное место, стрелка прижимается вправо
        name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(name);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(22);
        arrow.setTextColor(ContextCompat.getColor(this, R.color.c_text_secondary));
        row.addView(arrow);

        row.setOnClickListener(v -> startActivity(new Intent(this, PublicProfileActivity.class)
                .putExtra(PublicProfileActivity.EXTRA_USER_ID, t.counterpartyId.longValue())));
        return row;
    }

    // серая подпись над карточкой
    private void addCaption(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(ContextCompat.getColor(this, R.color.c_text_secondary));
        int pad = (int) (6 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad * 2, 0, pad);
        cards.addView(tv);
    }

    // простая карточка-текст с закруглённым фоном
    private TextView card(String text, int colorRes) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setLineSpacing(0, 1.15f);
        tv.setTextColor(ContextCompat.getColor(this, colorRes));
        tv.setBackgroundResource(R.drawable.rounded_bg);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = pad / 2;
        tv.setLayoutParams(lp);
        return tv;
    }

    // "2026-10-05T11:32:00Z" -> "5 октября 2026, 14:32" (в часовом поясе телефона)
    private String fullDate(String iso) {
        long ms = Ui.parseIso(iso);
        if (ms < 0) return iso == null ? "" : iso;
        return new SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru")).format(new Date(ms));
    }
}

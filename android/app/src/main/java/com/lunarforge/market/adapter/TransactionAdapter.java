package com.lunarforge.market.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.lunarforge.market.R;
import com.lunarforge.market.model.WalletTransaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// список операций по кошельку (история). открывается на экране кошелька,
// сначала грузится первая страница через submitList, потом при прокрутке вниз догружаю appendList
public class TransactionAdapter extends RecyclerView.Adapter<TransactionAdapter.VH> {
    private final List<WalletTransaction> transactions = new ArrayList<>();

    // первая страница / обновление по свайпу - выкидываю старое и показываю заново
    public void submitList(List<WalletTransaction> newTransactions) {
        transactions.clear();
        transactions.addAll(newTransactions);
        notifyDataSetChanged();
    }

    // следующая порция - в конец, без перерисовки уже показанного
    public void appendList(List<WalletTransaction> more) {
        // запоминаю позицию с которой начинаются новые элементы, чтобы сказать адаптеру что вставилось именно туда
        int start = transactions.size();
        transactions.addAll(more);
        notifyItemRangeInserted(start, more.size());
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transaction, parent, false);
        return new VH(v);
    }

    // поэтому замороженную продажу рисую жёлтым со снежинкой, а разморозку серым без знака - это просто перенос
    // из замороженных в доступные, баланс в сумме не меняется
    // SALE_FROZEN и SALE_RELEASED это одни и те же деньги.
    // раньше оба показывались как "+500" зелёным и казалось что пришло 1000
    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        WalletTransaction t = transactions.get(position);
        // "Входящий перевод" + с кем / по какому заказу. старые операции без подробностей - как раньше, описанием
        String subtitle = t.counterpartyNickname != null ? t.counterpartyNickname
                : t.orderTitle != null ? "«" + t.orderTitle + "»" : null;
        // если у операции есть title - показываю его и через точку собеседника или название заказа
        holder.description.setText(t.title != null
                ? t.title + (subtitle != null ? " · " + subtitle : "")
                : t.description);
        // дата в виде "5 минут назад" и т.д.
        holder.date.setText(com.lunarforge.market.util.Ui.createdAgo(t.createdAt));
        // по нажатию открываю подробности операции, передаю только id - экран сам загрузит остальное
        holder.itemView.setOnClickListener(v -> v.getContext().startActivity(
                new android.content.Intent(v.getContext(), com.lunarforge.market.ui.wallet.TransactionDetailActivity.class)
                        .putExtra(com.lunarforge.market.ui.wallet.TransactionDetailActivity.EXTRA_TX_ID, t.id)));

        // деньги за продажу пришли, но заморожены пока покупатель не подтвердит + 48ч холд
        if ("SALE_FROZEN".equals(t.type)) {
            holder.amount.setText(String.format(Locale.getDefault(), "❄ +%.2f ₽", t.amount));
            holder.amount.setTextColor(androidx.core.content.ContextCompat.getColor(holder.itemView.getContext(), R.color.c_warning));
        // холд прошёл, деньги стали доступны. сумма может прийти со знаком, поэтому беру модуль
        } else if ("SALE_RELEASED".equals(t.type)) {
            holder.amount.setText(String.format(Locale.getDefault(), "⇄ %.2f ₽", Math.abs(t.amount)));
            holder.amount.setTextColor(androidx.core.content.ContextCompat.getColor(holder.itemView.getContext(), R.color.c_text_secondary));
        // все остальные операции (пополнение, вывод, покупка, переводы, комиссия) - обычный плюс зелёным / минус красным.
        // цвет ставлю в каждой ветке, иначе переиспользованный ViewHolder оставит цвет от прошлой строки
        } else {
            boolean positive = t.amount >= 0;
            // минус у отрицательного числа format поставит сам, поэтому вручную добавляю только "+"
            holder.amount.setText(String.format(Locale.getDefault(), "%s%.2f ₽", positive ? "+" : "", t.amount));
            holder.amount.setTextColor(positive ? androidx.core.content.ContextCompat.getColor(holder.itemView.getContext(), R.color.c_success) : androidx.core.content.ContextCompat.getColor(holder.itemView.getContext(), R.color.c_danger));
        }
    }

    @Override
    public int getItemCount() {
        return transactions.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView description, date, amount;

        VH(@NonNull View itemView) {
            super(itemView);
            description = itemView.findViewById(R.id.descriptionText);
            date = itemView.findViewById(R.id.dateText);
            amount = itemView.findViewById(R.id.amountText);
        }
    }
}

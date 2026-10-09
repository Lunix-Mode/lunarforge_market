package com.lunarforge.market.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.lunarforge.market.R;
import com.lunarforge.market.model.Order;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// адаптер списка заказов (мои покупки / продажи). RecyclerView сам переиспользует строки при прокрутке,
// а адаптер только заполняет их данными заказа. по тапу на строку зовётся listener - экран открывает заказ
public class OrderAdapter extends RecyclerView.Adapter<OrderAdapter.VH> {
    // интерфейс для клика, чтобы адаптер не знал про активити и не открывал экраны сам
    public interface OnOrderClickListener {
        void onOrderClick(Order order);
    }

    private final List<Order> orders = new ArrayList<>();
    private final OnOrderClickListener listener;

    public OrderAdapter(OnOrderClickListener listener) {
        this.listener = listener;
    }

    // первая порция или обновление: заменяю всё целиком. notifyDataSetChanged перерисовывает весь список,
    // для полной замены это проще всего
    public void submitList(List<Order> newOrders) {
        orders.clear();
        orders.addAll(newOrders);
        notifyDataSetChanged();
    }

    // следующая порция - в конец, без перерисовки уже показанного
    public void appendList(List<Order> more) {
        // запоминаю с какой позиции добавились, и говорю списку что вставлены только эти строки
        int start = orders.size();
        orders.addAll(more);
        notifyItemRangeInserted(start, more.size());
    }

    @NonNull
    @Override
    // создаю view строки из item_order.xml. attachToRoot = false - в parent её добавит сам RecyclerView
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_order, parent, false);
        return new VH(v);
    }

    @Override
    // заполняю строку данными. holder может быть старый от другого заказа, поэтому всё выставляю заново
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Order order = orders.get(position);
        holder.title.setText(order.listingTitle + "  × " + order.quantity);
        // сумма с двумя знаками, Locale.getDefault - чтобы разделитель был как у юзера в системе
        holder.amount.setText(String.format(Locale.getDefault(), "#%d · %.2f ₽", order.id, order.amount));

        // бейдж статуса: текст, фон и цвет текста по статусу заказа
        switch (order.status) {
            case "COMPLETED":
                holder.badge.setText("Завершён");
                holder.badge.setBackgroundResource(R.drawable.bg_status_completed);
                holder.badge.setTextColor(androidx.core.content.ContextCompat.getColor(holder.itemView.getContext(), R.color.c_success));
                break;
            case "DISPUTED":
                holder.badge.setText("Спор");
                holder.badge.setBackgroundResource(R.drawable.bg_status_pending);
                holder.badge.setTextColor(androidx.core.content.ContextCompat.getColor(holder.itemView.getContext(), R.color.c_warning));
                break;
            case "CANCELLED":
                holder.badge.setText("Отменён");
                holder.badge.setBackgroundResource(R.drawable.bg_status_cancelled);
                holder.badge.setTextColor(androidx.core.content.ContextCompat.getColor(holder.itemView.getContext(), R.color.c_danger));
                break;
            // все остальные статусы (оплачен, ждём подтверждения покупателя) показываю как "ожидает"
            default:
                holder.badge.setText("Ожидает выполнения");
                holder.badge.setBackgroundResource(R.drawable.bg_status_pending);
                holder.badge.setTextColor(androidx.core.content.ContextCompat.getColor(holder.itemView.getContext(), R.color.c_warning));
                break;
        }

        // клик по всей строке
        holder.itemView.setOnClickListener(v -> listener.onOrderClick(order));
    }

    @Override
    public int getItemCount() {
        return orders.size();
    }

    // ViewHolder держит ссылки на view строки, чтобы не делать findViewById при каждой прокрутке
    static class VH extends RecyclerView.ViewHolder {
        TextView title, amount, badge;

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.orderListingTitleText);
            amount = itemView.findViewById(R.id.orderAmountText);
            badge = itemView.findViewById(R.id.orderStatusBadge);
        }
    }
}

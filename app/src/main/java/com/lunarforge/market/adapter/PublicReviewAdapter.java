package com.lunarforge.market.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.lunarforge.market.R;
import com.lunarforge.market.model.Rating;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// используется на экране профиля продавца, чтобы покупатель видел что про него пишут.
// автора скрываю специально - чтобы продавец не мог найти покупателя и давить на него из-за плохого отзыва
// отзывы о продавце. автора не показываем, сумма округлена до 10р
public class PublicReviewAdapter extends RecyclerView.Adapter<PublicReviewAdapter.VH> {
    private final List<Rating> reviews = new ArrayList<>();

    // заменяю весь список целиком (отзывы грузятся одним запросом, без подгрузки),
    // поэтому notifyDataSetChanged тут нормально, а не только для новых элементов
    public void submitList(List<Rating> newReviews) {
        reviews.clear();
        reviews.addAll(newReviews);
        notifyDataSetChanged();
    }

    // сколько отзывов сейчас в списке - экран по этому решает, показывать ли надпись "отзывов пока нет"
    public int getCount() {
        return reviews.size();
    }

    @NonNull
    @Override
    // создаю view для одной карточки отзыва из разметки item_public_review
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_public_review, parent, false);
        return new VH(v);
    }

    @Override
    // заполняю карточку: звёзды, дата, текст и примерная сумма покупки
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Rating r = reviews.get(position);
        // рисую звёзды по оценке (1-5), 18 - размер звёздочки
        com.lunarforge.market.util.Stars.render(holder.stars, r.score, 18);

        // с сервера дата приходит строкой ISO, беру только первые 10 символов (yyyy-MM-dd), время тут не нужно.
        // проверка длины чтобы не упасть на substring, если вдруг пришло что-то короткое
        holder.date.setText(r.createdAt != null && r.createdAt.length() >= 10 ? r.createdAt.substring(0, 10) : "");

        // отзыв может быть просто оценкой без текста - тогда прячу поле, чтобы не было пустой строки.
        // VISIBLE в else обязательно, потому что ViewHolder переиспользуется и мог остаться GONE от прошлого отзыва
        if (r.comment == null || r.comment.trim().isEmpty()) {
            holder.comment.setVisibility(View.GONE);
        } else {
            holder.comment.setVisibility(View.VISIBLE);
            holder.comment.setText(r.comment);
        }

        // сумму сервер уже округлил до 10р (roundedAmount), чтобы по точной цене нельзя было вычислить конкретный заказ
        holder.amount.setText(String.format(Locale.getDefault(), "Покупка ~%d ₽", r.roundedAmount));
    }

    @Override
    public int getItemCount() {
        return reviews.size();
    }

    // ViewHolder хранит ссылки на view, чтобы не вызывать findViewById при каждой прокрутке
    static class VH extends RecyclerView.ViewHolder {
        // контейнер куда Stars.render добавляет иконки звёзд
        android.widget.LinearLayout stars;
        TextView date, comment, amount;

        VH(@NonNull View itemView) {
            super(itemView);
            stars = itemView.findViewById(R.id.starsRow);
            date = itemView.findViewById(R.id.dateText);
            comment = itemView.findViewById(R.id.commentText);
            amount = itemView.findViewById(R.id.amountText);
        }
    }
}

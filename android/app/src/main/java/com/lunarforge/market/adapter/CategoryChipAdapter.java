package com.lunarforge.market.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.lunarforge.market.R;
import com.lunarforge.market.model.Category;

import java.util.ArrayList;
import java.util.List;

// горизонтальная лента "чипов" с категориями над списком товаров игры.
// первые две позиции у меня фиксированные: 0 = "Все", 1 = "Другое" (лоты без категории),
// а дальше идут категории с сервера. поэтому везде сдвиг на 2 при обращении к списку categories
public class CategoryChipAdapter extends RecyclerView.Adapter<CategoryChipAdapter.VH> {
    // через этот интерфейс сообщаю экрану, что выбрали: categoryId = null и isOther = false значит "Все",
    // null и isOther = true - "Другое", иначе конкретная категория
    public interface OnChipSelectedListener {
        void onChipSelected(Long categoryId, boolean isOther);
    }

    private final List<Category> categories = new ArrayList<>();
    private final OnChipSelectedListener listener;
    // какой чип сейчас подсвечен, по умолчанию "Все"
    private int selectedPosition = 0;

    public CategoryChipAdapter(OnChipSelectedListener listener) {
        this.listener = listener;
    }

    // подставляю новый список категорий (когда сервер их прислал).
    // notifyDataSetChanged перерисовывает всё целиком - категорий мало, так что по скорости не страшно
    public void submitCategories(List<Category> newCategories) {
        categories.clear();
        categories.addAll(newCategories);
        notifyDataSetChanged();
    }

    // создаю вьюшку одного чипа из разметки item_category_chip (это просто TextView)
    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_category_chip, parent, false);
        return new VH(v);
    }

    // заполняю чип: подпись в зависимости от позиции и фон - выделенный или обычный
    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        String label;
        if (position == 0) {
            label = "Все";
        } else if (position == 1) {
            label = "Другое";
        } else {
            label = categories.get(position - 2).name;
        }
        holder.text.setText(label);
        holder.text.setBackgroundResource(position == selectedPosition ? R.drawable.bg_chip_selected : R.drawable.bg_search_field);

        // нажатие на чип: запоминаю старую позицию и новую, и перерисовываю только эти два чипа,
        // чтобы не дёргать весь список
        holder.itemView.setOnClickListener(v -> {
            int previous = selectedPosition;
            // getBindingAdapterPosition, а не position из параметра - position мог устареть,
            // если список успел поменяться после того как чип был нарисован
            selectedPosition = holder.getBindingAdapterPosition();
            notifyItemChanged(previous);
            notifyItemChanged(selectedPosition);

            if (selectedPosition == 0) {
                listener.onChipSelected(null, false);
            } else if (selectedPosition == 1) {
                listener.onChipSelected(null, true);
            } else {
                listener.onChipSelected(categories.get(selectedPosition - 2).id, false);
            }
        });
    }

    // +2 это мои два фиксированных чипа "Все" и "Другое"
    @Override
    public int getItemCount() {
        return categories.size() + 2;
    }

    // ViewHolder держит ссылку на TextView, чтобы не искать её каждый раз через findViewById
    static class VH extends RecyclerView.ViewHolder {
        TextView text;

        VH(@NonNull View itemView) {
            super(itemView);
            // корневая вьюшка в разметке чипа и есть TextView, поэтому просто привожу тип
            text = (TextView) itemView;
        }
    }
}

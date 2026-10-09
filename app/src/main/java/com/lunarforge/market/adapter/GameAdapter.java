package com.lunarforge.market.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.lunarforge.market.R;
import com.lunarforge.market.model.Game;

import java.util.ArrayList;
import java.util.List;

// адаптер для сетки игр на главном экране (MainActivity).
// RecyclerView сам не знает как рисовать элементы - адаптер говорит ему сколько их,
// как создать карточку (onCreateViewHolder) и как заполнить её данными (onBindViewHolder).
// карточки переиспользуются при прокрутке, поэтому в bind надо каждый раз выставлять ВСЁ заново
public class GameAdapter extends RecyclerView.Adapter<GameAdapter.VH> {
    // нажатие на игру отдаю наружу в активити, адаптер сам никуда не переходит
    public interface OnGameClickListener {
        void onGameClick(Game game);
    }

    // палитра для плиток без картинки (ARGB, FF в начале = непрозрачный)
    private static final int[] TILE_COLORS = {
            0xFF287BFF, 0xFF8B5CF6, 0xFF22C55E, 0xFFEC4899, 0xFFF97316, 0xFF06B6D4, 0xFFEAB308, 0xFFEF4444};

    private final List<Game> games = new ArrayList<>();
    private final OnGameClickListener listener;

    public GameAdapter(OnGameClickListener listener) {
        this.listener = listener;
    }

    // заменить весь список. notifyDataSetChanged перерисовывает всё - для пары десятков игр это нормально,
    // DiffUtil тут был бы перебором
    public void submitList(List<Game> newGames) {
        games.clear();
        games.addAll(newGames);
        notifyDataSetChanged();
    }

    // создаём пустую карточку из разметки item_game. false - не прикреплять к parent сразу,
    // это сделает сам RecyclerView
    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_game, parent, false);
        return new VH(v);
    }

    // заполняем карточку игрой с позиции position.
    // нет картинки у игры - рисуем первую букву на цветном фоне, цвет от хэша названия
    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Game game = games.get(position);
        holder.name.setText(game.name);
        if (game.iconUrl != null && !game.iconUrl.isEmpty()) {
            // есть иконка: прячу букву, показываю фото. visibility выставляю в обе стороны,
            // потому что карточка могла раньше показывать игру без картинки
            holder.letter.setVisibility(View.GONE);
            holder.photo.setVisibility(View.VISIBLE);
            // Glide сам грузит картинку в фоне и кэширует. with(itemView) - загрузка привязана
            // к жизни вьюшки, если карточку убрали - запрос отменится.
            // absoluteUrl нужен т.к. сервер отдаёт путь вида /uploads/..., без адреса сервера
            Glide.with(holder.itemView).load(com.lunarforge.market.api.ApiClient.absoluteUrl(game.iconUrl)).into(holder.photo);
        } else {
            holder.photo.setVisibility(View.GONE);
            holder.letter.setVisibility(View.VISIBLE);
            // защита от null и пустого имени, иначе substring упадёт
            String name = game.name == null ? "?" : game.name.trim();
            holder.letter.setText(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase());
            // hashCode может быть отрицательным, поэтому abs - иначе индекс массива уйдёт в минус.
            // цвет зависит только от названия, так что у одной игры он всегда одинаковый
            int color = TILE_COLORS[Math.abs(name.hashCode()) % TILE_COLORS.length];
            // фон рисую кодом: скругление 8dp, переводим dp в пиксели через density экрана
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(8 * holder.itemView.getResources().getDisplayMetrics().density);
            bg.setColor(color);
            holder.letter.setBackground(bg);
        }
        holder.itemView.setOnClickListener(v -> listener.onGameClick(game));
    }

    @Override
    public int getItemCount() {
        return games.size();
    }

    // держит ссылки на вьюшки карточки, чтобы не вызывать findViewById при каждой прокрутке
    static class VH extends RecyclerView.ViewHolder {
        TextView name;
        ImageView photo;
        TextView letter;

        VH(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.gameNameText);
            photo = itemView.findViewById(R.id.gamePhoto);
            // фон плитки скруглённый, а картинка поверх - нет: обрезаем фото по контуру фона, иначе торчат острые углы
            itemView.findViewById(R.id.photoContainer).setClipToOutline(true);
            letter = itemView.findViewById(R.id.gameLetter);
        }
    }
}

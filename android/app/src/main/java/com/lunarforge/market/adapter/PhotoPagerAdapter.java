package com.lunarforge.market.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.lunarforge.market.R;
import com.lunarforge.market.api.ApiClient;

import java.util.ArrayList;
import java.util.List;

// адаптер для листалки фото на странице товара (ViewPager2 тоже работает через RecyclerView.Adapter).
// одна страница = одна картинка, по клику открывается фото на весь экран
public class PhotoPagerAdapter extends RecyclerView.Adapter<PhotoPagerAdapter.VH> {
    public interface OnPhotoClick {
        void onPhotoClick(int position);
    }

    private final List<String> urls = new ArrayList<>();
    private final OnPhotoClick listener;

    public PhotoPagerAdapter(OnPhotoClick listener) {
        this.listener = listener;
    }

    // положить новый набор ссылок на фото и перерисовать
    public void submit(List<String> newUrls) {
        urls.clear();
        urls.addAll(newUrls);
        notifyDataSetChanged();
    }

    // ссылка на фото по номеру страницы
    public String urlAt(int position) {
        return urls.get(position);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_gallery_photo, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        String url = urls.get(position);
        // url может быть null - тогда просто заглушка, грузить нечего
        if (url == null) {
            holder.image.setImageResource(R.drawable.ic_image_placeholder);
        } else {
            // placeholder показывается, пока фото качается
            Glide.with(holder.itemView).load(ApiClient.absoluteUrl(url))
                    .placeholder(R.drawable.ic_image_placeholder).into(holder.image);
        }
        holder.itemView.setOnClickListener(v -> {
            // позицию беру из holder в момент клика, а не position из параметра - та могла устареть,
            // если список успел поменяться. на заглушку клик не реагирует
            if (url != null) listener.onPhotoClick(holder.getBindingAdapterPosition());
        });
    }

    @Override
    public int getItemCount() {
        return urls.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView image;

        VH(@NonNull View itemView) {
            super(itemView);
            // корень разметки item_gallery_photo сам является ImageView, поэтому просто привожу тип
            image = (ImageView) itemView;
        }
    }
}

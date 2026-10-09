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
import com.lunarforge.market.model.Listing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// адаптер для списка товаров (витрина игры, мои товары, товары в профиле продавца).
// RecyclerView сам не знает, как рисовать товар - адаптер создаёт карточку из item_listing.xml
// и заполняет её данными. карточки переиспользуются при прокрутке, поэтому список не тормозит
public class ListingAdapter extends RecyclerView.Adapter<ListingAdapter.VH> {
    // клик по карточке отдаю наружу, в активити - она решает, какой экран открыть
    public interface OnListingClickListener {
        void onListingClick(Listing listing);
    }

    private final List<Listing> listings = new ArrayList<>();
    private final OnListingClickListener listener;

    public ListingAdapter(OnListingClickListener listener) {
        this.listener = listener;
    }

    // полностью заменить список (первая загрузка, смена фильтра, обновление)
    // notifyDataSetChanged перерисовывает всё - тут это ок, всё равно данные новые
    public void submitList(List<Listing> newListings) {
        listings.clear();
        listings.addAll(newListings);
        notifyDataSetChanged();
    }

    // следующая порция - в конец, без перерисовки уже показанного
    public void appendList(List<Listing> more) {
        // запоминаю, с какой позиции добавятся новые, и говорю адаптеру только про них
        int start = listings.size();
        listings.addAll(more);
        notifyItemRangeInserted(start, more.size());
    }

    @NonNull
    @Override
    // вызывается, только когда нужна новая карточка - их создаётся примерно столько, сколько влезает на экран
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_listing, parent, false);
        return new VH(v);
    }

    // показываем buyerPrice (с комиссией) - ровно столько и спишется
    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Listing listing = listings.get(position);
        holder.title.setText(listing.title);
        // "Продавец: ник", и если товар неактивен - пометка рядом (на витрину такие не попадают, их видно в "моих товарах")
        holder.seller.setText(new android.text.SpannableStringBuilder(
                com.lunarforge.market.util.Ui.labelValue(holder.itemView.getContext(), "Продавец: ", listing.sellerNickname))
                .append(listing.active ? "" : "  ·  🚫 скрыт с витрины"));
        holder.price.setText(String.format(Locale.getDefault(), "%.2f ₽", listing.buyerPrice));
        // картинку грузит Glide: в фоне, с кэшем, и сам отменяет загрузку, если карточку переиспользовали.
        // сервер отдаёт путь /files/..., absoluteUrl дописывает адрес сервера.
        // else обязателен: иначе в переиспользованной карточке осталась бы картинка от другого товара
        if (listing.imageUrl != null && !listing.imageUrl.isEmpty()) {
            Glide.with(holder.itemView).load(com.lunarforge.market.api.ApiClient.absoluteUrl(listing.imageUrl)).into(holder.image);
        } else {
            holder.image.setImageResource(R.drawable.ic_image_placeholder);
        }
        holder.itemView.setOnClickListener(v -> listener.onListingClick(listing));
    }

    @Override
    public int getItemCount() {
        return listings.size();
    }

    // держатель ссылок на вьюшки карточки, чтобы не вызывать findViewById при каждой прокрутке
    static class VH extends RecyclerView.ViewHolder {
        TextView title, seller, price;
        ImageView image;

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.listingTitleText);
            seller = itemView.findViewById(R.id.listingSellerText);
            price = itemView.findViewById(R.id.listingPriceText);
            image = itemView.findViewById(R.id.listingImage);
        }
    }
}

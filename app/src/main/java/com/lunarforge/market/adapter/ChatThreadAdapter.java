package com.lunarforge.market.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.lunarforge.market.R;
import com.lunarforge.market.model.Chat;

import java.util.ArrayList;
import java.util.List;

// адаптер для списка чатов (вкладка "Сообщения"): одна строка = один диалог
// с аватаркой собеседника, его ником и последним сообщением.
// нажатие на строку отдаю наружу через слушатель, а экран уже сам открывает чат
public class ChatThreadAdapter extends RecyclerView.Adapter<ChatThreadAdapter.VH> {
    // колбэк для нажатия - чтобы адаптер не знал ничего про активити и интенты
    public interface OnThreadClickListener {
        void onThreadClick(Chat.Thread thread);
    }

    private final List<Chat.Thread> threads = new ArrayList<>();
    private final OnThreadClickListener listener;
    // мой id нужен, чтобы понять, кто в диалоге "собеседник": я могу быть и покупателем, и продавцом
    private final long myUserId;

    public ChatThreadAdapter(long myUserId, OnThreadClickListener listener) {
        this.myUserId = myUserId;
        this.listener = listener;
    }

    // заменяю весь список новым с сервера. notifyDataSetChanged перерисовывает всё -
    // для списка чатов это нормально, он небольшой
    public void submitList(List<Chat.Thread> newThreads) {
        threads.clear();
        threads.addAll(newThreads);
        notifyDataSetChanged();
    }

    // создаю разметку строки (вызывается только когда нужна новая строка, потом они переиспользуются)
    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_thread, parent, false);
        return new VH(v);
    }

    // заполняю строку данными конкретного чата
    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Chat.Thread thread = threads.get(position);
        // если покупатель я - показываю продавца, иначе наоборот
        String otherNickname = thread.buyerId == myUserId ? thread.sellerNickname : thread.buyerNickname;
        holder.nickname.setText(otherNickname);
        String avatar = thread.buyerId == myUserId ? thread.sellerAvatarUrl : thread.buyerAvatarUrl;
        // если фото нет, Avatars нарисует кружок с первой буквой ника
        com.lunarforge.market.util.Avatars.load(holder.avatar, avatar, otherNickname);
        // в новом чате ещё нет сообщений - пишу подсказку вместо пустоты
        holder.preview.setText(thread.lastMessagePreview == null || thread.lastMessagePreview.isEmpty()
                ? "Начните диалог" : thread.lastMessagePreview);
        holder.itemView.setOnClickListener(v -> listener.onThreadClick(thread));
    }

    @Override
    public int getItemCount() {
        return threads.size();
    }

    // держит ссылки на вьюшки строки, чтобы не делать findViewById при каждой прокрутке
    static class VH extends RecyclerView.ViewHolder {
        TextView nickname, preview;
        android.widget.ImageView avatar;

        VH(@NonNull View itemView) {
            super(itemView);
            nickname = itemView.findViewById(R.id.threadNicknameText);
            avatar = itemView.findViewById(R.id.threadAvatar);
            preview = itemView.findViewById(R.id.threadPreviewText);
        }
    }
}

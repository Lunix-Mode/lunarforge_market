package com.lunarforge.market.ui.chat;

import com.lunarforge.market.util.ApiErrors;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.lunarforge.market.R;
import com.lunarforge.market.adapter.ChatThreadAdapter;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Chat;
import com.lunarforge.market.util.SessionManager;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// вкладка "Чаты" на главном экране: список всех моих диалогов. тап - открываю ChatActivity.
// обновляется свайпом вниз и при каждом возвращении на вкладку
public class ChatListFragment extends Fragment {
    private ChatThreadAdapter adapter;
    private SwipeRefreshLayout swipeRefresh;
    private TextView emptyText;

    @Nullable
    @Override
    // создаю разметку, настраиваю список и сразу запускаю загрузку
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_chat_list, container, false);

        // из сессии беру свой userId - по нему понимаю кто в диалоге "я", а кто собеседник
        SessionManager session = new SessionManager(requireContext());

        RecyclerView recyclerView = view.findViewById(R.id.threadsRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        // адаптеру тоже передаю свой id, чтобы он показывал имя и аватар собеседника, а не мой
        adapter = new ChatThreadAdapter(session.getUserId(), thread -> {
            // если я покупатель в этом чате - собеседник продавец, и наоборот
            String otherNickname = thread.buyerId == session.getUserId() ? thread.sellerNickname : thread.buyerNickname;
            Intent intent = new Intent(requireContext(), ChatActivity.class);
            intent.putExtra(ChatActivity.EXTRA_THREAD_ID, thread.id);
            intent.putExtra(ChatActivity.EXTRA_OTHER_NICKNAME, otherNickname);
            // id собеседника нужен чтобы из шапки чата открыть его профиль
            intent.putExtra(ChatActivity.EXTRA_OTHER_USER_ID,
                    thread.buyerId == session.getUserId() ? thread.sellerId : thread.buyerId);
            startActivity(intent);
        });
        recyclerView.setAdapter(adapter);

        emptyText = view.findViewById(R.id.emptyText);
        swipeRefresh = view.findViewById(R.id.swipeRefresh);
        // свайп вниз - перезагрузить список
        swipeRefresh.setOnRefreshListener(this::load);

        load();
        return view;
    }

    @Override
    // при возврате из чата обновляю - там могли прийти новые сообщения, превью и порядок поменяются
    public void onResume() {
        super.onResume();
        load();
    }

    // загрузка списка чатов с сервера
    private void load() {
        // фрагмент может быть уже не прикреплён к активити (например свайп сработал при уходе) - тогда requireContext упадёт
        if (!isAdded()) return;
        swipeRefresh.setRefreshing(true);
        ApiClient.getApiService(requireContext()).myThreads().enqueue(new Callback<List<Chat.Thread>>() {
            @Override
            public void onResponse(Call<List<Chat.Thread>> call, Response<List<Chat.Thread>> response) {
                // ответ пришёл асинхронно, за это время могли уйти с вкладки - проверяю ещё раз, иначе краш
                if (!isAdded()) return;
                swipeRefresh.setRefreshing(false);
                if (response.isSuccessful() && response.body() != null) {
                    adapter.submitList(response.body());
                    // пусто - показываю надпись что чатов пока нет
                    emptyText.setVisibility(response.body().isEmpty() ? View.VISIBLE : View.GONE);
                }
            }

            @Override
            public void onFailure(Call<List<Chat.Thread>> call, Throwable t) {
                if (!isAdded()) return;
                swipeRefresh.setRefreshing(false);
                Toast.makeText(requireContext(), ApiErrors.network(t), Toast.LENGTH_SHORT).show();
            }
        });
    }
}

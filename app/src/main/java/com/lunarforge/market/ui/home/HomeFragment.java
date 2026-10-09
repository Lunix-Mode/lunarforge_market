package com.lunarforge.market.ui.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.lunarforge.market.R;
import com.lunarforge.market.adapter.GameAdapter;
import com.lunarforge.market.api.ApiClient;
import com.lunarforge.market.model.Game;
import com.lunarforge.market.ui.listing.GameListingsActivity;
import com.lunarforge.market.util.ApiErrors;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// главная: разделы Игры / Приложения / Прочее как в макете.
// поиск сверху (в MainActivity) просто фильтрует то что уже загружено
public class HomeFragment extends Fragment {
    // ключ раздела (как поле category у игры на сервере) и заголовок, который вижу на экране
    private static final String[][] SECTIONS = {
            {"games", "Игры"},
            {"apps", "Приложения"},
            {"other", "Прочее"},
    };

    // все игры с сервера храню тут целиком, а при поиске просто перерисовываю отфильтрованные - без нового запроса
    private final List<Game> allGames = new ArrayList<>();
    private String currentQuery = "";

    private SwipeRefreshLayout swipeRefresh;
    private LinearLayout sectionsContainer;
    private TextView emptyText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        sectionsContainer = view.findViewById(R.id.sectionsContainer);
        emptyText = view.findViewById(R.id.emptyText);
        swipeRefresh = view.findViewById(R.id.swipeRefresh);
        // потянул вниз - перезагружаю список
        swipeRefresh.setOnRefreshListener(this::loadGames);
        loadGames();
        return view;
    }

    // грузим все игры одним запросом (null - без фильтра по разделу), потом раскладываю по разделам у себя
    private void loadGames() {
        swipeRefresh.setRefreshing(true);
        ApiClient.getApiService(requireContext()).games(null).enqueue(new Callback<List<Game>>() {
            @Override
            public void onResponse(Call<List<Game>> call, Response<List<Game>> response) {
                // isAdded - фрагмент ещё прикреплён к активити. если пользователь ушёл с экрана, пока шёл запрос,
                // requireContext() упал бы с исключением
                if (!isAdded()) return;
                swipeRefresh.setRefreshing(false);
                if (response.isSuccessful() && response.body() != null) {
                    allGames.clear();
                    allGames.addAll(response.body());
                    render();
                } else {
                    Toast.makeText(requireContext(), ApiErrors.message(response, "Не удалось загрузить игры"),
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<List<Game>> call, Throwable t) {
                if (!isAdded()) return;
                swipeRefresh.setRefreshing(false);
                Toast.makeText(requireContext(), ApiErrors.network(t), Toast.LENGTH_LONG).show();
            }
        });
    }

    // вызывается из MainActivity, когда пишут в поиск. если view ещё не создан - просто запомню запрос
    public void filterGames(String query) {
        currentQuery = query == null ? "" : query.trim().toLowerCase();
        if (sectionsContainer != null) render();
    }

    // перерисовываю всё с нуля: для каждого раздела собираю подходящие игры и добавляю блок с сеткой
    private void render() {
        sectionsContainer.removeAllViews();
        int shownTotal = 0;
        for (String[] section : SECTIONS) {
            List<Game> items = new ArrayList<>();
            for (Game g : allGames) {
                // игра не из этого раздела или не подходит под поиск - пропускаю
                if (!sectionOf(g).equals(section[0])) continue;
                if (!currentQuery.isEmpty() && (g.name == null || !g.name.toLowerCase().contains(currentQuery))) continue;
                items.add(g);
            }
            // пустые разделы не показываю вообще
            if (items.isEmpty()) continue;
            shownTotal += items.size();
            sectionsContainer.addView(buildSection(section[1], items));
        }
        // текст пустого экрана зависит от причины: либо игр нет совсем, либо поиск ничего не нашёл
        emptyText.setText(allGames.isEmpty() ? "Список пока пуст" : "Ничего не найдено по запросу «" + currentQuery + "»");
        emptyText.setVisibility(shownTotal == 0 ? View.VISIBLE : View.GONE);
    }

    // всё что не games и не apps (или пустая категория) уходит в "Прочее"
    private String sectionOf(Game g) {
        if ("games".equals(g.category) || "apps".equals(g.category)) return g.category;
        return "other";
    }

    // блок раздела: заголовок, количество и сетка игр по 3 в ряд
    private View buildSection(String title, List<Game> items) {
        View section = LayoutInflater.from(requireContext()).inflate(R.layout.item_home_section, sectionsContainer, false);
        ((TextView) section.findViewById(R.id.sectionTitle)).setText(title);
        ((TextView) section.findViewById(R.id.sectionCount)).setText(items.size() + " ›");

        RecyclerView grid = section.findViewById(R.id.sectionGrid);
        grid.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        // по нажатию на игру открываю её лоты, передаю id и название через extras
        GameAdapter adapter = new GameAdapter(game -> {
            Intent intent = new Intent(requireContext(), GameListingsActivity.class);
            intent.putExtra(GameListingsActivity.EXTRA_GAME_ID, game.id);
            intent.putExtra(GameListingsActivity.EXTRA_GAME_NAME, game.name);
            startActivity(intent);
        });
        grid.setAdapter(adapter);
        adapter.submitList(items);
        return section;
    }
}

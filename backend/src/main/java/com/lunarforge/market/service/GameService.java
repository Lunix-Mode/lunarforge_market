package com.lunarforge.market.service;

import com.lunarforge.market.dto.GameDtos.GameResponse;
import com.lunarforge.market.entity.Game;
import com.lunarforge.market.repository.GameRepository;
import org.springframework.stereotype.Service;

import java.util.List;

// список игр для каталога (на главной и при создании объявления).
// тут ничего сложного: достать из базы и превратить в dto
@Service
public class GameService {
    private final GameRepository gameRepository;

    public GameService(GameRepository gameRepository) {
        this.gameRepository = gameRepository;
    }

    // все игры. GameController зовёт это, если категорию не передали
    public List<GameResponse> listAll() {
        return gameRepository.findAll().stream().map(this::toResponse).toList();
    }

    // только игры нужной категории (фильтр на главном экране)
    public List<GameResponse> listByCategory(String category) {
        return gameRepository.findByCategory(category).stream().map(this::toResponse).toList();
    }

    // сущность наружу не отдаю, только dto с нужными полями -
    // так в json не утечёт лишнее и не будет проблем с lazy-связями при сериализации
    private GameResponse toResponse(Game g) {
        return new GameResponse(g.getId(), g.getName(), g.getIconUrl(), g.getCategory());
    }
}

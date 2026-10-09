package com.lunarforge.market.controller;

import com.lunarforge.market.dto.GameDtos.GameResponse;
import com.lunarforge.market.service.GameService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// список игр для главного экрана приложения. тут только чтение, никакой логики -
// всё отдаю в GameService, контроллер просто принимает http-запрос
@RestController
@RequestMapping("/api/games")
public class GameController {
    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    // GET /api/games - все игры, GET /api/games?category=... - только из этой категории
    // (поле category у самой игры). параметр необязательный, поэтому проверяю на null
    @GetMapping
    public List<GameResponse> list(@RequestParam(required = false) String category) {
        return category == null ? gameService.listAll() : gameService.listByCategory(category);
    }
}

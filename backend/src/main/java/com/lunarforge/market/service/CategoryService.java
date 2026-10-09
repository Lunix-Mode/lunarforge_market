package com.lunarforge.market.service;

import com.lunarforge.market.dto.CategoryDtos.CategoryResponse;
import com.lunarforge.market.dto.CategoryDtos.CreateCategoryRequest;
import com.lunarforge.market.entity.Category;
import com.lunarforge.market.entity.Game;
import com.lunarforge.market.exception.ApiException;
import com.lunarforge.market.repository.CategoryRepository;
import com.lunarforge.market.repository.GameRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

// логика категорий: список по игре и добавление новой (добавляет админ)
@Service
public class CategoryService {
    private final CategoryRepository categoryRepository;
    private final GameRepository gameRepository;

    public CategoryService(CategoryRepository categoryRepository, GameRepository gameRepository) {
        this.categoryRepository = categoryRepository;
        this.gameRepository = gameRepository;
    }

    // наружу отдаю DTO, а не саму сущность - иначе Jackson полез бы в ленивую связь game и вытащил лишнее
    public List<CategoryResponse> byGame(Long gameId) {
        return categoryRepository.findByGameId(gameId).stream().map(this::toResponse).toList();
    }

    public CategoryResponse create(CreateCategoryRequest request) {
        // сначала проверяю, что игра существует, иначе категория повисла бы без игры. нет игры - 404 с понятным текстом
        Game game = gameRepository.findById(request.gameId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Игра не найдена"));
        Category category = new Category();
        category.setGame(game);
        // trim - чтобы " Аккаунты " и "Аккаунты" не были разными категориями из-за пробелов
        category.setName(request.name().trim());
        categoryRepository.save(category);
        return toResponse(category);
    }

    // без public - метод для других сервисов этого пакета (например при создании объявления проверить категорию)
    Category getEntityOrThrow(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Категория не найдена"));
    }

    private CategoryResponse toResponse(Category c) {
        return new CategoryResponse(c.getId(), c.getGame().getId(), c.getName());
    }
}

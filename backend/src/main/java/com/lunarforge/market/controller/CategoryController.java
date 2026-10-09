package com.lunarforge.market.controller;

import com.lunarforge.market.dto.CategoryDtos.CategoryResponse;
import com.lunarforge.market.dto.CategoryDtos.CreateCategoryRequest;
import com.lunarforge.market.service.CategoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// категории внутри игры (например "Аккаунты", "Валюта"). контроллер тонкий - только принимает запрос и отдаёт в CategoryService.
// GET открыт всем, POST только админу - это прописано в SecurityConfig, а не тут
@RestController
@RequestMapping("/api/categories")
public class CategoryController {
    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    // GET /api/categories?gameId=1 - все категории игры, приложение показывает их вкладками на экране игры
    @GetMapping
    public List<CategoryResponse> byGame(@RequestParam Long gameId) {
        return categoryService.byGame(gameId);
    }

    // POST - добавить категорию. @Valid проверяет аннотации в CreateCategoryRequest (пустое имя -> 400 ещё до сервиса)
    @PostMapping
    public CategoryResponse create(@Valid @RequestBody CreateCategoryRequest request) {
        return categoryService.create(request);
    }
}

package com.lunarforge.market.repository;

import com.lunarforge.market.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// доступ к таблице категорий. JpaRepository уже даёт save/findById/findAll/delete,
// реализацию пишет спринг сам - я только объявляю интерфейс
public interface CategoryRepository extends JpaRepository<Category, Long> {
    // категории одной игры. запрос спринг строит сам по имени метода: WHERE game_id = ?
    List<Category> findByGameId(Long gameId);
}

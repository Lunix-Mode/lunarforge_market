package com.lunarforge.market.repository;

import com.lunarforge.market.entity.Game;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// репозиторий игр. spring data сам делает реализацию по имени метода, sql писать не надо
public interface GameRepository extends JpaRepository<Game, Long> {
    // игры одного раздела ("games" или "apps")
    List<Game> findByCategory(String category);
}

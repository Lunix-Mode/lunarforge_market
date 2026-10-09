package com.lunarforge.market.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

// категория товаров внутри игры (например "Аккаунты", "Валюта", "Предметы").
// у каждой игры свой набор категорий, поэтому категория привязана к игре, а объявления потом привязаны к категории
@Entity
@Table(name = "categories")
@Getter
@Setter
public class Category {
    // id генерирует сама база (автоинкремент), я его руками не задаю
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // много категорий к одной игре. LAZY - игру не тяну из базы, пока к ней реально не обратятся,
    // иначе на каждый список категорий был бы лишний запрос. optional = false - категории без игры не бывает
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    // название категории, пустым быть не может
    @Column(nullable = false)
    private String name;
}

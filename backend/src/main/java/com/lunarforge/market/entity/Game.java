package com.lunarforge.market.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

// игра в каталоге (под неё продавцы выставляют лоты). таблица games
@Entity
@Table(name = "games")
@Getter
@Setter
public class Game {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    // ссылка на иконку игры, может быть пустой
    private String iconUrl;

    @Column(nullable = false)
    // раздел, к которому относится игра. по умолчанию "games", чтобы старые записи не остались без раздела
    private String category = "games";
}

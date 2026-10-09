package com.lunarforge.market.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

// объявление (лот) продавца - то что показывается в списке товаров по игре.
// price - сколько получит продавец, buyerPrice - сколько платит покупатель (+5%).
// unlimited = бесконечный товар, quantity не уменьшается.
// геттеры/сеттеры генерит lombok (@Getter/@Setter), руками их не писал
@Entity
@Table(name = "listings")
@Getter
@Setter
public class Listing {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // кто продаёт. LAZY - юзера не тянем из базы, пока он реально не понадобится
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    // к какой игре относится лот, обязательно
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    // категория внутри игры, может быть null (не все лоты разложены по категориям)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    // тип лота. STRING - в базе хранится имя ("ITEM"), а не номер,
    // чтобы не поломалось, если поменять порядок в enum
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ListingType type = ListingType.ITEM;

    @Column(nullable = false)
    private String title;

    @Column(length = 2000)
    private String description;

    // деньги храню в BigDecimal, а не double - иначе копейки начнут теряться на округлении.
    // scale = 2 это два знака после запятой
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    // цена для покупателя уже с комиссией 5%, считается при сохранении лота
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal buyerPrice;

    // сколько штук осталось (для обычного товара)
    @Column(nullable = false)
    private Integer quantity = 1;

    @Column(nullable = false)
    private boolean unlimited = false;

    // как продавец передаёт товар (текст, необязательно)
    private String deliveryMethod;

    // главная картинка (обложка лота)
    private String imageUrl;

    // дополнительные фото. лежат в отдельной таблице listing_images,
    // @OrderColumn хранит позицию, чтобы порядок фото не перемешивался после загрузки из базы
    @ElementCollection
    @CollectionTable(name = "listing_images", joinColumns = @JoinColumn(name = "listing_id"))
    @OrderColumn(name = "position")
    @Column(name = "url", nullable = false)
    private java.util.List<String> extraImageUrls = new java.util.ArrayList<>();

    // false = лот снят с продажи (не удаляю физически, т.к. на него ссылаются старые заказы)
    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    // предмет, услуга (например буст) или донат
    public enum ListingType {
        ITEM, SERVICE, DONATE
    }
}

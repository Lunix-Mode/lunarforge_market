package com.lunarforge.market.service;

import com.lunarforge.market.dto.ListingDtos.CreateListingRequest;
import com.lunarforge.market.dto.ListingDtos.ListingResponse;
import com.lunarforge.market.entity.Category;
import com.lunarforge.market.entity.Game;
import com.lunarforge.market.entity.Listing;
import com.lunarforge.market.entity.User;
import com.lunarforge.market.exception.ApiException;
import com.lunarforge.market.repository.GameRepository;
import com.lunarforge.market.repository.ListingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

// вся логика объявлений: создание, витрина с фильтрами, включение/выключение.
// цену продавца и цену для покупателя (с комиссией) храню обе, чтобы не пересчитывать на каждом показе
@Service
public class ListingService {
    // комиссия 5%. в приложении (AddProductActivity) такая же цифра для подсказки цены
    public static final BigDecimal COMMISSION_RATE = new BigDecimal("0.05");

    private final ListingRepository listingRepository;
    private final GameRepository gameRepository;
    private final CategoryService categoryService;

    public ListingService(ListingRepository listingRepository, GameRepository gameRepository,
                           CategoryService categoryService) {
        this.listingRepository = listingRepository;
        this.gameRepository = gameRepository;
        this.categoryService = categoryService;
    }

    // создание объявления. порядок: проверяю что продавец не в бане, что игра есть,
    // что цена и количество нормальные, что подкатегория из этой же игры, потом собираю Listing и сохраняю.
    // @Transactional - если что-то упадёт посередине, в базе ничего не останется
    @Transactional
    public ListingResponse create(User seller, CreateListingRequest req) {
        if (seller.isBlocked()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Ваш аккаунт заблокирован, добавление товаров недоступно");
        }
        // игру ищу по id из запроса, если её нет - 404
        Game game = gameRepository.findById(req.gameId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Игра не найдена"));

        // та же проверка суммы что и у кошелька: > 0, 2 знака, не больше миллиона
        BigDecimal price = WalletService.validateAmount(req.price());
        // unlimited = "бесконечный" товар (например услуга), у него количество не считается
        boolean unlimitedReq = req.unlimited() != null && req.unlimited();
        // лимит 100000 просто чтобы не вбили какую-то дичь
        if (!unlimitedReq && (req.quantity() == null || req.quantity() < 1 || req.quantity() > 100_000)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Количество должно быть от 1 до 100000 (или отметьте «бесконечно»)");
        }

        Category category = null;
        // подкатегория необязательна. если она есть - проверяю что она от этой же игры,
        // иначе можно было бы засунуть товар по WoW в категорию от CS
        if (req.categoryId() != null) {
            category = categoryService.getEntityOrThrow(req.categoryId());
            if (!category.getGame().getId().equals(game.getId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Категория не принадлежит выбранной игре");
            }
        }

        // тип товара строкой из приложения -> enum. если не указали - ITEM. valueOf кидает исключение
        // на незнакомую строку, ловлю и отдаю нормальную 400 вместо 500
        Listing.ListingType type;
        try {
            type = req.type() == null ? Listing.ListingType.ITEM : Listing.ListingType.valueOf(req.type());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректный тип товара");
        }

        boolean unlimited = req.unlimited() != null && req.unlimited();

        Listing listing = new Listing();
        listing.setSeller(seller);
        listing.setGame(game);
        listing.setCategory(category);
        listing.setType(type);
        listing.setTitle(req.title().trim());
        listing.setDescription(req.description());
        listing.setPrice(price);
        // buyerPrice = цена продавца + 5%, это то что увидит и заплатит покупатель
        listing.setBuyerPrice(computeBuyerPrice(price));
        // для бесконечного товара quantity не важно, ставлю 0. иначе берём из запроса, по умолчанию 1
        listing.setQuantity(unlimited ? 0 : (req.quantity() == null ? 1 : req.quantity()));
        listing.setUnlimited(unlimited);
        listing.setDeliveryMethod(req.deliveryMethod());
        // ссылки на картинки проверяю, чтобы туда можно было положить только наш /files/..., а не любой внешний url
        listing.setImageUrl(checkFileUrl(req.imageUrl()));
        if (req.extraImageUrls() != null) {
            for (String url : req.extraImageUrls()) {
                String checked = checkFileUrl(url);
                if (checked != null) listing.getExtraImageUrls().add(checked);
            }
        }
        // если active не передали - товар сразу на витрине
        listing.setActive(req.active() == null || req.active());

        listingRepository.save(listing);
        return toResponse(listing);
    }

    // цена для покупателя: sellerPrice * 1.05, округляю до копеек HALF_UP (обычное школьное округление)
    private BigDecimal computeBuyerPrice(BigDecimal sellerPrice) {
        BigDecimal rate = COMMISSION_RATE;
        return sellerPrice.multiply(BigDecimal.ONE.add(rate)).setScale(2, RoundingMode.HALF_UP);
    }

    // старые методы списком без страниц, только активные товары
    public List<ListingResponse> byGame(Long gameId) {
        return listingRepository.findByGameIdAndActiveTrue(gameId).stream().map(this::toResponse).toList();
    }

    // categoryId == null значит раздел "Другое" - товары без подкатегории
    public List<ListingResponse> byGameAndCategory(Long gameId, Long categoryId) {
        List<Listing> listings = categoryId == null
                ? listingRepository.findByGameIdAndCategoryIsNullAndActiveTrue(gameId)
                : listingRepository.findByGameIdAndCategoryIdAndActiveTrue(gameId, categoryId);
        return listings.stream().map(this::toResponse).toList();
    }

    // все товары продавца (и скрытые тоже) - это для "моих товаров"
    public List<ListingResponse> bySeller(Long sellerId) {
        return listingRepository.findBySellerIdOrderByCreatedAtDesc(sellerId).stream().map(this::toResponse).toList();
    }

    // а это для чужого профиля - только активные
    public List<ListingResponse> byPublicSeller(Long sellerId) {
        return listingRepository.findBySellerIdAndActiveTrueOrderByCreatedAtDesc(sellerId).stream().map(this::toResponse).toList();
    }

    // витрина порциями: фильтры, поиск и сортировка - на сервере (иначе при миллионе товаров
    // пришлось бы тащить в телефон всё ради сортировки). size ограничен, чтобы не попросили "всё сразу"
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public com.lunarforge.market.dto.PageResponse<ListingResponse> browse(Long gameId, Long categoryId, boolean other,
                                                                          String q, String type, String sort,
                                                                          int page, int size) {
        // Specification - собираю условия WHERE по кусочкам, в зависимости от того какие фильтры пришли.
        // cb это CriteriaBuilder, root - сама таблица listings
        org.springframework.data.jpa.domain.Specification<Listing> spec = (root, query, cb) -> {
            java.util.List<jakarta.persistence.criteria.Predicate> p = new java.util.ArrayList<>();
            // скрытые и распроданные на витрину не попадают никогда
            p.add(cb.isTrue(root.get("active")));
            if (gameId != null) p.add(cb.equal(root.get("game").get("id"), gameId));
            // other = раздел "Другое" (без подкатегории), он важнее categoryId
            if (other) p.add(cb.isNull(root.get("category")));
            else if (categoryId != null) p.add(cb.equal(root.get("category").get("id"), categoryId));
            if (q != null && !q.isBlank()) {
                // поиск по названию и описанию без учёта регистра. % и _ в like - спецсимволы,
                // экранирую их обратным слэшем, чтобы поиск "100%" искал именно "100%", а не всё подряд
                String like = "%" + q.trim().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("title")), like, '\\'),
                        cb.like(cb.lower(root.get("description")), like, '\\')));
            }
            if (type != null && !type.isBlank()) {
                try {
                    p.add(cb.equal(root.get("type"), Listing.ListingType.valueOf(type)));
                } catch (IllegalArgumentException ignored) {
                    // неизвестный тип - фильтр не применяем
                }
            }
            return cb.and(p.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        // сортировка. вторым ключом id, чтобы при одинаковой цене порядок был стабильный
        // и между страницами товары не прыгали и не дублировались
        org.springframework.data.domain.Sort order = switch (sort == null ? "new" : sort) {
            case "cheap" -> org.springframework.data.domain.Sort.by("buyerPrice").ascending().and(org.springframework.data.domain.Sort.by("id").descending());
            case "expensive" -> org.springframework.data.domain.Sort.by("buyerPrice").descending().and(org.springframework.data.domain.Sort.by("id").descending());
            default -> org.springframework.data.domain.Sort.by("id").descending(); // новые сверху
        };
        // размер страницы от 1 до 100, отрицательную страницу тоже не пускаю
        int safeSize = Math.max(1, Math.min(100, size));
        org.springframework.data.domain.Page<Listing> result = listingRepository.findAll(spec,
                org.springframework.data.domain.PageRequest.of(Math.max(0, page), safeSize, order));
        // отдаю кусок + номер страницы + есть ли ещё дальше (hasNext), по нему приложение решает грузить ли следующую
        return new com.lunarforge.market.dto.PageResponse<>(
                result.getContent().stream().map(this::toResponse).toList(), result.getNumber(), result.hasNext());
    }

    // старый поиск, запрос лежит в ListingRepository.search
    public List<ListingResponse> search(String query) {
        return listingRepository.search(query).stream().map(this::toResponse).toList();
    }

    public ListingResponse getOrThrow(Long id) {
        return toResponse(getEntityOrThrow(id));
    }

    // без модификатора доступа - чтобы другие сервисы из этого пакета (заказы) могли брать саму сущность
    Listing getEntityOrThrow(Long id) {
        return listingRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Товар не найден"));
    }

    // скрыть/показать товар. только владелец может
    @Transactional
    public void setActive(User owner, Long listingId, boolean active) {
        Listing listing = getEntityOrThrow(listingId);
        if (!listing.getSeller().getId().equals(owner.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Это не ваш товар");
        }
        // распроданный вернуть на витрину нельзя
        if (active && !listing.isUnlimited() && listing.getQuantity() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Товара нет в наличии - создайте новое объявление");
        }
        listing.setActive(active);
        listingRepository.save(listing);
    }

    // сущность -> dto для приложения. категория может быть null (раздел "Другое"), поэтому проверки
    private ListingResponse toResponse(Listing l) {
        return new ListingResponse(
                l.getId(),
                l.getGame().getId(),
                l.getGame().getName(),
                l.getCategory() != null ? l.getCategory().getId() : null,
                l.getCategory() != null ? l.getCategory().getName() : null,
                l.getType().name(),
                l.getSeller().getId(),
                l.getSeller().getNickname(),
                l.getSeller().getAvatarUrl(),
                l.getTitle(),
                l.getDescription(),
                l.getPrice(),
                l.getBuyerPrice(),
                l.getQuantity(),
                l.isUnlimited(),
                l.getDeliveryMethod(),
                l.getImageUrl(),
                // копирую список в новый ArrayList, чтобы не отдавать наружу ленивую коллекцию хибернейта
                new java.util.ArrayList<>(l.getExtraImageUrls()),
                l.isActive(),
                l.getCreatedAt()
        );
    }

    // разрешаю только ссылки вида /files/<uuid>.<расширение> - то что вернул наш FileStorageService.
    // пустую ссылку просто считаю "нет фото"
    private String checkFileUrl(String url) {
        if (url == null || url.isBlank()) return null;
        if (!url.matches("^/files/[0-9a-fA-F-]{36}\\.[a-z0-9]{1,5}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректная ссылка на фото");
        }
        return url;
    }
}

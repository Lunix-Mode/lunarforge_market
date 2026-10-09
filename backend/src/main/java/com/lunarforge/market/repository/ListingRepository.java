package com.lunarforge.market.repository;

import com.lunarforge.market.entity.Listing;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

// доступ к объявлениям (товарам). методы вида findBy... спринг превращает в sql по имени,
// например findByGameIdAndActiveTrue = WHERE game_id = ? AND active = true.
// JpaSpecificationExecutor нужен для фильтров каталога, где условия собираются динамически
public interface ListingRepository extends JpaRepository<Listing, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<Listing> {
    // только активные товары игры - снятые с продажи в каталоге не показываем
    List<Listing> findByGameIdAndActiveTrue(Long gameId);

    List<Listing> findByGameIdAndCategoryIdAndActiveTrue(Long gameId, Long categoryId);

    // товары без категории (когда в фильтре выбрано "без категории", categoryId == null)
    List<Listing> findByGameIdAndCategoryIsNullAndActiveTrue(Long gameId);

    // все товары продавца, включая неактивные, новые сверху
    List<Listing> findBySellerIdOrderByCreatedAtDesc(Long sellerId);

    // только активные товары продавца, новые сверху
    List<Listing> findBySellerIdAndActiveTrueOrderByCreatedAtDesc(Long sellerId);

    // поиск по подстроке в названии или описании. LOWER с обеих сторон - чтобы регистр не мешал.
    // :query подставляется как параметр, а не склейкой строки, так что sql-инъекции не будет
    @Query("SELECT l FROM Listing l WHERE l.active = true AND " +
           "(LOWER(l.title) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(l.description) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<Listing> search(@Param("query") String query);

    // лочим товар при покупке, чтобы два покупателя не купили последнюю штуку одновременно
    // PESSIMISTIC_WRITE = SELECT ... FOR UPDATE: второй запрос ждёт, пока первая транзакция не закончится,
    // и потом видит уже уменьшенный остаток. работает только внутри @Transactional
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Listing e WHERE e.id = :id")
    Optional<Listing> lockById(@Param("id") Long id);
}

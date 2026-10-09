package com.lunarforge.market.repository;

import com.lunarforge.market.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

// репозиторий пользователей. большинство методов Spring Data генерирует сам по названию
public interface UserRepository extends JpaRepository<User, Long> {
    // поиск по почте - через него идёт вход (почта у меня и есть логин)
    Optional<User> findByEmail(String email);
    Optional<User> findByNickname(String nickname);
    Optional<User> findByUsername(String username);
    // exists-методы нужны при регистрации и смене ника/юзернейма: проверить, что не занято
    boolean existsByEmail(String email);
    boolean existsByNickname(String nickname);
    boolean existsByUsername(String username);

    // блокировка строки юзера на время транзакции (защита от двойного списания)
    // PESSIMISTIC_WRITE это SELECT ... FOR UPDATE: пока моя транзакция не закончилась,
    // другая транзакция будет ждать на этой же строке. без этого две покупки одновременно
    // прочитали бы один и тот же баланс и обе прошли бы, хотя денег хватает только на одну
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM User e WHERE e.id = :id")
    Optional<User> lockById(@Param("id") Long id);

    // все юзеры с нужными ролями (например список модераторов и админов для админ-панели)
    java.util.List<User> findByRoleIn(java.util.Collection<User.Role> roles);

    // модераторы, которых сняло автоматически за низкий рейтинг (роль уже USER, но флаг стоит)
    java.util.List<User> findByDemotedAutomaticallyTrueAndRole(User.Role role);

    // все бывшие модераторы: сняты за рейтинг, за блокировку или админом вручную
    java.util.List<User> findByDemotedAtIsNotNullAndRoleOrderByDemotedAtDesc(User.Role role);
}

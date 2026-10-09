package com.lunarforge.market.service;

import com.lunarforge.market.dto.UserDtos.PublicProfile;
import com.lunarforge.market.dto.UserDtos.UserProfile;
import com.lunarforge.market.dto.UserDtos.UserStats;
import com.lunarforge.market.entity.Order;
import com.lunarforge.market.entity.User;
import com.lunarforge.market.exception.ApiException;
import com.lunarforge.market.repository.OrderRepository;
import com.lunarforge.market.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

// сервис пользователей: профиль, статистика, смена ника и аватарки.
// тут же собираю dto профиля из entity, чтобы не отдавать наружу пароль и прочее
@Service
public class UserService {
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;

    public UserService(UserRepository userRepository, OrderRepository orderRepository) {
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
    }

    // достать юзера по id или сразу 404
    public User getOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
    }

    // профиль для самого владельца - тут есть почта и балансы (доступный и замороженный)
    // последний аргумент - флаг "бывший модератор": роль USER, но дата снятия есть
    public UserProfile toProfile(User u) {
        return new UserProfile(u.getId(), u.getNickname(), u.getUsername(), u.getEmail(), u.getAvatarUrl(),
                u.getBalanceAvailable(), u.getBalanceFrozen(), u.getRole().name(),
                averageRating(u), u.getRatingCount(), u.getCreatedAt(),
                u.isBlocked(), u.getBlockReason(),
                u.getRole() == User.Role.USER && u.getDemotedAt() != null);
    }

    // публичный профиль для других людей - без почты и денег
    public PublicProfile toPublicProfile(User u) {
        return new PublicProfile(u.getId(), u.getNickname(), u.getUsername(), u.getAvatarUrl(),
                averageRating(u), u.getRatingCount(), u.getCreatedAt(), u.getRole().name(), u.isBlocked());
    }

    // средняя оценка = сумма оценок / количество. если отзывов нет - 0, иначе было бы деление на ноль
    private double averageRating(User u) {
        return u.getRatingCount() == 0 ? 0.0 : (double) u.getRatingSum() / u.getRatingCount();
    }

    public UserStats stats(Long userId) {
        // потрачено/заработано считаю только по завершённым заказам (запросы в OrderRepository),
        // у потраченного вычитается то, что вернули по спору
        BigDecimal spent = orderRepository.totalSpent(userId);
        BigDecimal earned = orderRepository.totalEarned(userId);
        long purchases = orderRepository.countByBuyerIdAndStatus(userId, Order.OrderStatus.COMPLETED);
        long sales = orderRepository.countBySellerIdAndStatus(userId, Order.OrderStatus.COMPLETED);
        return new UserStats(spent, earned, purchases, sales);
    }

    // имя бота занимать нельзя, а то можно подделать сообщения от бота.
    // длина ника 2..32 после trim
    public static void validateNickname(String nickname) {
        String n = nickname == null ? "" : nickname.trim();
        if (n.length() < 2 || n.length() > 32) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Никнейм должен быть от 2 до 32 символов");
        }
        // убираю все пробелы и сравниваю без регистра, чтобы "Lunar Bot" или "LUNARBOT" тоже не прошли
        if (n.replaceAll("\\s+", "").equalsIgnoreCase("lunarbot")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Это имя зарезервировано");
        }
    }

    // смена ника: сначала проверка, потом сохраняю уже обрезанный по пробелам
    @org.springframework.transaction.annotation.Transactional
    public UserProfile changeNickname(Long userId, String nickname) {
        validateNickname(nickname);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        user.setNickname(nickname.trim());
        userRepository.save(user);
        return toProfile(user);
    }

    @org.springframework.transaction.annotation.Transactional
    public UserProfile changeAvatar(Long userId, String avatarUrl) {
        // аватар принимаю только как ссылку на файл, который загружен на наш сервер (/files/uuid.расширение).
        // так нельзя подсунуть ссылку на чужой сайт или какой-то левый путь
        if (avatarUrl == null || !avatarUrl.matches("^/files/[0-9a-fA-F-]{36}\\.(jpg|jpeg|png|webp|gif|heic)$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректная картинка для аватара");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Пользователь не найден"));
        user.setAvatarUrl(avatarUrl);
        userRepository.save(user);
        return toProfile(user);
    }
}

package com.lunarforge.market.config;

import com.lunarforge.market.entity.Category;
import com.lunarforge.market.entity.Game;
import com.lunarforge.market.entity.User;
import com.lunarforge.market.repository.CategoryRepository;
import com.lunarforge.market.repository.GameRepository;
import com.lunarforge.market.repository.UserRepository;
import com.lunarforge.market.service.ChatService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

// при старте заполняет базу: бот, админ, стартовые игры.
// можно гонять сколько угодно раз, дубли не создаёт
// CommandLineRunner - спринг вызывает run() один раз сразу после запуска приложения
@Component
public class DataSeeder implements CommandLineRunner {
    // TODO перед нормальным релизом поменять пароль админа!!
    // флаг из application.properties, по умолчанию true (после двоеточия - значение если свойства нет)
    @org.springframework.beans.factory.annotation.Value("${lunar.seed-test-users:true}")
    private boolean seedTestUsers;

    // данные создателя площадки
    private static final String ADMIN_EMAIL = "lunixmode.dev@gmail.com";
    private static final String ADMIN_NICKNAME = "Lunix";
    private static final String ADMIN_USERNAME = "lunix";
    private static final String ADMIN_PASSWORD = "admin";

    private final GameRepository gameRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    // JdbcTemplate - чтобы выполнить сырой sql для починки старых ограничений в базе
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public DataSeeder(GameRepository gameRepository, CategoryRepository categoryRepository,
                       UserRepository userRepository, PasswordEncoder passwordEncoder,
                       org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.gameRepository = gameRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // порядок важен: сначала чиним старую схему, потом системные юзеры, игры только если база
    // пустая
    @Override
    public void run(String... args) {
        dropLegacyNicknameUniqueness();
        dropStaleEnumChecks();
        seedAdmin();       // первым - чтобы на чистой базе у создателя был id 1
        seedBot();
        seedTestUsers();

        // игры только в пустую базу, иначе на каждом рестарте будут копии
        // count() > 0 значит игры уже есть - выхожу
        if (gameRepository.count() > 0) return;

        // игра + её категории товаров. второй параметр - раздел (игры или приложения)
        Game roblox = seedGame("Roblox", "games");
        seedCategory(roblox, "Robux");
        seedCategory(roblox, "Roblox Plus");
        seedCategory(roblox, "Плейсы");

        Game pubg = seedGame("PUBG", "games");
        seedCategory(pubg, "UC");
        seedCategory(pubg, "Донат");
        seedCategory(pubg, "Аккаунты");

        seedGame("Minecraft", "games");
        seedGame("Steam", "apps");
        seedGame("Telegram", "apps");
        seedGame("Discord", "apps");
    }

    // создатель площадки. создаём ПЕРВЫМ - на чистой базе он получает id 1.
    // если в базе остался старый админ (admin@lunarforge.market) - переименовываем его, чтоб доход не потерять
    private void seedAdmin() {
        // ищу текущего админа, если нет - старого по прежнему email, если и его нет - создаю нового
        User admin = userRepository.findByEmail(ADMIN_EMAIL)
                .or(() -> userRepository.findByEmail("admin@lunarforge.market"))
                .orElseGet(User::new);
        // проверяю, не занял ли логин "lunix" кто-то другой (не сам админ), иначе save упадёт на уникальности
        boolean usernameTakenBySomeoneElse = userRepository.findByUsername(ADMIN_USERNAME)
                .filter(other -> admin.getId() == null || !other.getId().equals(admin.getId()))
                .isPresent();
        if (usernameTakenBySomeoneElse) {
            System.out.println("[DataSeeder] @" + ADMIN_USERNAME + " уже занят обычным пользователем - админ не изменён");
            return; // выходим только из этого метода - бот и тестовые аккаунты всё равно создадутся
        }
        admin.setEmail(ADMIN_EMAIL);
        admin.setNickname(ADMIN_NICKNAME);
        admin.setUsername(ADMIN_USERNAME);
        admin.setRole(User.Role.ADMIN);
        // пароль перезаписываю только если юзер новый или хеш не совпадает с ADMIN_PASSWORD, чтобы не пересчитывать хеш при каждом старте
        if (admin.getId() == null || !passwordEncoder.matches(ADMIN_PASSWORD, admin.getPasswordHash())) {
            admin.setPasswordHash(passwordEncoder.encode(ADMIN_PASSWORD));
        }
        userRepository.save(admin);
    }

    // бот пишет в чаты про заказы, сам никогда не логинится
    private void seedBot() {
        if (userRepository.findByEmail(ChatService.BOT_EMAIL).isPresent()) return;
        User bot = new User();
        bot.setEmail(ChatService.BOT_EMAIL);
        bot.setNickname("Lunar Bot");
        bot.setUsername("lunarbot");
        // рандомный пароль - под ботом зайти нельзя
        bot.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        userRepository.save(bot);
    }

    // тестовые аккаунты test1..test3 / пароль 123456 - чтобы не регистрировать заново после каждой очистки базы.
    // ВЫКЛЮЧИТЬ перед настоящим запуском: lunar.seed-test-users=false в application.properties
    private void seedTestUsers() {
        // выключено в настройках - ничего не создаю
        if (!seedTestUsers) return;
        for (int i = 1; i <= 3; i++) {
            String name = "test" + i;
            String email = name + "@gmail.com";
            // если такой уже есть - пропускаю, повторный запуск не создаёт дубли
            if (userRepository.findByEmail(email).isPresent() || userRepository.findByUsername(name).isPresent()) continue;
            User u = new User();
            u.setEmail(email);
            u.setNickname(name);
            u.setUsername(name);
            u.setPasswordHash(passwordEncoder.encode("123456"));
            userRepository.save(u);
        }
    }

    // помощники для стартовых данных
    private Game seedGame(String name, String category) {
        Game g = new Game();
        g.setName(name);
        g.setCategory(category);
        return gameRepository.save(g);
    }

    private void seedCategory(Game game, String name) {
        Category c = new Category();
        c.setGame(game);
        c.setName(name);
        categoryRepository.save(c);
    }

    // раньше ники были уникальные, в старых базах остался unique constraint.
    // hibernate его сам не удаляет, сношу руками. если его нет - ничего не произойдёт
    private void dropLegacyNicknameUniqueness() {
        // DO $$ ... $$ - анонимный блок на PL/pgSQL (постгрес). ищу unique-ограничения на одной колонке nickname таблицы users и удаляю каждое
        jdbc.execute("""
            DO $$
            DECLARE r record;
            BEGIN
              FOR r IN
                SELECT con.conname
                FROM pg_constraint con
                JOIN pg_class rel ON rel.oid = con.conrelid
                JOIN pg_attribute att ON att.attrelid = rel.oid AND att.attnum = ANY (con.conkey)
                WHERE rel.relname = 'users' AND con.contype = 'u'
                  AND att.attname = 'nickname' AND array_length(con.conkey, 1) = 1
              LOOP
                EXECUTE 'ALTER TABLE users DROP CONSTRAINT ' || quote_ident(r.conname);
              END LOOP;
            END $$;
            """);
    }

    // hibernate 6 при создании таблиц вешает CHECK со списком значений enum и потом его не обновляет.
    // добавили DISPUTED / MODERATOR / VIDEO_NOTE - вставка падала бы. сносим эти проверки
    private void dropStaleEnumChecks() {
        // тут удаляю все CHECK-ограничения в этих таблицах, в определении которых есть ARRAY[ - это и есть списки значений enum.
        // %I в format экранирует имена таблиц/ограничений, чтобы sql не сломался
        jdbc.execute("""
            DO $$
            DECLARE r record;
            BEGIN
              FOR r IN
                SELECT con.conname, rel.relname
                FROM pg_constraint con
                JOIN pg_class rel ON rel.oid = con.conrelid
                WHERE con.contype = 'c'
                  AND rel.relname IN ('users', 'orders', 'chat_messages', 'wallet_transactions', 'listings', 'tickets')
                  AND pg_get_constraintdef(con.oid) LIKE '%ARRAY[%'
              LOOP
                EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', r.relname, r.conname);
              END LOOP;
            END $$;
            """);
    }
}

package com.lunarforge.market;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// запуск сервера. EnableScheduling нужен для разморозки денег продавцов (см.
// WalletReleaseScheduler)
// без этой аннотации методы с @Scheduled просто не будут вызываться, и деньги
// после 48 часов холда так и остались бы замороженными
// @SpringBootApplication сам сканирует все пакеты ниже com.lunarforge.market
// (контроллеры, сервисы, репозитории), поэтому руками ничего регистрировать не надо
@SpringBootApplication
@EnableScheduling
public class MarketApplication {
    // обычная точка входа: поднимает спринг, встроенный tomcat и всё остальное
    public static void main(String[] args) {
        SpringApplication.run(MarketApplication.class, args);
    }
}

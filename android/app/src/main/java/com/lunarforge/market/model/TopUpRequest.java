package com.lunarforge.market.model;

// тело запроса на пополнение кошелька (POST api/users/me/topup).
// денег по-настоящему никто не списывает - это демо, номер карты нужен только чтобы форма выглядела как настоящая
public class TopUpRequest {
    // double тут ок - это только ввод юзера. на сервере сумма сразу становится BigDecimal, считать деньги в double нельзя (копейки поплывут)
    public double amount;
    public String cardNumber;

    public TopUpRequest(double amount, String cardNumber) {
        this.amount = amount;
        this.cardNumber = cardNumber;
    }
}

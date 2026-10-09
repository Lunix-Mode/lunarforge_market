package com.lunarforge.market.model;

// тело запроса на вывод денег с кошелька на карту.
// проверка, что денег хватает и карта нормальная, делается на сервере - на клиенте это можно обойти
public class WithdrawRequest {
    public double amount;
    public String cardNumber;

    public WithdrawRequest(double amount, String cardNumber) {
        this.amount = amount;
        this.cardNumber = cardNumber;
    }
}

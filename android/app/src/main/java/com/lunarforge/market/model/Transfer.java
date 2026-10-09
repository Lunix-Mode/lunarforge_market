package com.lunarforge.market.model;

// перевод денег другому пользователю внутри площадки (с кошелька на кошелёк)
public class Transfer {
    public long id;
    public long senderId;
    public String senderUsername;
    public long receiverId;
    public String receiverUsername;
    // double только для показа и отправки, на сервере деньги считаются в BigDecimal
    public double amount;
    // необязательная подпись к переводу
    public String message;
    public String createdAt;

    // что отправляю на сервер при переводе - получателя ищут по username, а не по id,
    // так юзеру проще: он вводит логин который видит в профиле
    public static class CreateRequest {
        public String toUsername;
        public double amount;
        public String message;

        public CreateRequest(String toUsername, double amount, String message) {
            this.toUsername = toUsername;
            this.amount = amount;
            this.message = message;
        }
    }
}

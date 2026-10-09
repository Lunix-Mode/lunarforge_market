package com.lunarforge.market.model;

// отзыв о продавце, как его отдаёт сервер (GET api/ratings/...). Gson сам раскладывает json по полям с такими же именами,
// поэтому поля public и без геттеров - это просто контейнер данных
public class Rating {
    public long id;
    public long orderId;
    // оценка 1..5 звёзд
    public int score;
    public String comment;
    // сумма заказа, округлённая сервером до 10 ₽ - видно примерно сколько купили, но конкретный заказ не вычислить
    public long roundedAmount;
    // даты приходят строкой (ISO), на экране их форматирую сам
    public String createdAt;
    public String updatedAt;

    // кто оставил отзыв. сервер заполняет это ТОЛЬКО когда смотрит сам продавец, остальным приходит null.
    // поэтому Long, а не long - примитив не может быть null
    public Long raterId;
    public String raterNickname;
    public String raterUsername;

    // тело запроса "оставить отзыв" (POST api/ratings)
    public static class CreateRequest {
        public long orderId;
        public int score;
        public String comment;

        public CreateRequest(long orderId, int score, String comment) {
            this.orderId = orderId;
            this.score = score;
            this.comment = comment;
        }
    }

    // тело запроса "изменить отзыв" (PUT api/ratings/{id}) - заказ уже не нужен, он привязан к отзыву
    public static class UpdateRequest {
        public int score;
        public String comment;

        public UpdateRequest(int score, String comment) {
            this.score = score;
            this.comment = comment;
        }
    }
}

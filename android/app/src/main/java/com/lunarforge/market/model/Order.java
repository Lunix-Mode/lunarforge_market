package com.lunarforge.market.model;

// заказ, как его присылает сервер. поля публичные и названы как в json -
// gson сам раскладывает ответ по ним, геттеры не нужны
public class Order {
    public long id;
    // какое объявление купили: id - чтобы открыть его, название - чтобы сразу показать в списке
    public long listingId;
    public String listingTitle;
    public long buyerId;
    public String buyerNickname;
    public long sellerId;
    public String sellerNickname;
    // amount - сколько заплатил покупатель, commissionAmount - 5% площадке,
    // sellerAmount - что дойдёт продавцу (amount минус комиссия). считает всё сервер, я только показываю
    public double amount;
    public double commissionAmount;
    public double sellerAmount;
    public int quantity;
    // статус сделки строкой (например COMPLETED), по нему экран заказа решает какие кнопки показать
    public String status;
    public Double refundedAmount; // сколько вернули по спору (null - не было)
    // даты приходят строкой ISO, форматирую уже при показе
    public String createdAt;
    // когда покупатель подтвердил получение (null - ещё не подтвердил)
    public String confirmedAt;

    // тело запроса на покупку: что и сколько штук. цену не шлю - её считает сервер, иначе можно подделать
    public static class CreateRequest {
        public long listingId;
        public int quantity;

        public CreateRequest(long listingId, int quantity) {
            this.listingId = listingId;
            this.quantity = quantity;
        }
    }
}

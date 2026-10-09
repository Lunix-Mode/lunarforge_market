package com.lunarforge.market.model;

// одна операция кошелька, как её присылает сервер (gson сам раскладывает json по полям с такими же именами).
// используется в истории операций и на экране подробностей операции
public class WalletTransaction {
    public long id;
    public String type;                  // TOP_UP, PURCHASE, TRANSFER_IN... строкой, как enum на сервере
    public double amount;                // со знаком: минус - списание, плюс - поступление
    public String description;
    public String createdAt;             // дата строкой в ISO, форматирую уже при показе
    public String title;                 // "Входящий перевод", "Продажа"...
    // с кем операция (кому перевёл / от кого / покупатель-продавец). Long, а не long - может прийти null
    public Long counterpartyId;
    public String counterpartyNickname;
    public String counterpartyUsername;
    public String counterpartyAvatarUrl;
    public Long orderId;
    public String orderTitle;
    public String message;               // сообщение к переводу
    public String releaseAt;             // продажа: когда разморозится
    public Boolean released;             // продажа: уже разморожено или ещё ждём 48ч (null для остальных операций)
}

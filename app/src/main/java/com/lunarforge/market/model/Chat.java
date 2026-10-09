package com.lunarforge.market.model;

// всё что связано с чатом в одном файле: диалог, сообщение и запросы. поля один в один как в dto на сервере
public class Chat {
    // диалог между покупателем и продавцом (в списке чатов)
    public static class Thread {
        public long id;
        // храню обе стороны, а кто из них "собеседник" определяю на клиенте, сравнивая со своим userId
        public long buyerId;
        public String buyerNickname;
        public String buyerAvatarUrl;
        public long sellerId;
        public String sellerNickname;
        public String sellerAvatarUrl;
        // Long, а не long - чат может быть без привязки к объявлению, тогда приходит null
        public Long listingId;
        public String listingTitle;
        // для списка чатов: кусок последнего сообщения и время, по нему же сортировка
        public String lastMessagePreview;
        public String lastMessageAt;
    }

    // одно сообщение в диалоге
    public static class Message {
        public long id;
        public long threadId;
        public long senderId;
        public String senderNickname;
        public String senderUsername;
        public String senderRole;     // USER / MODERATOR / ADMIN
        public String senderAvatarUrl; // аватар отправителя (в чате над чужими сообщениями)
        public String text;
        // ссылка на загруженный файл, если это фото/видео/голосовое/кружок
        public String attachmentUrl;
        // тип вложения, по нему адаптер решает как рисовать сообщение
        public String attachmentType;
        // длительность для голосовых и кружков, Integer потому что у текста и фото её нет
        public Integer attachmentDurationSeconds;
        // время отправки строкой ISO
        public String sentAt;
    }

    // начать диалог с продавцом (если такой уже есть, сервер вернёт существующий)
    public static class StartThreadRequest {
        public long sellerId;
        public Long listingId;

        public StartThreadRequest(long sellerId, Long listingId) {
            this.sellerId = sellerId;
            this.listingId = listingId;
        }
    }

    // что отправляю при отправке сообщения. сначала файл грузится отдельно, потом сюда кладу его url
    public static class SendMessageRequest {
        public String text;
        public String attachmentUrl;
        public String attachmentType;
        public Integer attachmentDurationSeconds;

        // просто текстовое сообщение
        public SendMessageRequest(String text) {
            this.text = text;
        }

        // сообщение с вложением
        public SendMessageRequest(String text, String attachmentUrl, String attachmentType, Integer attachmentDurationSeconds) {
            this.text = text;
            this.attachmentUrl = attachmentUrl;
            this.attachmentType = attachmentType;
            this.attachmentDurationSeconds = attachmentDurationSeconds;
        }
    }

    // ответ на загрузку файла - url, который потом кладу в SendMessageRequest
    public static class UploadResponse {
        public String url;
    }
}

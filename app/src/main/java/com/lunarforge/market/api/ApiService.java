package com.lunarforge.market.api;

import com.lunarforge.market.model.Admin;
import com.lunarforge.market.model.AuthModels.AuthResponse;
import com.lunarforge.market.model.AuthModels.LoginRequest;
import com.lunarforge.market.model.AuthModels.RegisterRequest;
import com.lunarforge.market.model.Category;
import com.lunarforge.market.model.Chat;
import com.lunarforge.market.model.Game;
import com.lunarforge.market.model.Listing;
import com.lunarforge.market.model.Order;
import com.lunarforge.market.model.PublicProfile;
import com.lunarforge.market.model.Rating;
import com.lunarforge.market.model.TopUpRequest;
import com.lunarforge.market.model.Transfer;
import com.lunarforge.market.model.User;
import com.lunarforge.market.model.UserStats;
import com.lunarforge.market.model.WalletTransaction;
import com.lunarforge.market.model.WithdrawRequest;

import java.util.List;

import okhttp3.MultipartBody;
import retrofit2.Call;
import retrofit2.http.*;

// все запросы к моему серверу в одном месте. это интерфейс для Retrofit: я только описываю адрес и параметры,
// а сам код запроса Retrofit генерирует сам. вызываю так: ApiClient.getApiService(this).me().enqueue(...)
// Call<T> - запрос ещё не отправлен; enqueue отправляет его в фоне и возвращает ответ уже в главном потоке.
// токен (Authorization: Bearer ...) сюда не пишу в каждый метод - его добавляет перехватчик в ApiClient
//
// --- вход / регистрация (эти два открыты без токена, в ответе приходит JWT) ---
public interface ApiService {
    @POST("api/auth/register")
    Call<AuthResponse> register(@Body RegisterRequest request);

    @POST("api/auth/login")
    Call<AuthResponse> login(@Body LoginRequest request);

    // --- мой профиль ---
    @GET("api/users/me")
    Call<User> me();

    // аватар и ник меняются частично, поэтому PATCH, а не PUT
    @PATCH("api/users/me/avatar")
    Call<User> changeAvatar(@Body User.ChangeAvatarRequest request);

    @PATCH("api/users/me/nickname")
    Call<User> changeNickname(@Body User.ChangeNicknameRequest request);

    @GET("api/users/me/stats")
    Call<UserStats> myStats();

    // история операций кошелька целиком (без порций). есть ещё myTransactionsPage ниже
    @GET("api/users/me/transactions")
    Call<List<WalletTransaction>> myTransactions();

    // чужой профиль (витрина продавца) - сервер пускает сюда даже без логина
    @GET("api/users/{id}/public-profile")
    Call<PublicProfile> publicProfile(@Path("id") long id);

    // пополнение и вывод. возвращают обновлённого User, чтобы сразу показать новый баланс без второго запроса.
    // реальной оплаты нет - это учебный проект, номер карты только для вида
    @POST("api/users/me/topup")
    Call<User> topUp(@Body TopUpRequest request);

    @POST("api/users/me/withdraw")
    Call<User> withdraw(@Body WithdrawRequest request);

    // --- переводы другому юзеру по @username ---
    @POST("api/transfers")
    Call<Transfer> sendTransfer(@Body Transfer.CreateRequest request);

    @GET("api/transfers")
    Call<List<Transfer>> myTransfers();

    // --- витрина: игры, категории, объявления ---
    // category = null - Retrofit не добавит параметр и сервер вернёт все игры
    @GET("api/games")
    Call<List<Game>> games(@Query("category") String category);

    @GET("api/categories")
    Call<List<Category>> categoriesByGame(@Query("gameId") long gameId);

    // --- объявления ---
    @POST("api/listings")
    Call<Listing> createListing(@Body Listing.CreateRequest request);

    @GET("api/listings/{id}")
    Call<Listing> getListing(@Path("id") long id);

    @GET("api/listings/game/{gameId}")
    Call<List<Listing>> listingsByGame(@Path("gameId") long gameId);

    @GET("api/listings/game/{gameId}/category/{categoryId}")
    Call<List<Listing>> listingsByCategory(@Path("gameId") long gameId, @Path("categoryId") long categoryId);

    @GET("api/listings/game/{gameId}/other")
    Call<List<Listing>> listingsOther(@Path("gameId") long gameId);

    @GET("api/listings/mine")
    Call<List<Listing>> myListings();

    @GET("api/listings/seller/{sellerId}")
    Call<List<Listing>> listingsBySeller(@Path("sellerId") long sellerId);

    // скрыть/показать своё объявление. ответ пустой, поэтому Void
    @PATCH("api/listings/{id}/active")
    Call<Void> setListingActive(@Path("id") long id, @Query("active") boolean active);

    @GET("api/listings/search")
    Call<List<Listing>> searchListings(@Query("q") String query);

    // --- порциями (null-параметры Retrofit просто не отправляет) ---
    // тут подгрузка для бесконечной ленты: page с 0, size - сколько штук за раз.
    // в ответе PageResponse - список + есть ли ещё страницы, чтобы знать, когда перестать грузить
    @GET("api/listings/browse")
    Call<com.lunarforge.market.model.PageResponse<Listing>> browseListings(@Query("gameId") Long gameId,
            @Query("categoryId") Long categoryId, @Query("other") boolean other, @Query("q") String q,
            @Query("type") String type, @Query("sort") String sort, @Query("page") int page, @Query("size") int size);

    // тот же адрес, что и myPurchases ниже, но с page/size - для списков, которые грузятся по мере прокрутки
    @GET("api/orders/purchases")
    Call<List<Order>> myPurchasesPage(@Query("page") int page, @Query("size") int size);

    @GET("api/orders/sales")
    Call<List<Order>> mySalesPage(@Query("page") int page, @Query("size") int size);

    @GET("api/users/me/transactions")
    Call<List<WalletTransaction>> myTransactionsPage(@Query("page") int page, @Query("size") int size);

    // последние limit сообщений; с beforeId - limit сообщений старее него
    // beforeId = null значит "самые свежие". когда листаю чат вверх - передаю id самого старого из загруженных
    @GET("api/chat/threads/{id}/messages")
    Call<List<Chat.Message>> messagesPage(@Path("id") long threadId, @Query("beforeId") Long beforeId, @Query("limit") int limit);

    // --- заказы ---
    // покупка: сервер списывает деньги и замораживает их, пока покупатель не подтвердит получение
    @POST("api/orders")
    Call<Order> purchase(@Body Order.CreateRequest request);

    @GET("api/orders/{id}")
    Call<Order> getOrder(@Path("id") long id);

    // подтвердить получение - после этого продавцу деньги приходят, но ещё 48ч их нельзя вывести
    @POST("api/orders/{id}/confirm")
    Call<Order> confirmOrder(@Path("id") long id);

    // отмена заказа (деньги возвращаются покупателю)
    @POST("api/orders/{id}/cancel")
    Call<Order> cancelOrder(@Path("id") long id);

    @GET("api/orders/purchases")
    Call<List<Order>> myPurchases();

    @GET("api/orders/sales")
    Call<List<Order>> mySales();

    // --- чат ---
    // открыть чат с продавцом (или получить уже существующий)
    @POST("api/chat/threads")
    Call<Chat.Thread> startThread(@Body Chat.StartThreadRequest request);

    @GET("api/chat/threads")
    Call<List<Chat.Thread>> myThreads();

    @POST("api/chat/threads/{id}/messages")
    Call<Chat.Message> sendMessage(@Path("id") long threadId, @Body Chat.SendMessageRequest request);

    // старый способ: всё что новее after. сейчас для пагинации есть messagesPage выше
    @GET("api/chat/threads/{id}/messages")
    Call<List<Chat.Message>> messages(@Path("id") long threadId, @Query("after") String after);

    // загрузка файла (фото/видео/голосовое/кружок) отдельным запросом. сервер вернёт url,
    // а потом этот url уходит в sendMessage. @Multipart нужен, потому что шлём файл, а не json
    @Multipart
    @POST("api/files/upload")
    Call<Chat.UploadResponse> uploadFile(@Part MultipartBody.Part file);

    // --- отзывы ---
    // отзыв можно оставить только на завершённый заказ, менять - не чаще раза в сутки (проверяет сервер)
    @POST("api/ratings")
    Call<Rating> createRating(@Body Rating.CreateRequest request);

    @PUT("api/ratings/{id}")
    Call<Rating> updateRating(@Path("id") long id, @Body Rating.UpdateRequest request);

    @GET("api/ratings/seller/{sellerId}")
    Call<List<Rating>> ratingsForSeller(@Path("sellerId") long sellerId);

    // мой отзыв на конкретный заказ - чтобы на экране заказа показать "изменить" вместо "оставить"
    @GET("api/ratings/order/{orderId}/mine")
    Call<Rating> myRatingForOrder(@Path("orderId") long orderId);

    // --- Заявки (пользователь) ---
    // возврат - заказ на сервере сразу замораживается (DISPUTED) до решения модератора
    @POST("api/tickets/refund")
    Call<com.lunarforge.market.model.Ticket> requestRefund(@Body com.lunarforge.market.model.Ticket.RefundRequest request);

    @POST("api/tickets/order-problem")
    Call<com.lunarforge.market.model.Ticket> orderProblem(@Body com.lunarforge.market.model.Ticket.OrderProblemRequest request);

    @POST("api/tickets/complaint")
    Call<com.lunarforge.market.model.Ticket> complain(@Body com.lunarforge.market.model.Ticket.ComplaintRequest request);

    @POST("api/tickets/account")
    Call<com.lunarforge.market.model.Ticket> accountProblem(@Body com.lunarforge.market.model.Ticket.TextRequest request);

    @POST("api/tickets/unblock")
    Call<com.lunarforge.market.model.Ticket> unblockAppeal(@Body com.lunarforge.market.model.Ticket.TextRequest request);

    @POST("api/tickets/reinstatement")
    Call<com.lunarforge.market.model.Ticket> reinstatement(@Body com.lunarforge.market.model.Ticket.TextRequest request);

    // обжаловать решение модератора (один раз, в течение 7 дней - проверяет сервер)
    @POST("api/tickets/{id}/appeal")
    Call<com.lunarforge.market.model.Ticket> appealDecision(@Path("id") long id, @Body com.lunarforge.market.model.Ticket.TextRequest request);

    // оценка решения модератора 👍/👎 - из них складывается его рейтинг
    @POST("api/tickets/{id}/feedback")
    Call<com.lunarforge.market.model.Ticket> ticketFeedback(@Path("id") long id, @Body com.lunarforge.market.model.Ticket.FeedbackRequest request);

    @GET("api/tickets/mine")
    Call<List<com.lunarforge.market.model.Ticket>> myTickets();

    @GET("api/tickets/{id}")
    Call<com.lunarforge.market.model.Ticket> ticket(@Path("id") long id);

    // последняя заявка по заказу (или пустой ответ, если заявок не было)
    @GET("api/tickets/order/{orderId}")
    Call<com.lunarforge.market.model.Ticket> ticketForOrder(@Path("orderId") long orderId);

    // --- Модерация ---
    // эти адреса сервер пускает только для MODERATOR и ADMIN.
    // filter/type/party - null значит не передавать, будет фильтр по умолчанию
    @GET("api/staff/tickets")
    Call<List<com.lunarforge.market.model.Ticket>> staffTickets(@Query("filter") String filter, @Query("type") String type, @Query("party") String party);

    @GET("api/staff/tickets/{id}")
    Call<com.lunarforge.market.model.Ticket> staffTicket(@Path("id") long id);

    // взять заявку себе (или перехватить, если прошлый модератор долго молчит)
    @POST("api/staff/tickets/{id}/claim")
    Call<com.lunarforge.market.model.Ticket> claimTicket(@Path("id") long id);

    // вынести решение - может только тот, кто заявку взял
    @POST("api/staff/tickets/{id}/resolve")
    Call<com.lunarforge.market.model.Ticket> resolveTicket(@Path("id") long id, @Body com.lunarforge.market.model.Ticket.ResolveRequest request);

    @POST("api/staff/users/{userId}/block")
    Call<com.lunarforge.market.model.Ticket.Block> blockUser(@Path("userId") long userId, @Body com.lunarforge.market.model.Ticket.BlockRequest request);

    @POST("api/staff/users/{userId}/unblock")
    Call<Void> unblockUser(@Path("userId") long userId, @Body com.lunarforge.market.model.Ticket.TextRequest request);

    @GET("api/staff/users/{userId}/blocks")
    Call<List<com.lunarforge.market.model.Ticket.Block>> userBlocks(@Path("userId") long userId);

    // --- Админ: модераторы ---
    // только для админа (/api/admin/** на сервере закрыт ролью ADMIN)
    @GET("api/admin/moderators")
    Call<List<com.lunarforge.market.model.Ticket.Moderator>> moderators();

    @GET("api/admin/moderators/demoted")
    Call<List<com.lunarforge.market.model.Ticket.Moderator>> demotedModerators();

    @GET("api/admin/moderators/{userId}/stats")
    Call<com.lunarforge.market.model.Ticket.ModeratorStats> moderatorStats(@Path("userId") long userId);

    @POST("api/admin/moderators/{userId}")
    Call<com.lunarforge.market.model.Ticket.Moderator> appointModerator(@Path("userId") long userId);

    @DELETE("api/admin/moderators/{userId}")
    Call<Void> dismissModerator(@Path("userId") long userId);

    // --- Уведомления ---
    // список моих уведомлений для экрана с колокольчиком
    @GET("api/notifications")
    Call<List<com.lunarforge.market.model.Notification>> notifications();

    // только число непрочитанных - лёгкий запрос, его можно дёргать часто
    @GET("api/notifications/unread-count")
    Call<com.lunarforge.market.model.Notification.UnreadCount> unreadCount();

    // пометить всё прочитанным, когда открыли экран уведомлений
    @POST("api/notifications/read-all")
    Call<Void> readAllNotifications();

    // --- Операция кошелька подробно ---
    @GET("api/users/me/transactions/{id}")
    Call<com.lunarforge.market.model.WalletTransaction> transaction(@Path("id") long id);

    // доход площадки с комиссии 5% - всего и по месяцам
    @GET("api/admin/revenue")
    Call<Admin.Revenue> adminRevenue();
}

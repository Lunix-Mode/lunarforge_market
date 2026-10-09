package com.lunarforge.market.repository;

import com.lunarforge.market.entity.Ticket;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

// запросы к заявкам модерации. большинство методов spring data собирает сам по имени
// (findBy...OrderBy... = WHERE + ORDER BY), где сложнее - написал @Query на JPQL
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    // очередь заявок с нужными статусами, старые первыми (кто раньше написал - того раньше и разбирают)
    List<Ticket> findByStatusInOrderByCreatedAtAsc(Collection<Ticket.Status> statuses);

    // заявки конкретного модератора в определённом статусе ("мои в работе")
    List<Ticket> findByAssigneeIdAndStatusOrderByCreatedAtAsc(Long assigneeId, Ticket.Status status);

    // вообще все заявки, свежие сверху (для админа)
    List<Ticket> findAllByOrderByCreatedAtDesc();

    // заявки, которые подал этот юзер
    List<Ticket> findByReporterIdOrderByCreatedAtDesc(Long reporterId);

    // активные заявки по чату - нужны чтобы обновлять таймеры и пускать модератора писать
    List<Ticket> findByThreadIdAndStatusIn(Long threadId, Collection<Ticket.Status> statuses);

    // есть ли вообще заявка по этому чату - если да, модератору можно читать переписку
    boolean existsByThreadId(Long threadId);

    // последняя заявка по заказу (их может быть несколько, берём самую новую)
    Optional<Ticket> findFirstByOrderIdOrderByCreatedAtDesc(Long orderId);

    // заявки в одном статусе - старые первыми / решённые по времени решения, свежие сверху
    List<Ticket> findByStatusOrderByCreatedAtAsc(Ticket.Status status);
    List<Ticket> findByStatusOrderByResolvedAtDesc(Ticket.Status status);
    // заявки по списку id (например все, что модератор когда-либо брал - id из TicketClaim)
    List<Ticket> findByIdInOrderByCreatedAtDesc(Collection<Long> ids);
    // для статистики модератора: сколько решено / сколько сейчас в работе
    long countByAssigneeIdAndStatus(Long assigneeId, Ticket.Status status);
    // обжаловали ли уже это решение - второй раз обжаловать нельзя
    boolean existsByAppealOfId(Long ticketId);
    // защита от дублей: нет ли у юзера уже открытой заявки такого же типа по этому заказу
    boolean existsByReporterIdAndOrderIdAndTypeAndStatusIn(Long reporterId, Long orderId, Ticket.Type type, Collection<Ticket.Status> statuses);

    // мои заявки: где я заявитель или сторона заказа. LEFT JOIN обязателен - иначе заявки без заказа
    // (аккаунт, жалоба, разблокировка) молча выпадут из выборки.
    // DISTINCT на всякий случай, чтобы одна заявка не пришла дважды
    @Query("SELECT DISTINCT t FROM Ticket t LEFT JOIN t.order o WHERE t.reporter.id = :uid OR o.buyer.id = :uid OR o.seller.id = :uid ORDER BY t.createdAt DESC")
    List<Ticket> findVisibleTo(@Param("uid") Long uid);
    // то же что выше, но без заказа: нет ли уже открытой заявки этого типа (например разблокировка)
    boolean existsByReporterIdAndTypeAndStatusIn(Long reporterId, Ticket.Type type, Collection<Ticket.Status> statuses);

    // обжалования, которые признали правыми - против решений этого модератора.
    // enum'ы передаём параметрами, а не литералами в запросе - так надёжнее во всех версиях hibernate
    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.type = :type AND t.resolution = :res AND t.appealOf.assignee.id = :staffId")
    long countAppeals(@Param("staffId") Long staffId, @Param("type") Ticket.Type type, @Param("res") Ticket.Resolution res);

    // удобная обёртка: сколько решений модератора отменили по обжалованию (default-метод прямо в интерфейсе)
    default long countOverturnedDecisions(Long staffId) {
        return countAppeals(staffId, Ticket.Type.DECISION_APPEAL, Ticket.Resolution.OVERTURNED);
    }

    // лочим заявку когда её берут/перехватывают/закрывают - чтобы два модератора не взяли одновременно.
    // PESSIMISTIC_WRITE = SELECT ... FOR UPDATE, второй запрос ждёт пока первая транзакция не закончится
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Ticket t WHERE t.id = :id")
    Optional<Ticket> lockById(@Param("id") Long id);
}

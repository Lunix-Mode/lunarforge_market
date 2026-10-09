package com.lunarforge.market.dto;

import java.util.List;

// одна порция длинного списка. hasMore = есть ещё, приложение подгрузит следующую при прокрутке
// page - номер этой порции (с нуля). generic T - чтобы одним классом отдавать и товары, и заказы, и сообщения.
// так не надо грузить сразу тысячу записей - и сервер, и телефон работают быстрее
public record PageResponse<T>(List<T> items, int page, boolean hasMore) {}

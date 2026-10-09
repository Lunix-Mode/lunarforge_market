package com.lunarforge.market.model;

import java.util.List;

// используется для подгрузки при прокрутке (история кошелька, отзывы и т.п.)
// порция списка с сервера: items + есть ли ещё
public class PageResponse<T> {
    // сами элементы этой страницы
    public List<T> items;
    // номер страницы (с нуля)
    public int page;
    // если false - дальше не грузим, список закончился
    public boolean hasMore;
}

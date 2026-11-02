package com.example.finance.report;

import com.example.finance.domain.Expense;
import com.example.finance.domain.Income;
import com.example.finance.domain.Operation;
import com.example.finance.domain.Transfer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class TestData {

    static final UUID MAIN = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID CARD = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    static final Expense RENT_SEPTEMBER = expense(2, MAIN, "450.00", "Аренда", "2026-09-01T10:00:00Z", null);
    static final Expense RENT_OCTOBER = expense(12, MAIN, "450.00", "Аренда", "2026-10-01T10:00:00Z", null);
    static final Expense LAPTOP = expense(13, MAIN, "1500.00", "Техника", "2026-10-08T14:00:00Z", "Ноутбук");

    /** 23:30 UTC 31 октября = 02:30 1 ноября по Минску. */
    static final Expense CAFE_AT_MONTH_BOUNDARY = expense(21, MAIN, "75.00", "Кафе", "2026-10-31T23:30:00Z", null);

    private TestData() {}

    static List<Operation> sample() {
        return List.of(
                // сентябрь: доходы 2150.00, расходы 890.99
                income(1, MAIN, "2000.00", "Зарплата", "2026-09-05T09:00:00Z"),
                RENT_SEPTEMBER,
                expense(3, MAIN, "120.50", "Продукты", "2026-09-03T15:00:00Z", null),
                expense(4, MAIN, "85.20", "Продукты", "2026-09-12T11:00:00Z", null),
                expense(5, MAIN, "30.00", "Кафе", "2026-09-14T13:00:00Z", null),
                expense(6, CARD, "60.00", "Транспорт", "2026-09-20T08:00:00Z", null),
                transfer(7, MAIN, CARD, "300.00", "2026-09-10T12:00:00Z"),
                income(8, CARD, "150.00", "Подработка", "2026-09-25T17:00:00Z"),
                expense(9, MAIN, "45.30", "Кафе", "2026-09-27T18:00:00Z", null),
                expense(10, CARD, "99.99", "Подписки", "2026-09-30T20:30:00Z", null),
                // октябрь: доходы 2100.00, расходы 2444.99
                income(11, MAIN, "2000.00", "Зарплата", "2026-10-05T09:00:00Z"),
                RENT_OCTOBER,
                LAPTOP,
                expense(14, MAIN, "210.40", "Продукты", "2026-10-10T11:00:00Z", null),
                income(15, CARD, "100.00", "Подработка", "2026-10-12T17:00:00Z"),
                expense(16, CARD, "60.00", "Транспорт", "2026-10-15T08:00:00Z", null),
                expense(17, MAIN, "35.00", "Кафе", "2026-10-17T13:00:00Z", null),
                transfer(18, CARD, MAIN, "100.00", "2026-10-20T12:00:00Z"),
                expense(19, MAIN, "89.60", "Продукты", "2026-10-24T16:00:00Z", null),
                expense(20, CARD, "99.99", "Подписки", "2026-10-30T20:00:00Z", null),
                // ноябрь: доходы 2200.00, расходы 910.49
                CAFE_AT_MONTH_BOUNDARY,
                income(22, MAIN, "2000.00", "Зарплата", "2026-11-05T09:00:00Z"),
                expense(23, MAIN, "450.00", "Аренда", "2026-11-01T10:00:00Z", null),
                expense(24, MAIN, "130.00", "Продукты", "2026-11-07T11:00:00Z", null),
                expense(25, CARD, "60.00", "Транспорт", "2026-11-15T08:00:00Z", null),
                income(26, CARD, "200.00", "Подработка", "2026-11-18T17:00:00Z"),
                expense(27, MAIN, "55.50", "Кафе", "2026-11-21T13:00:00Z", null),
                transfer(28, MAIN, CARD, "250.00", "2026-11-22T12:00:00Z"),
                expense(29, MAIN, "40.00", "Подарки", "2026-11-28T15:00:00Z", null),
                expense(30, CARD, "99.99", "Подписки", "2026-11-30T20:00:00Z", null));
    }

    static Income income(int id, UUID account, String amount, String category, String at) {
        return new Income(id(id), account, new BigDecimal(amount), category, Instant.parse(at), null);
    }

    static Expense expense(int id, UUID account, String amount, String category, String at, String description) {
        return new Expense(id(id), account, new BigDecimal(amount), category, Instant.parse(at), description);
    }

    static Transfer transfer(int id, UUID from, UUID to, String amount, String at) {
        return new Transfer(id(id), from, to, new BigDecimal(amount), Instant.parse(at));
    }

    private static UUID id(int n) {
        return new UUID(0, n);
    }
}

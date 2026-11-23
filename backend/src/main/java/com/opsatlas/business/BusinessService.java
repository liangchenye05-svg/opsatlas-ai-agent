package com.opsatlas.business;

import com.opsatlas.security.IdentityService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BusinessService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public record Period(LocalDate from, LocalDate to) {}
    public record Statistics(long orders, long refundedOrders, BigDecimal grossSales, BigDecimal refundAmount,
                             BigDecimal netSales, BigDecimal refundRate) {}
    public record Change(BigDecimal ordersPercent, BigDecimal grossSalesPercent, BigDecimal refundRatePoints) {}
    public record Dashboard(Period period, Period comparison, List<Long> storeIds, Statistics current,
                            Statistics previous, Change change, List<BusinessMapper.Daily> trend) {}

    private final IdentityService identities;
    private final BusinessMapper data;

    public BusinessService(IdentityService identities, BusinessMapper data) {
        this.identities = identities;
        this.data = data;
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(long userId, Long storeId, Long regionId, LocalDate from, LocalDate to) {
        Period period = period(from, to);
        long days = ChronoUnit.DAYS.between(period.from(), period.to()) + 1;
        Period previous = new Period(period.from().minusDays(days), period.from().minusDays(1));
        var stores = identities.storeIds(userId, storeId, regionId);
        var current = statistics(data.totals(stores, period.from().atStartOfDay(), period.to().plusDays(1).atStartOfDay()));
        var earlier = statistics(data.totals(stores, previous.from().atStartOfDay(), previous.to().plusDays(1).atStartOfDay()));
        var change = new Change(change(BigDecimal.valueOf(current.orders()), BigDecimal.valueOf(earlier.orders())),
                change(current.grossSales(), earlier.grossSales()), current.refundRate().subtract(earlier.refundRate()));
        return new Dashboard(period, previous, stores, current, earlier, change,
                data.daily(stores, period.from().atStartOfDay(), period.to().plusDays(1).atStartOfDay()));
    }

    public List<BusinessMapper.OrderRow> orders(long userId, Long storeId, Long regionId, LocalDate from, LocalDate to, int page) {
        Period period = period(from, to);
        return data.orders(identities.storeIds(userId, storeId, regionId), period.from().atStartOfDay(),
                period.to().plusDays(1).atStartOfDay(), 50, page * 50);
    }

    public List<BusinessMapper.RefundRow> refunds(long userId, Long storeId, Long regionId, LocalDate from, LocalDate to, int page) {
        Period period = period(from, to);
        return data.refunds(identities.storeIds(userId, storeId, regionId), period.from().atStartOfDay(),
                period.to().plusDays(1).atStartOfDay(), 50, page * 50);
    }

    public Period period(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            LocalDate monday = LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return new Period(monday.minusWeeks(1), monday.minusDays(1));
        }
        if (from == null || to == null || from.isBefore(LocalDate.of(2000, 1, 1))
                || to.isAfter(LocalDate.of(2100, 1, 1)) || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 89) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 2000–2100 年内、长度不超过 90 天的有效日期范围");
        }
        return new Period(from, to);
    }

    private Statistics statistics(BusinessMapper.Totals row) {
        BigDecimal rate = row.orders() == 0 ? BigDecimal.ZERO.setScale(2)
                : BigDecimal.valueOf(row.refundedOrders() * 100L)
                        .divide(BigDecimal.valueOf(row.orders()), 2, RoundingMode.HALF_UP);
        return new Statistics(row.orders(), row.refundedOrders(), row.grossSales(), row.refundAmount(),
                row.grossSales().subtract(row.refundAmount()), rate);
    }

    private BigDecimal change(BigDecimal current, BigDecimal previous) {
        return previous.signum() == 0 ? null
                : current.subtract(previous).movePointRight(2).divide(previous, 2, RoundingMode.HALF_UP);
    }
}

package com.opsatlas.business;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BusinessMapper {
    record Totals(long orders, long refundedOrders, BigDecimal grossSales, BigDecimal refundAmount) {}
    record Daily(LocalDate day, long orders, long refundedOrders, BigDecimal grossSales, BigDecimal refundAmount) {}
    record OrderRow(long id, long storeId, LocalDateTime orderedAt, BigDecimal amount) {}
    record RefundRow(long id, long orderId, long storeId, LocalDateTime refundedAt, BigDecimal amount, String reason) {}

    String SCOPE = """
        o.store_id IN
        <foreach collection="stores" item="store" open="(" separator="," close=")">#{store}</foreach>
        AND o.ordered_at &gt;= #{start} AND o.ordered_at &lt; #{end}
        """;

    String REFUNDS = """
        FROM retail_order o LEFT JOIN (
            SELECT order_id, SUM(amount) AS amount
            FROM retail_refund WHERE status = 'APPROVED' AND refunded_at &lt; #{end}
            GROUP BY order_id
        ) r ON r.order_id = o.id
        WHERE
        """;

    String COLUMNS = """
        COUNT(*) AS orders, COUNT(r.order_id) AS refunded_orders,
        COALESCE(SUM(o.amount), 0) AS gross_sales,
        COALESCE(SUM(r.amount), 0) AS refund_amount
        """;

    @Select("<script>SELECT " + COLUMNS + REFUNDS + SCOPE + "</script>")
    Totals totals(@Param("stores") List<Long> stores, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("<script>SELECT DATE(o.ordered_at) AS day, " + COLUMNS + REFUNDS + SCOPE
            + " GROUP BY DATE(o.ordered_at) ORDER BY day</script>")
    List<Daily> daily(@Param("stores") List<Long> stores, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("<script>SELECT o.id,o.store_id,o.ordered_at,o.amount FROM retail_order o WHERE " + SCOPE
            + " ORDER BY o.ordered_at DESC,o.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<OrderRow> orders(@Param("stores") List<Long> stores, @Param("start") LocalDateTime start,
                          @Param("end") LocalDateTime end, @Param("size") int size, @Param("offset") int offset);

    @Select("<script>SELECT r.id,r.order_id,o.store_id,r.refunded_at,r.amount,r.reason "
            + "FROM retail_refund r JOIN retail_order o ON o.id=r.order_id WHERE " + SCOPE
            + " AND r.refunded_at &lt; #{end} AND r.status='APPROVED' "
            + "ORDER BY r.refunded_at DESC,r.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<RefundRow> refunds(@Param("stores") List<Long> stores, @Param("start") LocalDateTime start,
                            @Param("end") LocalDateTime end, @Param("size") int size, @Param("offset") int offset);
}

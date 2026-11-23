START TRANSACTION READ ONLY;

SELECT 'dataset', DATE(MIN(ordered_at)), DATE(MAX(ordered_at)), COUNT(*),
       (SELECT COUNT(*) FROM retail_refund),
       (SELECT COUNT(*) FROM retail_refund WHERE status = 'APPROVED')
FROM retail_order;

SELECT 'migration', version, checksum FROM flyway_schema_history
WHERE success AND version IS NOT NULL ORDER BY installed_rank;

WITH anchor AS (
    SELECT DATE_ADD(DATE(MAX(ordered_at)), INTERVAL 1 DAY) AS cutoff FROM retail_order
), windows AS (
    SELECT 'week:current' AS label, DATE_SUB(cutoff, INTERVAL 7 DAY) AS since, cutoff AS until_date FROM anchor
    UNION ALL SELECT 'week:previous', DATE_SUB(cutoff, INTERVAL 14 DAY), DATE_SUB(cutoff, INTERVAL 7 DAY) FROM anchor
    UNION ALL SELECT 'fortnight:current', DATE_SUB(cutoff, INTERVAL 14 DAY), cutoff FROM anchor
    UNION ALL SELECT 'fortnight:previous', DATE_SUB(cutoff, INTERVAL 28 DAY), DATE_SUB(cutoff, INTERVAL 14 DAY) FROM anchor
    UNION ALL SELECT 'empty:current', cutoff, DATE_ADD(cutoff, INTERVAL 7 DAY) FROM anchor
    UNION ALL SELECT 'empty:previous', DATE_SUB(cutoff, INTERVAL 7 DAY), cutoff FROM anchor
), per_order AS (
    SELECT w.label, s.id AS store_id, o.id AS order_id, o.amount,
           (SELECT SUM(r.amount) FROM retail_refund r
            WHERE r.order_id = o.id AND r.status = 'APPROVED' AND r.refunded_at < w.until_date) AS refund_total
    FROM windows w CROSS JOIN store s
    LEFT JOIN retail_order o ON o.store_id = s.id AND o.ordered_at >= w.since AND o.ordered_at < w.until_date
)
SELECT 'total', label, store_id, COUNT(order_id), COUNT(refund_total),
       COALESCE(SUM(amount),0), COALESCE(SUM(refund_total),0)
FROM per_order GROUP BY label,store_id ORDER BY label,store_id;

ROLLBACK;

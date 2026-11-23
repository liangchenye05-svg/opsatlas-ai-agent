INSERT INTO region VALUES (1,'华东区'),(2,'华南区');
INSERT INTO store VALUES (1,'徐汇店',1),(2,'浦东店',1),(3,'天河店',2),(4,'福田店',2);
INSERT INTO account (id,username,display_name,role_code,password_hash,region_id) VALUES
  (1,'hq','总部运营','HEADQUARTERS','!',NULL),
  (2,'east','华东区域经理','REGION','!',1),
  (3,'xuhui','徐汇店长','STORE','!',NULL),
  (4,'tianhe','天河店长','STORE','!',NULL);
INSERT INTO account_store VALUES (3,1),(4,3);

SET @monday = DATE_SUB(CURDATE(), INTERVAL WEEKDAY(CURDATE()) DAY);
INSERT INTO retail_order (id,store_id,ordered_at,amount)
WITH RECURSIVE days AS (SELECT 0 AS n UNION ALL SELECT n+1 FROM days WHERE n<13),
slots AS (SELECT 1 AS n UNION ALL SELECT n+1 FROM slots WHERE n<6)
SELECT 100000+s.id*1000+d.n*10+k.n, s.id,
       TIMESTAMP(DATE_ADD(@monday, INTERVAL (d.n-14) DAY), MAKETIME(8+k.n, 15, 0)),
       55+s.id*24+d.n*2.35+k.n*8.20
FROM store s JOIN days d JOIN slots k;

INSERT INTO retail_refund (id,order_id,refunded_at,amount,reason,status)
SELECT id,id,DATE_ADD(ordered_at, INTERVAL 2 HOUR),ROUND(amount*0.65,2),
       CASE WHEN store_id=1 AND MOD(id,1000)>=70 THEN '同批商品质量投诉' ELSE '尺寸不合适' END,
       'APPROVED'
FROM retail_order
WHERE MOD(id,10)=1 AND (
    (store_id=1 AND (MOD(id,1000)>=70 OR MOD(id,1000)=11))
    OR (store_id<>1 AND MOD(id,1000) IN (21,91))
);

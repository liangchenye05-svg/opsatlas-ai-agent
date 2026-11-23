# 经营 API

| 方法和路径 | 用途 |
| --- | --- |
| GET /api/health | 检查应用和 MySQL |
| POST /api/auth/login | JSON 包含 username、password。成功返回 accessToken 和 user |
| GET /api/me | 当前账号 |
| GET /api/stores | 当前账号可访问的门店 |
| GET /api/dashboard | 指标、等长环比及日趋势。支持 storeId、regionId、from、to |
| GET /api/orders | 按门店与订单下单日期查询。page 从 0 开始，每页 50 条 |
| GET /api/refunds | 按相关订单与门店查询统计截止时已批准退款。分页同订单 |

日期采用 YYYY-MM-DD。日期缺省时返回上周，范围上限 90 天。受保护接口通过 Authorization Bearer 传递登录令牌。已登录但无门店访问权时返回 403，参数错误返回 400。

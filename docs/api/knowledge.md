# 企业制度知识库 API

鉴权规则沿用业务 JWT。GET /api/knowledge/documents 查看来源索引。总部运营可通过 POST /api/knowledge/documents 上传 UTF-8 文本，表单字段为 title 和 file。单文档上限 256 KB。

GET /api/knowledge/search?q=问题&limit=4 返回 BM25、Qdrant 向量和 RRF 三路结果。GET /api/knowledge/answer?q=问题显示可核对的制度片段，证据不足返回 grounded=false。GET /api/knowledge/citations/{id} 返回片段原文与位置。GET /api/knowledge/index 返回向量模型和已索引点数。

默认向量化是 384 维字符二元组特征哈希，适用于可独立运行的词项基线。Qdrant 执行真实向量检索。可通过 OPSATLAS_EMBEDDING_URL、OPSATLAS_EMBEDDING_MODEL、OPSATLAS_EMBEDDING_DIMENSIONS 和可选 OPSATLAS_EMBEDDING_API_KEY 替换为 OpenAI 兼容的向量化服务。配置外部向量化服务将向该服务传送业务制度文本与检索词。DeepSeek 聊天配置独立。 [Qdrant 文档](https://qdrant.tech/documentation/quickstart/)

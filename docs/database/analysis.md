# 分析与知识数据库

V3 引入 knowledge_document 与 knowledge_chunk。来源、标题、段号和原文在 MySQL，向量索引在独立 Qdrant 卷。写入制度时只有数据库提交后的片段可被引用。

V4 引入 analysis_session、weekly_report 和 operation_audit。analysis_session 以账号为所有者并保存草稿 JSON。weekly_report 对 analysis_id 建立唯一约束，报告保存与审计在同一确认事务内。审计保留动作、目标、结果及时间。向量引用 ID 是来自 MySQL 的 UUID。

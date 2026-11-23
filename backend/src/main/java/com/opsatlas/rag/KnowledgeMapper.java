package com.opsatlas.rag;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeMapper {
    record Document(String id, String title, String source, Long uploadedBy) {}
    record Chunk(String id, String documentId, String title, String source, int ordinal, String content) {}

    String SELECT_CHUNKS = """
        SELECT c.id, c.document_id, d.title, d.source, c.ordinal, c.content
        FROM knowledge_chunk c JOIN knowledge_document d ON d.id=c.document_id
        """;

    @Select("SELECT id,title,source,uploaded_by FROM knowledge_document ORDER BY created_at DESC,id")
    List<Document> documents();

    @Select(SELECT_CHUNKS + " ORDER BY d.source,c.ordinal")
    List<Chunk> chunks();

    @Select(SELECT_CHUNKS + " WHERE c.id=#{id}")
    Chunk chunk(String id);

    @Select("SELECT COUNT(*) FROM knowledge_document WHERE source=#{source}")
    int countSource(String source);

    @Insert("INSERT INTO knowledge_document (id,title,source,uploaded_by) VALUES (#{id},#{title},#{source},#{uploadedBy,jdbcType=BIGINT})")
    int addDocument(Document document);

    @Insert("INSERT INTO knowledge_chunk (id,document_id,ordinal,content) VALUES (#{id},#{documentId},#{ordinal},#{content})")
    int addChunk(Chunk chunk);
}

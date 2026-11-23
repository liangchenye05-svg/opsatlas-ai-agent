package com.opsatlas.report;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ReportMapper {
    record Session(String id, long accountId, String question, String state, String draft, String failure, LocalDateTime createdAt) {}
    record Report(String id, String analysisId, String content, LocalDateTime confirmedAt) {}
    record Audit(long id, String actionCode, String target, String outcome, LocalDateTime createdAt) {}

    String SESSION = "SELECT id,account_id,question,state,draft,failure,created_at FROM analysis_session";

    @Insert("INSERT INTO analysis_session(id,account_id,question,state) VALUES(#{id},#{user},#{question},'RUNNING')")
    int begin(@Param("id") String id, @Param("user") long user, @Param("question") String question);

    @Update("UPDATE analysis_session SET state='DRAFT', draft=#{draft} WHERE id=#{id} AND state='RUNNING'")
    int draft(@Param("id") String id, @Param("draft") String draft);

    @Update("UPDATE analysis_session SET state='FAILED', failure=#{problem} WHERE id=#{id} AND state='RUNNING'")
    int fail(@Param("id") String id, @Param("problem") String problem);

    @Update("UPDATE analysis_session SET state='FAILED', failure='服务重启，任务中断' WHERE state='RUNNING'")
    int recover();

    @Select(SESSION + " WHERE id=#{id} AND account_id=#{owner}")
    Session session(@Param("id") String id, @Param("owner") long owner);

    @Select(SESSION + " WHERE id=#{id} AND account_id=#{owner} FOR UPDATE")
    Session lock(@Param("id") String id, @Param("owner") long owner);

    @Select(SESSION + " WHERE account_id=#{owner} ORDER BY created_at DESC,id DESC LIMIT 30")
    List<Session> sessions(long owner);

    @Insert("INSERT INTO weekly_report(id,account_id,analysis_id,content) VALUES(#{id},#{owner},#{analysis},#{content})")
    int addReport(@Param("id") String id, @Param("owner") long owner, @Param("analysis") String analysis, @Param("content") String content);

    @Update("UPDATE analysis_session SET state='CONFIRMED' WHERE id=#{id}")
    int confirm(String id);

    @Select("SELECT id,analysis_id,content,confirmed_at FROM weekly_report WHERE analysis_id=#{id} AND account_id=#{owner}")
    Report report(@Param("id") String id, @Param("owner") long owner);

    @Select("SELECT id,analysis_id,content,confirmed_at FROM weekly_report WHERE account_id=#{owner} ORDER BY confirmed_at DESC,id DESC LIMIT 30")
    List<Report> reports(long owner);

    @Insert("INSERT INTO operation_audit(account_id,action_code,target,outcome) VALUES(#{owner},#{code},#{target},#{outcome})")
    int audit(@Param("owner") long owner, @Param("code") String code, @Param("target") String target, @Param("outcome") String outcome);

    @Select("SELECT id,action_code,target,outcome,created_at FROM operation_audit WHERE account_id=#{owner} ORDER BY id DESC LIMIT 100")
    List<Audit> auditRows(long owner);
}

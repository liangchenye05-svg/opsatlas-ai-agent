package com.opsatlas.security;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IdentityMapper {
    enum Role { HEADQUARTERS, REGION, STORE }
    record Account(long id, String username, String displayName, String passwordHash, Role roleCode, Long regionId) {}
    record Store(long id, String name, long regionId, String regionName) {}

    @Select("""
        SELECT id, username, display_name, password_hash, role_code, region_id
        FROM account WHERE username = #{username} AND enabled = 1
        """)
    Account byName(String username);

    @Select("""
        SELECT id, username, display_name, password_hash, role_code, region_id
        FROM account WHERE id = #{id} AND enabled = 1
        """)
    Account byId(long id);

    @Select("""
        SELECT s.id, s.name, s.region_id, r.name AS region_name
        FROM store s JOIN region r ON r.id = s.region_id
        WHERE #{roleCode} = 'HEADQUARTERS'
          OR (#{roleCode} = 'REGION' AND s.region_id = #{regionId,jdbcType=BIGINT})
          OR (#{roleCode} = 'STORE' AND EXISTS (
               SELECT 1 FROM account_store access WHERE access.store_id = s.id AND access.account_id = #{id}
          ))
        ORDER BY s.id
        """)
    List<Store> stores(Account account);
}

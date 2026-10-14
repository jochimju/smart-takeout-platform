package com.sky.account;

import org.apache.ibatis.annotations.*;
import java.util.Date;

@Mapper
public interface SessionMapper {
    @Select("select auth_version from employee where id = #{id}") Long version(Long id);
    @Select("select count(1) from revoked_token where jti = #{jti} and expires_at > CURRENT_TIMESTAMP") int revoked(String jti);
    @Insert("insert into revoked_token(jti, expires_at) values(#{jti}, #{expires}) on duplicate key update expires_at = values(expires_at)")
    void revoke(@Param("jti") String jti, @Param("expires") Date expires);
    @Delete("delete from revoked_token where expires_at <= CURRENT_TIMESTAMP") void purge();
}

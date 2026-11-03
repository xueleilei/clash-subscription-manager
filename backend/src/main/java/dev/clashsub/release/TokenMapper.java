package dev.clashsub.release;

import org.apache.ibatis.annotations.*;

@Mapper
public interface TokenMapper {
    String COLUMNS = "id,owner_id,version,current_release_id,token_hash,token_cipher";
    @Select("SELECT " + COLUMNS + " FROM configuration_profile WHERE owner_id=#{owner} AND id=#{id} AND is_deleted=0")
    TokenDO find(@Param("owner") String owner, @Param("id") String id);
    @Select("SELECT " + COLUMNS + " FROM configuration_profile WHERE token_hash=#{hash} AND is_deleted=0")
    TokenDO byHash(String hash);
    @Update("UPDATE configuration_profile SET token_hash=#{hash},token_cipher=#{cipher} WHERE owner_id=#{owner} AND id=#{id} AND token_hash IS NULL AND is_deleted=0")
    int initialize(@Param("owner") String owner, @Param("id") String id, @Param("hash") String hash, @Param("cipher") String cipher);
    @Update("UPDATE configuration_profile SET token_hash=#{hash},token_cipher=#{cipher},version=version+1 WHERE owner_id=#{owner} AND id=#{id} AND version=#{expected} AND is_deleted=0")
    int rotate(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected, @Param("hash") String hash, @Param("cipher") String cipher);
}

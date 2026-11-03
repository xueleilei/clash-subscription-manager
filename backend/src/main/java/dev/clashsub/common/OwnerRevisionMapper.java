package dev.clashsub.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OwnerRevisionMapper {
    @Update("UPDATE app_user SET data_revision = data_revision + 1 WHERE id = #{owner} AND data_revision = #{expected}")
    int increment(@Param("owner") String owner, @Param("expected") long expected);
    @Select("SELECT data_revision FROM app_user WHERE id = #{owner}")
    Long revision(String owner);
}

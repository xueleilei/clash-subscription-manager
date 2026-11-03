package dev.clashsub.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {
    @Select("SELECT id, username, password_hash, data_revision FROM app_user WHERE username = #{username}")
    AppUserDO findByUsername(String username);

    @Select("SELECT COUNT(*) FROM app_user")
    long count();

    @Insert("INSERT INTO app_user(id, username, password_hash) VALUES(#{id}, #{username}, #{passwordHash})")
    int insert(AppUserDO user);
}

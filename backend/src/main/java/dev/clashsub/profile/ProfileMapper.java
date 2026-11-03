package dev.clashsub.profile;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ProfileMapper {
    String COLUMNS = "id,owner_id,name,version,current_release_id,create_time,update_time";
    @Select("SELECT " + COLUMNS + " FROM configuration_profile WHERE owner_id=#{owner} AND is_deleted=0 ORDER BY create_time,id")
    List<ProfileDO> list(String owner);
    @Select("SELECT " + COLUMNS + " FROM configuration_profile WHERE owner_id=#{owner} AND id=#{id} AND is_deleted=0")
    ProfileDO find(@Param("owner") String owner, @Param("id") String id);
    @Insert("INSERT INTO configuration_profile(id,owner_id,name) VALUES(#{id},#{ownerId},#{name})")
    int insert(ProfileDO row);
    @Insert("INSERT INTO profile_draft(id,owner_id,profile_id,version,document_cipher) VALUES(#{id},#{ownerId},#{profileId},#{version},#{documentCipher})")
    int insertDraft(ProfileDraftDO row);
    @Select("SELECT id,owner_id,profile_id,version,document_cipher FROM profile_draft WHERE owner_id=#{owner} AND profile_id=#{profile}")
    ProfileDraftDO draft(@Param("owner") String owner, @Param("profile") String profile);
    @Update("UPDATE profile_draft SET version=version+1,document_cipher=#{cipher} WHERE owner_id=#{owner} AND profile_id=#{profile} AND version=#{expected}")
    int updateDraft(@Param("owner") String owner, @Param("profile") String profile, @Param("expected") long expected, @Param("cipher") String cipher);
    @Update("UPDATE configuration_profile SET version=version+1 WHERE owner_id=#{owner} AND id=#{id} AND version=#{expected} AND is_deleted=0")
    int touch(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected);
    @Update("UPDATE configuration_profile SET version=version+1,name=#{name} WHERE owner_id=#{owner} AND id=#{id} AND version=#{expected} AND is_deleted=0")
    int rename(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected, @Param("name") String name);
    @Update("UPDATE configuration_profile SET version=version+1,is_deleted=1 WHERE owner_id=#{owner} AND id=#{id} AND version=#{expected} AND is_deleted=0")
    int delete(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected);
}

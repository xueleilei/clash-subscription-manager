package dev.clashsub.release;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ReleaseMapper {
    String METADATA = "id,owner_id,profile_id,version,definition_hash,yaml_digest,node_count,reason";
    @Select("SELECT " + METADATA + ",payload_cipher,create_time FROM profile_release WHERE owner_id=#{owner} AND profile_id=#{profile} AND id=#{id}")
    ReleaseDO find(@Param("owner") String owner, @Param("profile") String profile, @Param("id") String id);
    @Select("SELECT " + METADATA + ",NULL AS payload_cipher,create_time FROM profile_release WHERE owner_id=#{owner} AND profile_id=#{profile} ORDER BY version DESC")
    List<ReleaseDO> list(@Param("owner") String owner, @Param("profile") String profile);
    @Select("SELECT " + METADATA + ",NULL AS payload_cipher,create_time FROM profile_release WHERE owner_id=#{owner} AND profile_id=#{profile} AND id=#{id}")
    ReleaseDO metadata(@Param("owner") String owner, @Param("profile") String profile, @Param("id") String id);
    @Select("SELECT COALESCE(MAX(version),0) FROM profile_release WHERE owner_id=#{owner} AND profile_id=#{profile}")
    long latestVersion(@Param("owner") String owner, @Param("profile") String profile);
    @Insert("INSERT INTO profile_release(id,owner_id,profile_id,version,definition_hash,yaml_digest,node_count,reason,payload_cipher,create_time,update_time) VALUES(#{id},#{ownerId},#{profileId},#{version},#{definitionHash},#{yamlDigest},#{nodeCount},#{reason},#{payloadCipher},#{createTime},#{createTime})")
    int insert(ReleaseDO row);
}

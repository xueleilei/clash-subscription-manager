package dev.clashsub.importer;
import java.time.Instant;
import org.apache.ibatis.annotations.*;
@Mapper
public interface PreviewMapper {
    @Insert("INSERT INTO import_preview(id,owner_id,kind,data_revision,payload_cipher,create_time,expires_at) VALUES(#{id},#{ownerId},#{kind},#{dataRevision},#{payloadCipher},#{createTime},#{expiresAt})")
    int insert(PreviewDO row);
    @Select("SELECT id,owner_id,kind,data_revision,payload_cipher,create_time,expires_at,is_consumed AS consumed FROM import_preview WHERE owner_id=#{owner} AND id=#{id} AND kind=#{kind}")
    PreviewDO find(@Param("owner") String owner, @Param("id") String id, @Param("kind") String kind);
    @Update("UPDATE import_preview SET is_consumed=1 WHERE owner_id=#{owner} AND id=#{id} AND is_consumed=0 AND expires_at>#{now}")
    int consume(@Param("owner") String owner, @Param("id") String id, @Param("now") Instant now);
    @Insert("INSERT INTO import_batch(id,owner_id,profile_id,payload_cipher,create_time) VALUES(#{id},#{ownerId},#{profileId},#{payloadCipher},#{createTime})")
    int batch(ImportBatchDO row);
    @Select("SELECT id,owner_id,profile_id,payload_cipher,create_time FROM import_batch WHERE owner_id=#{owner} AND profile_id=#{profile}")
    ImportBatchDO original(@Param("owner") String owner, @Param("profile") String profile);
}

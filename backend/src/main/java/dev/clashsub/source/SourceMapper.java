package dev.clashsub.source;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface SourceMapper {
    String COLUMNS = "id, owner_id, name, config_version, snapshot_version, is_enabled AS enabled, refresh_interval_seconds, input_format, request_cipher, sync_status, last_attempt_time, last_success_time, next_refresh_time, last_error_code, create_time, update_time";
    @Select("SELECT " + COLUMNS + " FROM subscription_source WHERE owner_id=#{owner} ORDER BY create_time, id")
    List<SourceDO> list(String owner);
    @Select("SELECT " + COLUMNS + " FROM subscription_source WHERE owner_id=#{owner} AND id=#{id}")
    SourceDO find(@Param("owner") String owner, @Param("id") String id);
    @Select("SELECT " + COLUMNS + " FROM subscription_source WHERE owner_id=#{owner} AND id=#{id} FOR UPDATE")
    SourceDO lock(@Param("owner") String owner, @Param("id") String id);
    @Insert("INSERT INTO subscription_source(id,owner_id,name,config_version,snapshot_version,is_enabled,refresh_interval_seconds,input_format,request_cipher,sync_status,next_refresh_time) VALUES(#{id},#{ownerId},#{name},#{configVersion},#{snapshotVersion},#{enabled},#{refreshIntervalSeconds},#{inputFormat},#{requestCipher},#{syncStatus},#{nextRefreshTime})")
    int insert(SourceDO row);
    @Update("UPDATE subscription_source SET name=#{row.name}, config_version=config_version+1, is_enabled=#{row.enabled}, refresh_interval_seconds=#{row.refreshIntervalSeconds}, input_format=#{row.inputFormat}, request_cipher=#{row.requestCipher}, next_refresh_time=#{row.nextRefreshTime}, sync_status='PENDING' WHERE owner_id=#{row.ownerId} AND id=#{row.id} AND config_version=#{expected}")
    int updateConfig(@Param("row") SourceDO row, @Param("expected") long expected);
    @Update("UPDATE subscription_source SET snapshot_version=#{next},sync_status='OK',last_success_time=#{now},last_attempt_time=#{now},last_error_code=NULL WHERE owner_id=#{owner} AND id=#{id} AND config_version=#{config} AND snapshot_version=#{previous}")
    int advanceSnapshot(@Param("owner") String owner, @Param("id") String id, @Param("config") long config,
            @Param("previous") long previous, @Param("next") long next, @Param("now") Instant now);
    @Insert("INSERT INTO source_snapshot(id,owner_id,source_id,snapshot_version,payload_cipher,create_time) VALUES(#{id},#{ownerId},#{sourceId},#{snapshotVersion},#{payloadCipher},#{createTime})")
    int insertSnapshot(SourceSnapshotDO row);
    @Select("SELECT id,owner_id,source_id,snapshot_version,payload_cipher,create_time FROM source_snapshot WHERE owner_id=#{owner} AND source_id=#{source} AND snapshot_version=#{version}")
    SourceSnapshotDO snapshot(@Param("owner") String owner, @Param("source") String source, @Param("version") long version);
    @Update("UPDATE pending_empty_decision SET state='EXPIRED' WHERE owner_id=#{owner} AND source_id=#{source} AND state IN ('PENDING','KEPT')")
    int expireDecisions(@Param("owner") String owner, @Param("source") String source);
    @Update("UPDATE subscription_source SET sync_status=#{status},last_attempt_time=#{now},last_error_code=#{error} WHERE owner_id=#{owner} AND id=#{source} AND config_version=#{config} AND snapshot_version=#{snapshot}")
    int attemptStatus(@Param("owner") String owner, @Param("source") String source, @Param("config") long config, @Param("snapshot") long snapshot,
            @Param("status") String status, @Param("error") String error, @Param("now") Instant now);
    @Update("UPDATE subscription_source SET sync_status='OK',last_attempt_time=#{now},last_success_time=#{now},last_error_code=NULL WHERE owner_id=#{owner} AND id=#{source} AND config_version=#{config} AND snapshot_version=#{snapshot}")
    int unchangedSuccess(@Param("owner") String owner, @Param("source") String source, @Param("config") long config, @Param("snapshot") long snapshot, @Param("now") Instant now);
    @Delete("DELETE FROM subscription_source WHERE owner_id=#{owner} AND id=#{source} AND config_version=#{config} AND snapshot_version=#{snapshot}")
    int remove(@Param("owner") String owner, @Param("source") String source, @Param("config") long config, @Param("snapshot") long snapshot);
}

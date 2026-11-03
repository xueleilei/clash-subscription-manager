package dev.clashsub.sync;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RefreshMapper {
    String ATTEMPT = "id,owner_id,source_id,config_version,snapshot_version,status,payload_cipher,create_time,update_time";
    String DECISION = "id,owner_id,source_id,config_version,snapshot_version,candidate_digest,state,payload_cipher,create_time";
    @Insert("INSERT IGNORE INTO refresh_attempt(id,owner_id,source_id,config_version,snapshot_version,status) VALUES(#{id},#{ownerId},#{sourceId},#{configVersion},#{snapshotVersion},#{status})")
    int insertAttempt(AttemptDO row);
    @Select("SELECT " + ATTEMPT + " FROM refresh_attempt WHERE owner_id=#{owner} AND id=#{id}")
    AttemptDO attempt(@Param("owner") String owner, @Param("id") String id);
    @Select("SELECT " + ATTEMPT + " FROM refresh_attempt WHERE owner_id=#{owner} AND source_id=#{source} ORDER BY create_time DESC,id DESC LIMIT 100")
    List<AttemptDO> history(@Param("owner") String owner, @Param("source") String source);
    @Update("UPDATE refresh_attempt SET status=#{status},payload_cipher=#{cipher} WHERE owner_id=#{owner} AND id=#{id} AND status='RUNNING'")
    int finish(@Param("owner") String owner, @Param("id") String id, @Param("status") String status, @Param("cipher") String cipher);
    @Update("UPDATE refresh_attempt SET status='RUNNING',config_version=#{config},snapshot_version=#{snapshot} WHERE owner_id=#{owner} AND id=#{id} AND status IN ('QUEUED','RUNNING')")
    int running(@Param("owner") String owner, @Param("id") String id, @Param("config") long config, @Param("snapshot") long snapshot);
    @Select("SELECT status FROM refresh_attempt WHERE owner_id=#{owner} AND id=#{id} AND status='RUNNING' AND (lease_until IS NULL OR lease_until>#{now}) FOR UPDATE")
    String lockRunning(@Param("owner") String owner, @Param("id") String id, @Param("now") java.time.Instant now);
    @Select("SELECT lease_until FROM refresh_attempt WHERE owner_id=#{owner} AND id=#{id}")
    java.time.Instant deadline(@Param("owner") String owner, @Param("id") String id);
    @Select("SELECT " + ATTEMPT + " FROM refresh_attempt WHERE owner_id=#{owner} AND source_id=#{source} AND status IN ('QUEUED','RUNNING') ORDER BY id")
    List<AttemptDO> activeForSource(@Param("owner") String owner, @Param("source") String source);
    @Update("UPDATE refresh_attempt SET status='CANCELLED',payload_cipher=#{cipher} WHERE owner_id=#{owner} AND id=#{id} AND status IN ('QUEUED','RUNNING')")
    int cancel(@Param("owner") String owner, @Param("id") String id, @Param("cipher") String cipher);
    @Select("SELECT " + DECISION + " FROM pending_empty_decision WHERE owner_id=#{owner} AND id=#{id}")
    EmptyDecisionDO decision(@Param("owner") String owner, @Param("id") String id);
    @Select("SELECT " + DECISION + " FROM pending_empty_decision WHERE owner_id=#{owner} AND source_id=#{source} AND config_version=#{config} AND snapshot_version=#{snapshot} AND candidate_digest=#{digest} AND state IN ('PENDING','KEPT') ORDER BY create_time DESC LIMIT 1")
    EmptyDecisionDO matching(@Param("owner") String owner, @Param("source") String source, @Param("config") long config, @Param("snapshot") long snapshot, @Param("digest") String digest);
    @Select("SELECT " + DECISION + " FROM pending_empty_decision WHERE owner_id=#{owner} AND state='PENDING' ORDER BY create_time,id")
    List<EmptyDecisionDO> pending(String owner);
    @Insert("INSERT INTO pending_empty_decision(id,owner_id,source_id,config_version,snapshot_version,candidate_digest,state,payload_cipher) VALUES(#{id},#{ownerId},#{sourceId},#{configVersion},#{snapshotVersion},#{candidateDigest},#{state},#{payloadCipher})")
    int insertDecision(EmptyDecisionDO row);
    @Update("UPDATE pending_empty_decision SET state=#{state} WHERE owner_id=#{owner} AND id=#{id} AND state='PENDING'")
    int decide(@Param("owner") String owner, @Param("id") String id, @Param("state") String state);
}

package dev.clashsub.sync;
import dev.clashsub.source.SourceDO;
import dev.clashsub.source.SourceMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;
@Mapper
public interface RefreshJobMapper {
    String JOB = "id,owner_id,source_id,status,trigger_kind,lease_until";
    @Select("SELECT " + SourceMapper.COLUMNS + " FROM subscription_source WHERE owner_id=#{owner} AND id=#{id} FOR UPDATE")
    SourceDO lockSource(@Param("owner") String owner, @Param("id") String id);
    @Select("SELECT active_attempt_id FROM subscription_source WHERE owner_id=#{owner} AND id=#{source}")
    String active(@Param("owner") String owner, @Param("source") String source);
    @Select("SELECT " + JOB + " FROM refresh_attempt WHERE owner_id=#{owner} AND id=#{id}")
    JobDO job(@Param("owner") String owner, @Param("id") String id);
    @Insert("INSERT INTO refresh_attempt(id,owner_id,source_id,config_version,snapshot_version,status,trigger_kind,create_time,update_time) VALUES(#{id},#{owner},#{source},#{config},#{snapshot},'QUEUED',#{trigger},#{now},#{now})")
    int insert(@Param("owner") String owner, @Param("source") String source, @Param("id") String id, @Param("config") long config, @Param("snapshot") long snapshot, @Param("trigger") String trigger, @Param("now") Instant now);
    @Update("UPDATE subscription_source SET active_attempt_id=#{attempt},next_refresh_time=#{next},sync_status='QUEUED' WHERE owner_id=#{owner} AND id=#{source}")
    int activate(@Param("owner") String owner, @Param("source") String source, @Param("attempt") String attempt, @Param("next") Instant next);
    @Update("UPDATE refresh_attempt SET status='RUNNING',lease_until=#{deadline},update_time=#{now} WHERE owner_id=#{owner} AND id=#{id} AND status='QUEUED'")
    int claim(@Param("owner") String owner, @Param("id") String id, @Param("deadline") Instant deadline, @Param("now") Instant now);
    @Update("UPDATE subscription_source SET sync_status='RUNNING',last_attempt_time=#{now} WHERE owner_id=#{owner} AND id=#{source} AND active_attempt_id=#{attempt}")
    int running(@Param("owner") String owner, @Param("source") String source, @Param("attempt") String attempt, @Param("now") Instant now);
    @Update("UPDATE subscription_source SET active_attempt_id=NULL WHERE owner_id=#{owner} AND id=#{source} AND active_attempt_id=#{attempt}")
    int release(@Param("owner") String owner, @Param("source") String source, @Param("attempt") String attempt);
    @Select("SELECT " + JOB + " FROM refresh_attempt WHERE status='QUEUED' ORDER BY create_time,id LIMIT 68")
    List<JobDO> queued();
    @Select("SELECT " + JOB + " FROM refresh_attempt WHERE status='RUNNING' AND lease_until IS NOT NULL AND lease_until<=#{now} ORDER BY lease_until,id LIMIT 100")
    List<JobDO> expired(Instant now);
    @Select("SELECT " + SourceMapper.COLUMNS + " FROM subscription_source WHERE is_enabled=1 AND next_refresh_time<=#{now} ORDER BY next_refresh_time,id LIMIT 100")
    List<SourceDO> due(Instant now);
}

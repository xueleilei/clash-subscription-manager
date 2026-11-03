package dev.clashsub.sync;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;
@Mapper
public interface RetentionMapper {
    record ProfilePointer(String id, String currentReleaseId) { }
    record SnapshotRow(String id, String sourceId, long snapshotVersion) { }
    @Select("SELECT id FROM app_user ORDER BY id") List<String> owners();
    @Select("SELECT id,current_release_id FROM configuration_profile WHERE owner_id=#{owner} ORDER BY id") List<ProfilePointer> profiles(String owner);
    @Delete("DELETE FROM profile_release WHERE owner_id=#{owner} AND id=#{id} AND NOT EXISTS (SELECT 1 FROM configuration_profile p WHERE p.current_release_id=#{id})")
    int deleteRelease(@Param("owner") String owner, @Param("id") String id);
    @Select("SELECT id,source_id,snapshot_version FROM source_snapshot WHERE owner_id=#{owner} AND create_time<#{cutoff} ORDER BY id")
    List<SnapshotRow> oldSnapshots(@Param("owner") String owner, @Param("cutoff") Instant cutoff);
    @Delete("DELETE FROM source_snapshot WHERE owner_id=#{owner} AND id=#{id}") int deleteSnapshot(@Param("owner") String owner, @Param("id") String id);
    @Delete("DELETE a FROM refresh_attempt a WHERE a.owner_id=#{owner} AND a.create_time<#{cutoff} AND a.status NOT IN ('QUEUED','RUNNING') AND NOT EXISTS (SELECT 1 FROM subscription_source s WHERE s.active_attempt_id=a.id) AND NOT EXISTS (SELECT 1 FROM pending_empty_decision d WHERE d.owner_id=a.owner_id AND d.source_id=a.source_id AND d.config_version=a.config_version AND d.snapshot_version=a.snapshot_version AND d.state='PENDING')")
    int deleteAttempts(@Param("owner") String owner, @Param("cutoff") Instant cutoff);
    @Delete("DELETE FROM pending_empty_decision WHERE owner_id=#{owner} AND state IN ('EXPIRED','CLEARED') AND update_time<#{cutoff}")
    int deleteResolvedDecisions(@Param("owner") String owner, @Param("cutoff") Instant cutoff);
}

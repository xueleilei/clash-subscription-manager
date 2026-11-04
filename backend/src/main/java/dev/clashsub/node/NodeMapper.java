package dev.clashsub.node;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NodeMapper {
    record SourceCount(String sourceId, long nodeCount) { }
    record CatalogRow(String id, String connectionCipher, String localGroupName) { }
    record PageRow(String id, String sourceId, String originalName, String protocol, String region, long snapshotVersion,
            String localGroupId, String localGroupName) { }
    @Select("SELECT source_id,COUNT(*) AS node_count FROM node WHERE owner_id=#{owner} AND is_active=1 AND source_id IS NOT NULL GROUP BY source_id")
    List<SourceCount> countsBySource(String owner);
    String COLUMNS = "id,owner_id,source_id,name_hash,original_name,protocol,region,region_override,snapshot_version,connection_cipher";
    String FILTER = " WHERE n.owner_id=#{owner} AND n.is_active=1"
            + "<if test='source != null'> AND n.source_id=#{source}</if><if test='localOnly'> AND n.source_id IS NULL</if>"
            + "<if test='groupId != null'> AND n.local_group_id=#{groupId}</if><if test='ungrouped'> AND n.local_group_id IS NULL</if>"
            + "<if test='protocol != null'> AND n.protocol=#{protocol}</if><if test='region != null'> AND n.region=#{region}</if>"
            + "<if test='name != null'> AND LOWER(n.original_name) LIKE #{name} ESCAPE '='</if>";
    @Select("SELECT " + COLUMNS + " FROM node WHERE owner_id=#{owner} AND id=#{id} AND is_active=1")
    NodeDO find(@Param("owner") String owner, @Param("id") String id);
    @Select("<script>SELECT id,owner_id,source_id,name_hash,original_name,protocol,region,region_override,snapshot_version,NULL AS connection_cipher FROM node WHERE owner_id=#{owner} AND source_id IS NULL AND is_active=1 AND id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY id</script>")
    List<NodeDO> findLocalBatch(@Param("owner") String owner, @Param("ids") List<String> ids);
    @Select("""
            SELECT n.id, n.connection_cipher, g.name AS local_group_name
            FROM node n LEFT JOIN local_node_group g
                ON g.id=n.local_group_id AND g.owner_id=n.owner_id AND n.source_id IS NULL
            WHERE n.owner_id=#{owner} AND n.is_active=1 ORDER BY n.id
            """)
    List<CatalogRow> catalog(String owner);
    @Select("SELECT " + COLUMNS + " FROM node WHERE owner_id=#{owner} AND source_id=#{source} ORDER BY id")
    List<NodeDO> source(@Param("owner") String owner, @Param("source") String source);
    @Select("<script>SELECT n.id,n.source_id,n.original_name,n.protocol,n.region,n.snapshot_version,g.id AS local_group_id,g.name AS local_group_name"
            + " FROM node n LEFT JOIN local_node_group g ON n.source_id IS NULL AND g.id=n.local_group_id AND g.owner_id=n.owner_id"
            + FILTER + " ORDER BY n.create_time,n.id LIMIT #{limit} OFFSET #{offset}</script>")
    List<PageRow> page(@Param("owner") String owner, @Param("source") String source, @Param("protocol") String protocol,
            @Param("region") String region, @Param("name") String name, @Param("limit") int limit, @Param("offset") long offset,
            @Param("localOnly") boolean localOnly, @Param("groupId") String groupId, @Param("ungrouped") boolean ungrouped);
    @Select("<script>SELECT COUNT(*) FROM node n" + FILTER + "</script>")
    long count(@Param("owner") String owner, @Param("source") String source, @Param("protocol") String protocol, @Param("region") String region,
            @Param("name") String name, @Param("localOnly") boolean localOnly, @Param("groupId") String groupId, @Param("ungrouped") boolean ungrouped);
    @Insert("INSERT INTO node(id,owner_id,source_id,name_hash,original_name,protocol,region,region_override,snapshot_version,connection_cipher,local_group_id)"
            + " VALUES(#{n.id},#{n.ownerId},#{n.sourceId},#{n.nameHash},#{n.originalName},#{n.protocol},#{n.region},#{n.regionOverride},#{n.snapshotVersion},#{n.connectionCipher},#{groupId})")
    int insert(@Param("n") NodeDO row, @Param("groupId") String groupId);
    @Update("UPDATE node SET is_active=1,original_name=#{originalName},name_hash=#{nameHash},protocol=#{protocol},region=#{region},snapshot_version=#{snapshotVersion},connection_cipher=#{connectionCipher} WHERE id=#{id} AND owner_id=#{ownerId} AND source_id=#{sourceId}")
    int update(NodeDO row);
    @Update("UPDATE node SET snapshot_version=#{version} WHERE owner_id=#{owner} AND source_id=#{source} AND is_active=1")
    int markSnapshot(@Param("owner") String owner, @Param("source") String source, @Param("version") long version);
    @Update("UPDATE node SET is_active=0 WHERE owner_id=#{owner} AND source_id=#{source} AND id=#{id}")
    int delete(@Param("owner") String owner, @Param("source") String source, @Param("id") String id);
    @Select("SELECT " + COLUMNS + " FROM node WHERE owner_id=#{owner} AND is_active=0 AND update_time<#{cutoff}")
    List<NodeDO> oldTombstones(@Param("owner") String owner, @Param("cutoff") java.time.Instant cutoff);
    @Update("UPDATE node SET connection_cipher=#{cipher} WHERE owner_id=#{owner} AND id=#{id} AND is_active=0")
    int wipeTombstone(@Param("owner") String owner, @Param("id") String id, @Param("cipher") String cipher);
    @Update("UPDATE node SET source_id=NULL,snapshot_version=0,connection_cipher=#{cipher} WHERE owner_id=#{owner} AND id=#{id} AND source_id=#{source} AND is_active=1")
    int detach(@Param("owner") String owner, @Param("source") String source, @Param("id") String id, @Param("cipher") String cipher);
    @Update("UPDATE node SET original_name=#{row.originalName},name_hash=#{row.nameHash},protocol=#{row.protocol},region=#{row.region},region_override=#{row.regionOverride},snapshot_version=snapshot_version+1,connection_cipher=#{row.connectionCipher} WHERE owner_id=#{row.ownerId} AND id=#{row.id} AND source_id IS NULL AND is_active=1 AND snapshot_version=#{expected}")
    int updateLocal(@Param("row") NodeDO row, @Param("expected") long expected);
    @Update("""
            UPDATE node SET region=#{region},region_override=#{override},snapshot_version=snapshot_version+1
            WHERE owner_id=#{owner} AND id=#{id} AND source_id IS NULL AND is_active=1 AND snapshot_version=#{expected}
            """)
    int updateLocalRegion(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected,
            @Param("region") String region, @Param("override") String override);
    @Update("UPDATE node SET is_active=0,local_group_id=NULL,snapshot_version=snapshot_version+1 WHERE owner_id=#{owner} AND id=#{id} AND source_id IS NULL AND is_active=1 AND snapshot_version=#{expected}")
    int deleteLocal(@Param("owner") String owner, @Param("id") String id, @Param("expected") long expected);
}

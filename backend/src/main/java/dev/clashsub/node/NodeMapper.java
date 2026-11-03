package dev.clashsub.node;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NodeMapper {
    record SourceCount(String sourceId, long nodeCount) { }
    @Select("SELECT source_id,COUNT(*) AS node_count FROM node WHERE owner_id=#{owner} AND is_active=1 AND source_id IS NOT NULL GROUP BY source_id")
    List<SourceCount> countsBySource(String owner);
    String COLUMNS = "id,owner_id,source_id,name_hash,original_name,protocol,region,snapshot_version,connection_cipher";
    String FILTER = " WHERE owner_id=#{owner} AND is_active=1<if test='source != null'> AND source_id=#{source}</if><if test='protocol != null'> AND protocol=#{protocol}</if><if test='region != null'> AND region=#{region}</if><if test='name != null'> AND LOWER(original_name) LIKE #{name} ESCAPE '='</if>";
    @Select("SELECT " + COLUMNS + " FROM node WHERE owner_id=#{owner} AND id=#{id} AND is_active=1")
    NodeDO find(@Param("owner") String owner, @Param("id") String id);
    @Select("SELECT " + COLUMNS + " FROM node WHERE owner_id=#{owner} AND is_active=1 ORDER BY id")
    List<NodeDO> all(String owner);
    @Select("SELECT " + COLUMNS + " FROM node WHERE owner_id=#{owner} AND source_id=#{source} ORDER BY id")
    List<NodeDO> source(@Param("owner") String owner, @Param("source") String source);
    @Select("<script>SELECT id,owner_id,source_id,name_hash,original_name,protocol,region,snapshot_version,NULL AS connection_cipher FROM node" + FILTER + " ORDER BY create_time,id LIMIT #{limit} OFFSET #{offset}</script>")
    List<NodeDO> page(@Param("owner") String owner, @Param("source") String source, @Param("protocol") String protocol,
            @Param("region") String region, @Param("name") String name, @Param("limit") int limit, @Param("offset") long offset);
    @Select("<script>SELECT COUNT(*) FROM node" + FILTER + "</script>")
    long count(@Param("owner") String owner, @Param("source") String source, @Param("protocol") String protocol, @Param("region") String region, @Param("name") String name);
    @Insert("INSERT INTO node(id,owner_id,source_id,name_hash,original_name,protocol,region,snapshot_version,connection_cipher) VALUES(#{id},#{ownerId},#{sourceId},#{nameHash},#{originalName},#{protocol},#{region},#{snapshotVersion},#{connectionCipher})")
    int insert(NodeDO row);
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
}

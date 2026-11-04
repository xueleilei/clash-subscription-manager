package dev.clashsub.node;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface LocalNodeGroupMapper {
    record Folder(String id, String name, String version, long nodeCount) { }
    @Select("""
            SELECT g.id,g.name,CAST(g.version AS CHAR) AS version,COUNT(n.id) AS node_count
            FROM local_node_group g LEFT JOIN node n ON n.owner_id=g.owner_id AND n.local_group_id=g.id
                AND n.source_id IS NULL AND n.is_active=1
            WHERE g.owner_id=#{owner} GROUP BY g.id,g.name,g.version ORDER BY g.name,g.id
            """)
    List<Folder> folders(String owner);
    @Select("SELECT COUNT(*) FROM node WHERE owner_id=#{owner} AND source_id IS NULL AND is_active=1 AND local_group_id IS NULL")
    long ungroupedCount(String owner);
    @Select("SELECT version FROM local_node_group WHERE owner_id=#{owner} AND id=#{id}")
    Long version(@Param("owner") String owner, @Param("id") String id);
    @Insert("INSERT INTO local_node_group(id,owner_id,name) VALUES(#{id},#{owner},#{name})")
    int insert(@Param("owner") String owner, @Param("id") String id, @Param("name") String name);
    @Update("UPDATE local_node_group SET name=#{name},version=version+1 WHERE owner_id=#{owner} AND id=#{id}")
    int rename(@Param("owner") String owner, @Param("id") String id, @Param("name") String name);
    @Delete("DELETE FROM local_node_group WHERE owner_id=#{owner} AND id=#{id}")
    int delete(@Param("owner") String owner, @Param("id") String id);
    @Update("UPDATE node SET local_group_id=NULL,update_time=update_time WHERE owner_id=#{owner} AND source_id IS NULL AND local_group_id=#{id}")
    int releaseMembers(@Param("owner") String owner, @Param("id") String id);
    @Update("<script>UPDATE node SET local_group_id=#{groupId},update_time=update_time WHERE owner_id=#{owner} AND source_id IS NULL AND is_active=1"
            + " AND NOT (local_group_id &lt;=> #{groupId}) AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int move(@Param("owner") String owner, @Param("groupId") String groupId, @Param("ids") List<String> ids);
}

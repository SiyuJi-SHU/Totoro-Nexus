package org.example.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.example.platform.PlatformModels.*;

@Service
public class DocumentCatalog {
    private final JdbcTemplate db;
    private final PlatformCatalog catalog;
    private static final String VERSION_QUERY="SELECT d.id,d.dataset_id,d.path,v.id,v.content_hash,v.source_path,v.content_length,v.chunk_count,v.index_collection,v.status FROM documents d JOIN document_versions v ON v.document_id=d.id ";
    private static final org.springframework.jdbc.core.RowMapper<DocumentVersion> VERSION_MAPPER=(r,n) -> new DocumentVersion(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getInt(7),r.getInt(8),r.getString(9),r.getString(10));
    public DocumentCatalog(JdbcTemplate db,PlatformCatalog catalog) { this.db=db;this.catalog=catalog; }
    public List<DocumentInfo> list(String datasetId) {
        if(datasetId!=null) catalog.dataset(datasetId);
        String sql="SELECT d.id,d.dataset_id,d.path,d.active_version,d.status,d.error_message,COALESCE(v.content_length,0),COALESCE(v.chunk_count,0),d.updated_at FROM documents d LEFT JOIN document_versions v ON v.id=d.active_version WHERE d.status <> 'DELETED'";
        Object[] args=datasetId==null?new Object[0]:new Object[]{datasetId};
        if(datasetId!=null)sql+=" AND d.dataset_id=?";
        return db.query(sql+" ORDER BY d.path,d.id",(r,n)->new DocumentInfo(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getInt(7),r.getInt(8),r.getTimestamp(9).toInstant().toString()),args);
    }
    public List<DocumentVersion> active(Set<String> datasets) {
        if(datasets.isEmpty())return List.of();
        String placeholders=String.join(",",Collections.nCopies(datasets.size(),"?"));
        return db.query(VERSION_QUERY+"WHERE d.active_version=v.id AND d.status <> 'DELETED' AND d.dataset_id IN ("+placeholders+") ORDER BY d.path,d.id",VERSION_MAPPER,datasets.toArray());
    }
    public DocumentVersion version(String id,String version,Set<String> scope) {
        var found=db.query(VERSION_QUERY+"WHERE d.id=? AND v.id=?",VERSION_MAPPER,id,version);
        if(found.isEmpty()||!scope.contains(found.get(0).datasetId()))throw PlatformCatalog.missing("范围内的文档版本");
        return found.get(0);
    }
    public Optional<DocumentVersion> currentByPath(String dataset,String path) {
        return db.query(VERSION_QUERY+"WHERE d.dataset_id=? AND d.path=? AND d.active_version=v.id AND d.status <> 'DELETED'",VERSION_MAPPER,dataset,path).stream().findFirst();
    }
    public void markHealthy(DocumentVersion version) {
        db.update("UPDATE documents SET status='INDEXED',error_message=NULL WHERE id=? AND active_version=? AND status <> 'DELETED'",version.documentId(),version.version());
    }
    public List<DocumentVersion> versions(String documentId) {
        return db.query(VERSION_QUERY+"WHERE d.id=? ORDER BY v.created_at DESC",VERSION_MAPPER,documentId);
    }
    @Transactional
    public DocumentVersion prepare(String datasetId,String path,String hash,int length,String collection) {
        catalog.dataset(datasetId); path=normalizePath(path);
        var ids=db.queryForList("SELECT id FROM documents WHERE dataset_id=? AND path=? FOR UPDATE",String.class,datasetId,path);
        String id=ids.isEmpty()?UUID.randomUUID().toString():ids.get(0), version=UUID.randomUUID().toString();
        if(ids.isEmpty())db.update("INSERT INTO documents(id,dataset_id,path,status) VALUES(?,?,?,'INDEXING')",id,datasetId,path);
        else db.update("UPDATE documents SET status='INDEXING',error_message=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        String sourcePath=id+"/"+version+".txt";
        db.update("INSERT INTO document_versions(id,document_id,content_hash,source_path,content_length,index_collection,status) VALUES(?,?,?,?,?,?,'PREPARING')",version,id,hash,sourcePath,length,collection);
        return new DocumentVersion(id,datasetId,path,version,hash,sourcePath,length,0,collection,"PREPARING");
    }
    @Transactional
    public void activate(DocumentVersion v,int chunks) {
        if(db.update("UPDATE document_versions SET chunk_count=?,status='READY',error_message=NULL WHERE id=? AND status='PREPARING'",chunks,v.version())!=1)throw new IllegalStateException("版本状态已改变");
        db.update("UPDATE documents SET active_version=?,status='INDEXED',error_message=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",v.version(),v.documentId());
    }
    @Transactional
    public void fail(DocumentVersion v,String reason) {
        db.update("UPDATE document_versions SET status='FAILED',error_message=? WHERE id=? AND status='PREPARING'",reason,v.version());
        db.update("UPDATE documents SET status='INDEX_FAILED',error_message=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",reason,v.documentId());
    }
    @Transactional
    public void recoverInterrupted() {
        db.update("UPDATE documents SET status='INDEX_FAILED',error_message='上次索引过程被中断，旧版本仍保留，可重新上传修复' WHERE status='INDEXING'");
        db.update("UPDATE document_versions SET status='FAILED',error_message='索引准备过程被中断' WHERE status='PREPARING'");
    }
    public void remove(String id) {
        if(db.update("UPDATE documents SET status='DELETED',updated_at=CURRENT_TIMESTAMP WHERE id=?",id)==0)throw PlatformCatalog.missing("文档");
    }
    public static String normalizePath(String raw) {
        String path=PlatformCatalog.required(raw,800,"文档路径").replace('\\','/');
        if(path.startsWith("/")||path.contains(":")||path.contains("\u0000")||path.chars().anyMatch(Character::isISOControl))throw PlatformCatalog.bad("文档路径无效");
        String[] parts=path.split("/",-1);
        if(parts.length>12||Arrays.stream(parts).anyMatch(p->p.isBlank()||p.equals(".")||p.equals("..")||p.startsWith(".")))throw PlatformCatalog.bad("文档路径无效");
        if(!path.toLowerCase(Locale.ROOT).matches(".*\\.(md|txt)$"))throw PlatformCatalog.bad("当前支持 Markdown/TXT 文档");
        return path;
    }
}

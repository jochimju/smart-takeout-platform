import java.sql.*;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** One-time copy of catalog-owned tables. Stop catalog writes during cutover. */
public class MigrateCatalogData {
    private static final List<String> TABLES=List.of("category","dish","dish_flavor","setmeal","setmeal_dish");
    public static void main(String[] args) throws Exception {
        String source=id(env("SKY_SOURCE_DB","sky_take_out")), target=id(env("SKY_CATALOG_DB","sky_take_out_catalog"));
        if(source.equals(target)) throw new IllegalArgumentException("source and target must differ");
        String url="jdbc:mysql://"+env("SKY_DB_HOST","localhost")+":"+env("SKY_DB_PORT","3306")+"/?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true";
        try(Connection db=DriverManager.getConnection(url,env("SKY_DB_USER","root"),env("SKY_DB_PASSWORD",""))){
            try(Statement s=db.createStatement()){
                s.execute("CREATE DATABASE IF NOT EXISTS `"+target+"` CHARACTER SET utf8mb4");
                for(String table:TABLES) s.execute("CREATE TABLE IF NOT EXISTS `"+target+"`.`"+table+"` LIKE `"+source+"`.`"+table+"`");
            }
            for(String table:TABLES){
                if(!columns(db,source,table).equals(columns(db,target,table))) throw new IllegalStateException("schema differs: "+table);
                long targetCount=count(db,target,table);
                if(targetCount!=0 && targetCount!=count(db,source,table)) throw new IllegalStateException("target is partially populated: "+table);
            }
            db.setAutoCommit(false);
            try{
                for(String table:TABLES){
                    if(count(db,target,table)==0){
                        List<String> cols=columns(db,source,table);
                        String names=String.join(",",cols.stream().map(c->"`"+c+"`").toList());
                        try(Statement s=db.createStatement()){s.executeUpdate("INSERT INTO `"+target+"`.`"+table+"` ("+names+") SELECT "+names+" FROM `"+source+"`.`"+table+"`");}
                    }
                    long a=count(db,source,table),b=count(db,target,table);
                    if(a!=b) throw new IllegalStateException("count mismatch: "+table);
                    System.out.println(table+": "+b+" rows verified");
                }
                db.commit();
            }catch(Exception ex){db.rollback();throw ex;}
            String output=System.getenv("SKY_SCHEMA_OUTPUT");
            if(output!=null && !output.isBlank()) exportSchema(db,target,Path.of(output));
        }
    }
    private static void exportSchema(Connection db,String schema,Path output)throws Exception{
        StringBuilder sql=new StringBuilder("SET FOREIGN_KEY_CHECKS=0;\n");
        for(String table:TABLES){
            try(Statement s=db.createStatement();ResultSet r=s.executeQuery("show create table `"+schema+"`.`"+table+"`")){
                r.next();String ddl=r.getString(2).replaceFirst("CREATE TABLE `"+table+"`","CREATE TABLE IF NOT EXISTS `"+table+"`");
                ddl=ddl.replaceAll(" AUTO_INCREMENT=\\d+","");
                sql.append(ddl).append(";\n\n");
            }
        }
        sql.append("SET FOREIGN_KEY_CHECKS=1;\n");
        Files.writeString(output,sql.toString(),StandardCharsets.UTF_8);
    }
    private static List<String> columns(Connection db,String schema,String table)throws SQLException{
        List<String> values=new ArrayList<>();
        try(PreparedStatement p=db.prepareStatement("select column_name from information_schema.columns where table_schema=? and table_name=? order by ordinal_position")){
            p.setString(1,schema);p.setString(2,table);try(ResultSet r=p.executeQuery()){while(r.next())values.add(r.getString(1));}
        }
        if(values.isEmpty())throw new IllegalStateException("missing table: "+schema+'.'+table);return values;
    }
    private static long count(Connection db,String schema,String table)throws SQLException{
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("select count(*) from `"+schema+"`.`"+table+"`")){r.next();return r.getLong(1);}
    }
    private static String id(String v){if(!v.matches("[A-Za-z0-9_]+"))throw new IllegalArgumentException("invalid database name");return v;}
    private static String env(String key,String fallback){String v=System.getenv(key);return v==null?fallback:v;}
}

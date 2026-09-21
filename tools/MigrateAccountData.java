import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** One-time copy of account-owned tables. Requires a stopped writer during cutover. */
public class MigrateAccountData {
    private static final List<String> TABLES = List.of(
            "user", "employee", "address_book", "role", "permission",
            "employee_role", "role_permission");

    public static void main(String[] args) throws Exception {
        String source = identifier(env("SKY_SOURCE_DB", "sky_take_out"));
        String target = identifier(env("SKY_ACCOUNT_DB", "sky_take_out_account"));
        if (source.equals(target)) throw new IllegalArgumentException("Source and target must differ");
        String host = env("SKY_DB_HOST", "localhost");
        String port = env("SKY_DB_PORT", "3306");
        String url = "jdbc:mysql://" + host + ":" + port
                + "/?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8"
                + "&useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection db = DriverManager.getConnection(url,
                env("SKY_DB_USER", "root"), env("SKY_DB_PASSWORD", ""))) {
            try (Statement ddl = db.createStatement()) {
                ddl.execute("CREATE DATABASE IF NOT EXISTS `" + target + "` CHARACTER SET utf8mb4");
                for (String table : TABLES) {
                    ddl.execute("CREATE TABLE IF NOT EXISTS `" + target + "`.`" + table
                            + "` LIKE `" + source + "`.`" + table + "`");
                }
            }
            for (String table : TABLES) {
                if (!columns(db, source, table).equals(columns(db, target, table))) {
                    throw new IllegalStateException("Schema differs for " + table);
                }
                if (count(db, target, table) != 0) {
                    throw new IllegalStateException("Target table is not empty: " + table);
                }
            }
            db.setAutoCommit(false);
            try {
                for (String table : TABLES) {
                    String qualified = "`" + target + "`.`" + table + "`";
                    List<String> columns = columns(db, source, table);
                    String names = String.join(",", columns.stream().map(c -> "`" + c + "`").toList());
                    try (Statement copy = db.createStatement()) {
                        copy.executeUpdate("INSERT INTO " + qualified + " (" + names + ") SELECT "
                                + names + " FROM `" + source + "`.`" + table + "`");
                    }
                    long sourceCount = count(db, source, table);
                    long targetCount = count(db, target, table);
                    if (sourceCount != targetCount) {
                        throw new IllegalStateException("Count mismatch for " + table);
                    }
                    System.out.println(table + ": " + targetCount + " rows verified");
                }
                db.commit();
                System.out.println("Account copy complete. Stop writes to source account tables before cutover.");
            } catch (Exception failure) {
                db.rollback();
                throw failure;
            }
        }
    }

    private static List<String> columns(Connection db, String schema, String table) throws SQLException {
        List<String> result = new ArrayList<>();
        String query = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement statement = db.prepareStatement(query)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
        }
        if (result.isEmpty()) throw new IllegalStateException("Missing table: " + schema + "." + table);
        return result;
    }

    private static long count(Connection db, String schema, String table) throws SQLException {
        try (Statement statement = db.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM `" + schema + "`.`" + table + "`")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static String identifier(String name) {
        if (!name.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Invalid database name");
        return name;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null ? fallback : value;
    }
}

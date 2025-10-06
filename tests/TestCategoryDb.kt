import java.nio.file.Path
import java.sql.DriverManager

fun main() {
    // Use the same path as DesktopPaths.configDir() for Linux
    val dbPath = Path.of(System.getProperty("user.home"), ".config", "wingmate", "wingmate.db")
    Class.forName("org.sqlite.JDBC")
    val conn = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")

    println("=== CATEGORIES TABLE SCHEMA ===")
    conn.createStatement().use { st ->
        val rs = st.executeQuery("PRAGMA table_info('categories')")
        while (rs.next()) {
            println("${rs.getString("name")} (${rs.getString("type")})")
        }
    }

    println("\n=== CATEGORIES BEFORE INSERT ===")
    conn.prepareStatement("SELECT id, name, selectedLanguage, selected_language, created_at, ordering FROM categories").use { ps ->
        val rs = ps.executeQuery()
        while (rs.next()) {
            println("id=${rs.getString(1)}, name=${rs.getString(2)}, selectedLanguage=${rs.getString(3)}, selected_language=${rs.getString(4)}, created_at=${rs.getLong(5)}, ordering=${rs.getInt(6)}")
        }
    }

    val id = "test_${System.currentTimeMillis()}"
    conn.prepareStatement("INSERT INTO categories(id, name, selectedLanguage, created_at, ordering) VALUES (?, ?, ?, ?, ?)").use { ps ->
        ps.setString(1, id)
        ps.setString(2, "TestCat")
        ps.setString(3, "en-US")
        ps.setLong(4, System.currentTimeMillis())
        ps.setInt(5, 999)
        ps.executeUpdate()
    }

    println("\n=== CATEGORIES AFTER INSERT ===")
    conn.prepareStatement("SELECT id, name, selectedLanguage, selected_language, created_at, ordering FROM categories").use { ps ->
        val rs = ps.executeQuery()
        while (rs.next()) {
            println("id=${rs.getString(1)}, name=${rs.getString(2)}, selectedLanguage=${rs.getString(3)}, selected_language=${rs.getString(4)}, created_at=${rs.getLong(5)}, ordering=${rs.getInt(6)}")
        }
    }

    conn.close()
}
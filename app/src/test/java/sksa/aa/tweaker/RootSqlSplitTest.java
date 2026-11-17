package sksa.aa.tweaker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class RootSqlSplitTest {

    @Test
    public void splitsPlainStatements() {
        List<String> s = RootSql.split("DROP TRIGGER IF EXISTS a; DELETE FROM FlagOverrides;\n");
        assertEquals(2, s.size());
        assertEquals("DROP TRIGGER IF EXISTS a", s.get(0));
        assertEquals("DELETE FROM FlagOverrides", s.get(1));
    }

    @Test
    public void keepsTriggerBodyTogether() {
        String sql = "CREATE TRIGGER t AFTER DELETE\nOn FlagOverrides\nBEGIN\n"
                + "INSERT OR REPLACE INTO FlagOverrides (name) VALUES (\"a\");\n"
                + "INSERT OR REPLACE INTO FlagOverrides (name) VALUES (\"b\");\nEND;\n"
                + "SELECT 1;";
        List<String> s = RootSql.split(sql);
        assertEquals(2, s.size());
        assertTrue(s.get(0).startsWith("CREATE TRIGGER t"));
        assertTrue(s.get(0).endsWith("END"));
        assertTrue(s.get(0).contains("VALUES ('a');"));
        assertEquals("SELECT 1", s.get(1));
    }

    @Test
    public void rewritesDoubleQuotedStringsAndKeepsSemicolonsInside() {
        List<String> s = RootSql.split("INSERT INTO t (a,b) VALUES (\"x;y\", \"it's\"); SELECT 'p;q';");
        assertEquals(2, s.size());
        assertEquals("INSERT INTO t (a,b) VALUES ('x;y', 'it''s')", s.get(0));
        assertEquals("SELECT 'p;q'", s.get(1));
    }

    @Test
    public void classifiesQueries() {
        assertTrue(RootSql.isQuery(" select * from t"));
        assertTrue(RootSql.isQuery("PRAGMA journal_mode"));
        assertFalse(RootSql.isQuery("PRAGMA journal_mode=wal"));
        assertFalse(RootSql.isQuery("DELETE FROM t"));
    }
}

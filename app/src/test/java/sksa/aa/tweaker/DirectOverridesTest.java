package sksa.aa.tweaker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class DirectOverridesTest {

    @Test
    public void booleanInsertUsesTypeTwoAndOneZero() {
        String sql = DirectOverrides.insertSql("com.google.android.projection.gearhead",
                "ApolloFeature__expressive_enabled", "boolean", "false");
        assertTrue(sql.contains("'ApolloFeature__expressive_enabled', 0, 2, 0);"));
        assertTrue(sql.contains("WHERE name = 'com.google.android.projection.gearhead'"));
    }

    @Test
    public void numericAndStringTypes() {
        assertTrue(DirectOverrides.insertSql("p", "A__b", "long", "900").contains("'A__b', 900, 1, 0);"));
        assertTrue(DirectOverrides.insertSql("p", "A__b", "double", "1.6").contains("'A__b', 1.6, 3, 0);"));
        assertTrue(DirectOverrides.insertSql("p", "A__b", "double", "2").contains("'A__b', 2.0, 3, 0);"));
        assertTrue(DirectOverrides.insertSql("p", "A__b", "string", "it's").contains("'A__b', 'it''s', 4, 0);"));
    }

    @Test
    public void insertSurvivesTheStatementSplitter() {
        // The SQL runner splits scripts on semicolons outside quotes; a value containing one must stay whole.
        List<String> statements = RootSql.split(DirectOverrides.insertSql("p", "A__b", "string", "x;y"));
        assertEquals(1, statements.size());
        assertTrue(statements.get(0).contains("'x;y'"));
    }

    @Test
    public void validation() {
        assertNull(DirectOverrides.validate("A__b", "boolean", "true"));
        assertNull(DirectOverrides.validate("A__b", "long", "-5"));
        assertNotNull(DirectOverrides.validate("A__b", "boolean", "1"));
        assertNotNull(DirectOverrides.validate("A__b", "long", "1.5"));
        assertNotNull(DirectOverrides.validate("A b; DROP", "long", "1"));
        assertNotNull(DirectOverrides.validate("A__b", "bytes", "x"));
    }
}

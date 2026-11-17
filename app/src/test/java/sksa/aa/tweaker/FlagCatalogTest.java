package sksa.aa.tweaker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Keeps the bundled flag catalog well-formed. */
public class FlagCatalogTest {

    @Test
    public void catalogIsWellFormed() throws Exception {
        String json = new String(Files.readAllBytes(Paths.get("src/main/assets/flags.json")), StandardCharsets.UTF_8);
        JSONArray flags = new JSONObject(json).getJSONArray("flags");
        assertTrue(flags.length() > 50);

        Set<String> categories = new HashSet<>(Arrays.asList(FlagRepository.CATEGORIES));
        Set<String> risks = new HashSet<>(Arrays.asList("safe", "may_break", "experimental", "safety"));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < flags.length(); i++) {
            JSONObject f = flags.getJSONObject(i);
            String name = f.getString("name");
            assertTrue(name + " should look like Group__flag", name.matches("[A-Za-z]+__[a-z0-9_]+"));
            assertTrue("duplicate " + name, seen.add(name));
            assertTrue(name + " has unknown category", categories.contains(f.getString("category")));
            assertTrue(name + " has unknown risk", risks.contains(f.getString("risk")));
            assertFalse(name + " needs a title", f.getString("title").trim().isEmpty());
            assertTrue(name + " needs a real description", f.getString("description").trim().length() > 20);
        }
        assertEquals(flags.length(), seen.size());
    }
}

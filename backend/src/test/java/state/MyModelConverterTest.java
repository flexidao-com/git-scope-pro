package state;

import model.MyModelBase;
import org.junit.Test;
import settings.NoiseRuleState;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The tab models are persisted through {@link MyModelConverter}'s Gson round-trip; these tests pin
 * down that a tab's scope noise rules survive it and that tabs persisted before the field existed
 * load with a usable (never null) list.
 */
public class MyModelConverterTest {

    private final MyModelConverter converter = new MyModelConverter();

    @Test
    public void noiseRulesSurviveTheGsonRoundTrip() {
        MyModelBase model = new MyModelBase();
        model.setCustomTabName("review");
        model.setNoiseRules(List.of(new NoiseRuleState(true, "rename", "*.java", "onvoice", "invoice")));

        List<MyModelBase> restored = converter.fromString(converter.toString(List.of(model)));

        assertEquals(1, restored.size());
        assertEquals("review", restored.get(0).getCustomTabName());
        assertEquals(model.getNoiseRules(), restored.get(0).getNoiseRules());
    }

    @Test
    public void aTabPersistedBeforeScopeRulesExistedLoadsWithAnEmptyList() {
        MyModelBase restored = converter.fromString("[{\"customTabName\":\"old\"}]").get(0);

        assertNotNull(restored.getNoiseRules());
        assertTrue(restored.getNoiseRules().isEmpty());
    }

    @Test
    public void anExplicitNullNoiseRulesListLoadsAsEmpty() {
        /* A JSON import or a hand-edited workspace.xml can carry an explicit null list. */
        MyModelBase restored = converter.fromString("[{\"customTabName\":\"old\",\"noiseRules\":null}]").get(0);

        assertNotNull(restored.getNoiseRules());
        assertTrue(restored.getNoiseRules().isEmpty());
    }
}
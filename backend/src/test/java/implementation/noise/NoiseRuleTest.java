package implementation.noise;

import org.junit.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class NoiseRuleTest {

    private static final Pattern TWO_GROUPS = Pattern.compile("(\\w+)-(\\w+)");
    private static final Pattern NAMED_GROUP = Pattern.compile("(?<word>\\w+)");

    @Test
    public void validGroupReferencesPass() {
        assertNull(NoiseRule.validateReplacement(TWO_GROUPS, "$2-$1"));
        assertNull(NoiseRule.validateReplacement(TWO_GROUPS, "$0"));
        assertNull(NoiseRule.validateReplacement(TWO_GROUPS, "no references at all"));
    }

    @Test
    public void validNamedGroupReferencePasses() {
        assertNull(NoiseRule.validateReplacement(NAMED_GROUP, "${word}!"));
    }

    @Test
    public void escapedDollarAndBackslashPass() {
        assertNull(NoiseRule.validateReplacement(TWO_GROUPS, "\\$1"));
        assertNull(NoiseRule.validateReplacement(TWO_GROUPS, "\\\\"));
    }

    @Test
    public void groupIndexBeyondThePatternIsRejected() {
        assertNotNull(NoiseRule.validateReplacement(TWO_GROUPS, "$3"));
    }

    @Test
    public void trailingBackslashIsRejected() {
        assertNotNull(NoiseRule.validateReplacement(TWO_GROUPS, "x\\"));
    }

    @Test
    public void trailingDollarIsRejected() {
        assertNotNull(NoiseRule.validateReplacement(TWO_GROUPS, "x$"));
    }

    @Test
    public void unknownNamedGroupIsRejected() {
        assertNotNull(NoiseRule.validateReplacement(TWO_GROUPS, "${x}"));
    }

    @Test
    public void extraDigitsFallBackToALiteralJustLikeMatcherDoes() {
        /* $12 next to one group is group 1 followed by a literal "2", exactly like appendReplacement. */
        assertNull(NoiseRule.validateReplacement(Pattern.compile("(a)"), "$12"));
    }

    @Test
    public void aBrokenReplacementLeavesTheTextUntouched() {
        NoiseNormalizer normalizer = new NoiseNormalizer(List.of(new NoiseRule("bad", null, "(a)", "$2")));

        assertEquals("a", normalizer.normalize("f.txt", "a"));
        assertEquals("a", normalizer.normalizeKeepingLines("f.txt", "a"));
    }
}
package com.moral.csv;

import com.moral.model.ColumnProfile;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

/** CSV 数值保真回归测试。 */
public class TypeInferencerTest {

    @Test
    public void leadingZerosRemainStrings() {
        for (String value : new String[]{"00123", "-00123", "+00123", "00.12", "001e3"}) {
            assertEquals(value, "STRING", infer(value, "12").getType().name());
        }
    }

    @Test
    public void fullLongRangeRemainsInteger() {
        assertEquals("LONG", infer("9223372036854775807", "-9223372036854775808").getType().name());
    }

    @Test
    public void largeIntegersAndFractionsAreExact() {
        assertEquals("DECIMAL", infer("9223372036854775808").getType().name());
        assertEquals("DECIMAL", infer("0.123456789012345678901234567890").getType().name());
    }

    /** 对单列累计全部样本。 */
    private ColumnProfile infer(String... values) {
        ColumnProfile profile = new ColumnProfile("value");
        TypeInferencer.Inferencer inferencer = TypeInferencer.create(Collections.singletonList(profile));
        for (String value : values) {
            inferencer.accept(Collections.singletonList(value));
        }
        inferencer.finish();
        return profile;
    }
}

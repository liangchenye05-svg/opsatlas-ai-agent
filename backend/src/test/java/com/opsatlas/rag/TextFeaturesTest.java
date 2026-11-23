package com.opsatlas.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextFeaturesTest {
    @Test
    void tokenizesChineseAndKeepsCaseInsensitiveBusinessCodes() {
        assertThat(TextFeatures.terms("售后退款 SKU42，门店 A7"))
                .containsExactly("售后", "后退", "退款", "sku42", "门店", "a7");
    }

    @Test
    void keepsRepeatedTermsForFrequencyScoring() {
        assertThat(TextFeatures.terms("退款、退款。")).containsExactly("退款", "退款");
    }
}

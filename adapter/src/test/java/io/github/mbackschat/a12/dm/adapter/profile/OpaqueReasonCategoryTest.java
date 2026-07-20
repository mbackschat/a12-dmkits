package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OpaqueReasonCategoryTest {

    @Test
    void stripsPathLiteralAndImplementationDetailIntoClosedFailureFamilies() {
        assertEquals(
                OpaqueReasonCategory.CONVERSION_LIMIT,
                OpaqueReasonCategory.of(
                        "value-list on unknown field /Private/Customer/Secret with value 'gold'"));
        assertEquals(
                OpaqueReasonCategory.KERNEL_EXTRACTION_FAILURE,
                OpaqueReasonCategory.of("the kernel could not extract calculation info"));
        assertEquals(
                OpaqueReasonCategory.READER_FAILURE,
                OpaqueReasonCategory.of(
                        "reader failure: com.project.PrivateCondition at /Private/Customer"));
    }
}

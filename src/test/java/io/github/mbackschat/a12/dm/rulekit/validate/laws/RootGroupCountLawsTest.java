package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.ModelDrafts;
import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS — {@code NumberOfFilledGroups} DOES accept a root group, as the sole starred operand
 * (source {@code CheckFeldListenAnzahlOpImpl} {@code checkNoRootGroupWhenMoreThanOneParameter}). This
 * corrects the catalog's earlier "no root groups" constraint.
 */
class RootGroupCountLawsTest {

    private static final String MULTIFORM = ModelDrafts.repeatableRoot().toJson();

    @Test
    void numberOfFilledGroupsAcceptsASoleStarredRootGroup() {
        KernelLaws.acceptsModel(MULTIFORM, "/Header", "../Title",
                "FieldFilled(Title) And NumberOfFilledGroups(/Persons*) > 0");
    }
}

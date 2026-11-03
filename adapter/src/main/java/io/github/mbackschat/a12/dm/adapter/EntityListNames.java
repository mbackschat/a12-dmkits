package io.github.mbackschat.a12.dm.adapter;

import java.util.Optional;

import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.types.EntityListConditionName;

import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Quantifier;

/** How {@link ConditionConverter} classifies an A12 entity-list condition name when it reads one back. */
final class EntityListNames {

    private EntityListNames() {
    }

    /** The presence leaf one field under {@code AllFieldsFilled}/{@code NoFieldFilled} reads back as. */
    static Optional<Condition> singleFieldPresence(EntityListConditionName name, FieldRef field) {
        return switch (name) {
            case AllFieldsFilled -> Optional.of(field.filled());
            case NoFieldFilled -> Optional.of(field.notFilled());
            default -> Optional.empty();
        };
    }

    /** The five fixed group-list quantifier names — the common field-or-group entity list. */
    static boolean isGroupListQuantifier(EntityListConditionName name) {
        return switch (name) {
            case AllGroupsFilled, NoGroupFilled, AtLeastOneGroupFilled, NotAllGroupsFilled,
                    GroupsNotCollectivelyFilled -> true;
            default -> false;
        };
    }

    /**
     * The typed quantifier behind an entity-list condition name, for the operators whose entity list may
     * legitimately mix fields and groups — the seven fill quantifiers plus {@code FieldValuesNotUnique}
     * (KERNEL-FINDINGS {@code kf187}). {@code DateRangesOverlap} is deliberately absent: it refuses a group
     * operand outright, so a mixed list of its is not a shape any stored model can contain.
     */
    static Optional<Quantifier> entityListQuantifier(EntityListConditionName name) {
        return Optional.ofNullable(switch (name) {
            case AllFieldsFilled -> Quantifier.ALL_FIELDS_FILLED;
            case NoFieldFilled -> Quantifier.NO_FIELD_FILLED;
            case AtLeastOneFieldFilled -> Quantifier.AT_LEAST_ONE_FIELD_FILLED;
            case NotAllFieldsFilled -> Quantifier.NOT_ALL_FIELDS_FILLED;
            case FieldsNotCollectivelyFilled -> Quantifier.FIELDS_NOT_COLLECTIVELY_FILLED;
            case NotExactlyOneFieldFilled -> Quantifier.NOT_EXACTLY_ONE_FIELD_FILLED;
            case MoreThanOneFieldFilled -> Quantifier.MORE_THAN_ONE_FIELD_FILLED;
            case FieldValuesNotUnique -> Quantifier.FIELD_VALUES_NOT_UNIQUE;
            default -> null;
        });
    }

    /** The seven field-fill quantifier names (over fields, not groups). */
    static boolean isFieldFillQuantifier(EntityListConditionName name) {
        return switch (name) {
            case AllFieldsFilled, NoFieldFilled, AtLeastOneFieldFilled, NotAllFieldsFilled,
                    FieldsNotCollectivelyFilled, NotExactlyOneFieldFilled, MoreThanOneFieldFilled -> true;
            default -> false;
        };
    }
}

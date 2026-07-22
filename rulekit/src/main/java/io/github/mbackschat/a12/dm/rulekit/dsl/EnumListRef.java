package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

/**
 * A member of an <em>enum</em> field-list value-list quantifier
 * ({@code AtLeastOne}/{@code No}/{@code NotAll}{@code FieldValue(s)IncludedInValueList(… In "a", "b")})
 * — either a direct enumeration reference ({@link EnumRef}) or a category projection
 * ({@link CategoryRef}, {@code [field -> Cat]}). A single list may mix the two, exactly as the kernel
 * allows: each literal is admitted against the <b>union</b> of the selected members' domains (a direct
 * ref contributes its stored values, a category ref its category values), and a direct ref and a
 * category ref on the same field are distinct members.
 */
public sealed interface EnumListRef permits EnumRef, CategoryRef {

    /** This reference as a value-list operand ({@code [path]} for a direct ref, {@code [path -> Cat]} for a category). */
    ValueListOperand listOperand();

    /** The stored/category values this member contributes to the admissible union. */
    List<String> admissibleValues();
}

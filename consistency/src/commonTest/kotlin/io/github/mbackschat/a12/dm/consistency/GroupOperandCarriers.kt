package io.github.mbackschat.a12.dm.consistency

/** The group carriers the operand-walk mirrors share, each the kernel list of the law test they mirror. */
internal object GroupOperandCarriers {

    /** `GroupOperandRepetitionScopeLawsTest.CARRIERS`: every carrier naming the repeatable `Claims` unstarred. */
    val CARRIERS = listOf(
        "FieldFilled(Claims)", "FieldNotFilled(Claims)", "GroupFilled(Claims)", "GroupNotFilled(Claims)",
        "AtLeastOneGroupFilled(Claims)", "AtLeastOneGroupFilled(Claims, Insured)",
        "AllGroupsFilled(Claims, Insured)", "NoGroupFilled(Claims, Insured)",
        "NotAllGroupsFilled(Claims, Insured)", "GroupsNotCollectivelyFilled(Claims, Insured)",
        "NumberOfFilledGroups(Claims) > 0", "AtLeastOneFieldFilled(Claims, Insured)",
        "AllFieldsFilled(Claims, Insured)", "NoFieldFilled(Claims, Insured)",
        "NotAllFieldsFilled(Claims, Insured)", "NotExactlyOneFieldFilled(Claims, Insured)",
        "MoreThanOneFieldFilled(Claims, Insured)", "NumberOfFilledFields(Claims) > 0",
        "Sum(Claims, Insured) > 0", "FirstFilledValue(Claims, Insured) > 0",
        "NumberOfValueInFields(0 In Claims) > 0", "CurrentRepetition(Claims) > 1",
    )
}

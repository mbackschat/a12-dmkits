package io.github.mbackschat.a12.dm.interpreter.model

/**
 * The keyword language declared by `content.modelConfig.conditionLanguage`. Display locales are independent:
 * a model can carry English labels while its stored conditions use German keywords.
 */
enum class ConditionLanguage(val code: String) {
    EN("en_US"),
    DE("de_DE"),
    ;

    /**
     * The canonical operator identifier represented by [authored] in this language, or null when the identifier
     * remains available to the model as a field/group name.
     */
    internal fun canonicalKeyword(authored: String): String? =
        when {
            authored in IN -> "In"
            this == EN && authored in EN_AND -> "And"
            this == DE && authored in DE_AND -> "And"
            this == EN && authored in EN_OR -> "Or"
            this == DE && authored in DE_OR -> "Or"
            this == EN && authored in EN_TO -> "To"
            this == DE && authored in DE_TO -> "To"
            this == EN -> authored.takeIf { it in ENGLISH_KEYWORDS }
            else -> GERMAN_TO_CANONICAL[authored]
        }

    /** Every authored spelling in this language that denotes [canonical]. */
    internal fun authoredForms(canonical: String): Set<String> =
        when {
            canonical == "In" -> IN
            this == EN && canonical == "And" -> EN_AND
            this == DE && canonical == "And" -> DE_AND
            this == EN && canonical == "Or" -> EN_OR
            this == DE && canonical == "Or" -> DE_OR
            this == EN && canonical == "To" -> EN_TO
            this == DE && canonical == "To" -> DE_TO
            this == EN && canonical in ENGLISH_KEYWORDS -> setOf(canonical)
            this == DE -> GERMAN_TO_CANONICAL.entries
                .asSequence()
                .filter { (_, value) -> value == canonical }
                .mapTo(linkedSetOf()) { (authored, _) -> authored }
            else -> emptySet()
        }

    /** Whether [authored] is reserved by the other condition language but not by this one. */
    internal fun isForeignKeyword(authored: String): Boolean =
        canonicalKeyword(authored) == null && authored in ALL_AUTHORED_KEYWORDS

    internal fun accepts(authored: String, canonical: String): Boolean =
        canonicalKeyword(authored) == canonical

    companion object {
        private val EN_AND = setOf("And", "and", "AND")
        private val DE_AND = setOf("Und", "und", "UND")
        private val EN_OR = setOf("Or", "or", "OR")
        private val DE_OR = setOf("Oder", "oder", "ODER")
        private val EN_TO = setOf("To", "to", "TO")
        private val DE_TO = setOf("Zu", "zu", "ZU")
        private val IN = setOf("In", "in", "IN")

        internal fun fromCode(code: String?): ConditionLanguage =
            if (code == DE.code) DE else EN

        /**
         * Identifier keywords from the CI-locked bilingual operator catalog plus the grammar-only `In`,
         * `RuleGroup`/`RegelKontext`, `@From`/`@Von` and the `@SuppressWarning`/`@UnterdrueckeWarnung` preamble
         * tokens. Symbol operators are language independent.
         */
        private val GERMAN_TO_CANONICAL = mapOf(
            "FeldAngegeben" to "FieldFilled",
            "FeldNichtAngegeben" to "FieldNotFilled",
            "Und" to "And",
            "Oder" to "Or",
            "UngleichMitToleranz1" to "DiffersWithToleranceRange1",
            "UngleichMitToleranz2" to "DiffersWithToleranceRange2",
            "UngleichMitToleranz5" to "DiffersWithToleranceRange5",
            "UngleichMitToleranz10" to "DiffersWithToleranceRange10",
            "AlleFelderAngegeben" to "AllFieldsFilled",
            "KeinFeldAngegeben" to "NoFieldFilled",
            "MindestensEinFeldAngegeben" to "AtLeastOneFieldFilled",
            "NichtAlleFelderOderKeinFeldAngegeben" to "NotAllFieldsFilled",
            "FelderNichtGemeinsamAngegeben" to "FieldsNotCollectivelyFilled",
            "NichtGenauEinFeldAngegeben" to "NotExactlyOneFieldFilled",
            "MehrAlsEinFeldAngegeben" to "MoreThanOneFieldFilled",
            "KontextAngegeben" to "GroupFilled",
            "KontextNichtAngegeben" to "GroupNotFilled",
            "AlleKontexteAngegeben" to "AllGroupsFilled",
            "KeinKontextAngegeben" to "NoGroupFilled",
            "MindestensEinKontextAngegeben" to "AtLeastOneGroupFilled",
            "NichtAlleOderKeinKontextAngegeben" to "NotAllGroupsFilled",
            "KontexteNichtGemeinsamAngegeben" to "GroupsNotCollectivelyFilled",
            "Wahr" to "True",
            "Falsch" to "False",
            "Laenge" to "Length",
            "WieMuster" to "PatternMatched",
            "NichtWieMuster" to "PatternViolated",
            "FeldWertInWerteListe" to "FieldValueIncludedInValueList",
            "FeldWertNichtInWerteListe" to "FieldValueNotIncludedInValueList",
            "MindestensEinFeldWertInWerteListe" to "AtLeastOneFieldValueIncludedInValueList",
            "KeinFeldWertInWerteListe" to "NoFieldValueIncludedInValueList",
            "NichtAlleFeldWerteInWerteListe" to "NotAllFieldValuesIncludedInValueList",
            "AnzahlAngegebenerFelder" to "NumberOfFilledFields",
            "AnzahlAngegebenerKontexte" to "NumberOfFilledGroups",
            "WiederholungNichtEindeutig" to "RepetitionNotUnique",
            "FeldWerteNichtEindeutig" to "FieldValuesNotUnique",
            "Summe" to "Sum",
            "MaxWert" to "MaxValue",
            "MinWert" to "MinValue",
            "Abrunden" to "RoundDown",
            "AbrundenWert" to "RoundDownValue",
            "AufrundenWert" to "RoundUpValue",
            "RundenWert" to "RoundAccountingValue",
            "AbsWert" to "AbsValue",
            "Aufrunden" to "RoundUp",
            "Runden" to "RoundAccounting",
            "AnzahlVerschiedenerWerte" to "NumberOfDifferentValues",
            "AnzahlWertInFeldliste" to "NumberOfValueInFields",
            "Mit" to "Having",
            "AktuelleWiederholung" to "CurrentRepetition",
            "ApplikationsBedingung" to "CustomCondition",
            "Fuer" to "For",
            "Von" to "From",
            "Heute" to "Today",
            "DifferenzInTagen" to "DifferenceInDays",
            "DifferenzInMonaten" to "DifferenceInMonths",
            "DifferenzInJahren" to "DifferenceInYears",
            "DifferenzInStunden" to "DifferenceInHours",
            "DifferenzInMinuten" to "DifferenceInMinutes",
            "DifferenzInSekunden" to "DifferenceInSeconds",
            "AddiereTage" to "AddDays",
            "AddiereMonate" to "AddMonths",
            "AddiereJahre" to "AddYears",
            "AddiereStunden" to "AddHours",
            "AddiereMinuten" to "AddMinutes",
            "AddiereSekunden" to "AddSeconds",
            "JahrAusDatum" to "YearFromDate",
            "MonatAusDatum" to "MonthFromDate",
            "TagAusDatum" to "DayFromDate",
            "QuartalAusDatum" to "QuarterFromDate",
            "StartAusDatumBereich" to "StartOfDateRange",
            "EndeAusDatumBereich" to "EndOfDateRange",
            "DatumsBereicheUeberlappend" to "DateRangesOverlap",
            "MindestensEinDatumsBereichUeberlappend" to "AtLeastOneDateRangeOverlaps",
            "StundenAusZeit" to "HoursFromTime",
            "MinutenAusZeit" to "MinutesFromTime",
            "SekundenAusZeit" to "SecondsFromTime",
            "DatumAusDateTime" to "DateFromDateTime",
            "ZeitAusDateTime" to "TimeFromDateTime",
            "BereichAlsString" to "RangeAsString",
            "FeldWertAlsString" to "FieldValueAsString",
            "BereichAlsZahl" to "RangeAsNumber",
            "FeldWertAlsZahl" to "FieldValueAsNumber",
            "WertAlsDatum" to "ValueAsDate",
            "ErsterTag" to "FirstDay",
            "LetzterTag" to "LastDay",
            "Datum" to "Date",
            "Zeit" to "Time",
            "Jetzt" to "Now",
            "Referenzjahr" to "BaseYear",
            "Gueltig" to "Valid",
            "Ungueltig" to "Invalid",
            "SummeVonProdukten" to "SumOfProducts",
            "ErsterAngegebenerWert" to "FirstFilledValue",
            "DatumBereich" to "DateRange",
            "RegelKontext" to "RuleGroup",
            "UnterdrueckeWarnung" to "SuppressWarning",
        )

        private val ENGLISH_KEYWORDS = GERMAN_TO_CANONICAL.values.toSet() + setOf("In", "To")
        private val ALL_AUTHORED_KEYWORDS =
            ENGLISH_KEYWORDS +
                GERMAN_TO_CANONICAL.keys +
                EN_AND +
                DE_AND +
                EN_OR +
                DE_OR +
                EN_TO +
                DE_TO +
                IN
    }
}

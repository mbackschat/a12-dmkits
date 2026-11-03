package io.github.mbackschat.a12.dm.adapter;

/**
 * Document uniqueness criteria spelled as the DM-JSON content member A12 reads (KERNEL-FINDINGS KF671). The kits
 * cannot author a criterion (DEV-API-GAPS DG176), so the tests that need one insert it textually here.
 */
public final class UniquenessCriteriaJson {

    private UniquenessCriteriaJson() {
    }

    /** One criterion over [fields], whose {@code en_US} error message is [text]. */
    public static String criterion(String name, String text, String... fields) {
        StringBuilder list = new StringBuilder();
        for (String field : fields) {
            list.append(list.isEmpty() ? "" : ",").append("{\"fullName\":\"").append(field).append("\"}");
        }
        return "{\"name\":\"" + name + "\",\"fields\":[" + list + "],"
                + "\"errorMessage\":[{\"locale\":\"en_US\",\"text\":\"" + text + "\"}]}";
    }

    /** [json] with these criteria inserted before its {@code modelRoot}. */
    public static String withCriteria(String json, String... criteria) {
        int at = json.indexOf("\"modelRoot\"");
        return json.substring(0, at) + "\"documentUniquenessCriteria\":[" + String.join(",", criteria) + "],"
                + json.substring(at);
    }
}

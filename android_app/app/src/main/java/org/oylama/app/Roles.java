package org.oylama.app;

public final class Roles {
    public static final String[] ALL = {"voter", "authority", "registrar", "trustee", "auditor"};

    private Roles() {
    }

    public static String label(String r) {
        switch (r) {
            case "authority":
                return "Authority";
            case "registrar":
                return "Registrar";
            case "trustee":
                return "Trustee";
            case "auditor":
                return "Auditor";
            default:
                return "Voter";
        }
    }

    public static int icon(String r) {
        switch (r) {
            case "authority":
                return R.drawable.ic_building;
            case "registrar":
                return R.drawable.ic_clipboard;
            case "trustee":
                return R.drawable.ic_key;
            case "auditor":
                return R.drawable.ic_scan;
            default:
                return R.drawable.ic_ballot;
        }
    }

    public static String description(String r) {
        switch (r) {
            case "authority":
                return "Create elections, set candidates and board capacity, and drive every phase.";
            case "registrar":
                return "Admit eligible voters, encrypt their credentials into the roster and seal delivery.";
            case "trustee":
                return "Hold a share of the threshold key, join the key ceremony and release shares for the tally.";
            case "auditor":
                return "Inspect the bulletin board, roster and transcripts, and re-verify every proof.";
            default:
                return "Get a credential, cast an encrypted ballot anonymously, revote, and verify your tracker.";
        }
    }
}

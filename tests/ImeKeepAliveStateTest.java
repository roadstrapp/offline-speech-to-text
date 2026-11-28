package dev.notune.transcribe;

public final class ImeKeepAliveStateTest {
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        ImeKeepAliveState owner = new ImeKeepAliveState();
        long first = owner.begin();
        check(owner.pending() && !owner.ready(), "queued is not foreground confirmation");
        check(!owner.acknowledge(first + 1) && !owner.ready(), "wrong callback accepted");
        check(owner.acknowledge(first) && owner.ready(), "exact confirmation rejected");
        owner.finish();
        check(!owner.matches(first) && !owner.acknowledge(first), "late confirmation resurrected stop");
        long second = owner.begin();
        check(second != first && !owner.matches(first), "stale notification matches new recording");
        check(owner.acknowledge(second), "replacement confirmation rejected");
        ImeKeepAliveState replacementIme = new ImeKeepAliveState();
        check(replacementIme.begin() != second, "IME recreation reuses token");
        ImeRecordingSession session = new ImeRecordingSession();
        Object editor = new Object();
        for (String unknown : new String[] {null, ""}) {
            session.begin(editor, 1, unknown);
            check(!session.canDeliverToCurrent(editor, true, true, "app.example"),
                    "unknown origin must retain text privately");
        }
        session.begin(editor, 2, "app.example");
        check(session.canDeliverToCurrent(new Object(), true, true, "app.example"),
                "same-app reactive editor rejected");
        check(!session.canDeliverToCurrent(editor, true, true, null), "unknown destination accepted");
        check(!session.canDeliverToCurrent(editor, true, true, "other.example"), "cross-app delivery");
        session.markEditorMoved();
        check(!session.canDeliverToCurrent(editor, true, true, "app.example"),
                "another editor in the same app received hidden dictation");
        session.begin(editor, 3, "app.example");
        check(session.canDeliverToCurrent(editor, true, true, "app.example"), "new capture remained moved");
        System.out.println("ImeKeepAliveStateTest passed");
    }
}

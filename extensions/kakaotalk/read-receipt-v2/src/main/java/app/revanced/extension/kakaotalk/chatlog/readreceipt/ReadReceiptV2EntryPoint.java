package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import android.content.Context;

public final class ReadReceiptV2EntryPoint {
    private ReadReceiptV2EntryPoint() {
    }

    public static void initialize(Context context) {
        ReadReceiptV2Initializer.initialize(context);
    }
}

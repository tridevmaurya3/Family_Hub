package com.tridev.familyhub.feature.chat;

import android.content.Context;
import android.content.ContextWrapper;
import android.widget.TextView;

final class FloatingChatSizing {
    private FloatingChatSizing() {}

    static boolean applies(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof FloatingChatActivity) return true;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return false;
    }

    static float text(Context context, float fullSize) {
        return applies(context) && fullSize > 11 ? Math.max(11, fullSize * .86f) : fullSize;
    }

    static int pixels(Context context, int fullSize) {
        return Math.round(fullSize * (applies(context) ? .8f : 1f)
                * context.getResources().getDisplayMetrics().density);
    }

    static int target(Context context, int fullSize) {
        int size = applies(context) && fullSize >= 44 ? 44 : fullSize;
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }

    static void compactButton(TextView button) {
        Context context = button.getContext();
        if (!applies(context)) return;
        button.setTextSize(12);
        button.setMinHeight(target(context, 48));
        button.setMinimumHeight(target(context, 48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
    }
}

package sksa.aa.tweaker.relay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.RemoteInput;

/** Handles "reply" and "mark as read" coming back from Android Auto (or the phone). */
public class RelayReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String sourceKey = intent.getStringExtra(RelayService.EXTRA_SOURCE_KEY);
        if (sourceKey == null) return;
        RelayService service = RelayService.get();

        if (RelayService.ACTION_REPLY.equals(intent.getAction())) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence text = results == null ? null : results.getCharSequence(RelayService.KEY_REPLY);
            boolean sent = text != null && service != null && service.sendReply(sourceKey, text);
            if (!sent) Log.w(RelayService.TAG, "Reply could not be delivered to the source app");
        }
        // Replying also counts as reading: clear both notifications.
        if (service != null) service.dismissSource(sourceKey);
        NotificationManagerCompat.from(context)
                .cancel(RelayService.NOTIFICATION_TAG, RelayService.idFor(sourceKey));
    }
}

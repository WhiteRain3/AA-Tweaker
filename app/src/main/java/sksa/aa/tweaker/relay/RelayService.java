package sksa.aa.tweaker.relay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.car.app.connection.CarConnection;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.lifecycle.Observer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import sksa.aa.tweaker.R;

/**
 * Re-posts message notifications from apps Android Auto rejects (Messenger posts no
 * "mark as read" action) as car-compatible message notifications, and passes replies back
 * through the original notification's own reply action.
 *
 * Everything stays on the device: this only reads and posts notifications.
 */
public class RelayService extends NotificationListenerService {

    static final String TAG = "AATweakerRelay";
    static final String PREFS = "relay";
    static final String PREF_ENABLED = "enabled";
    static final String PREF_ONLY_IN_CAR = "only_in_car";
    static final String CHANNEL = "relayed_messages";
    static final String NOTIFICATION_TAG = "relay";
    static final String KEY_REPLY = "relay_reply_text";
    static final String EXTRA_SOURCE_KEY = "source_key";
    static final String ACTION_REPLY = "sksa.aa.tweaker.relay.REPLY";
    static final String ACTION_READ = "sksa.aa.tweaker.relay.READ";

    /** Apps whose message notifications are relayed. */
    static final Set<String> SOURCES = new HashSet<>(Arrays.asList(
            "com.facebook.orca", "com.facebook.mlite"));

    private static volatile RelayService instance;
    private volatile int carConnectionType = CarConnection.CONNECTION_TYPE_NOT_CONNECTED;
    private CarConnection carConnection;
    private final Observer<Integer> carObserver = type -> {
        carConnectionType = type == null ? CarConnection.CONNECTION_TYPE_NOT_CONNECTED : type;
        if (carConnectionType != CarConnection.CONNECTION_TYPE_PROJECTION && onlyInCar()) {
            cancelAllRelayed();
        }
    };

    static RelayService get() {
        return instance;
    }

    @Override
    public void onListenerConnected() {
        instance = this;
        ensureChannel(this);
        carConnection = new CarConnection(this);
        carConnection.getType().observeForever(carObserver);
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        if (carConnection != null) carConnection.getType().removeObserver(carObserver);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!SOURCES.contains(sbn.getPackageName())) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_ENABLED, true)) return;
        if (onlyInCar() && carConnectionType != CarConnection.CONNECTION_TYPE_PROJECTION) return;

        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 || sbn.isOngoing()) return;
        try {
            Notification relayed = buildRelayed(this, sbn);
            if (relayed != null) {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_TAG, idFor(sbn.getKey()), relayed);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Not allowed to post notifications", e);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not relay notification from " + sbn.getPackageName(), e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (SOURCES.contains(sbn.getPackageName())) {
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_TAG, idFor(sbn.getKey()));
        }
    }

    private boolean onlyInCar() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_ONLY_IN_CAR, true);
    }

    private void cancelAllRelayed() {
        try {
            for (StatusBarNotification sbn : getActiveNotifications()) {
                if (getPackageName().equals(sbn.getPackageName()) && NOTIFICATION_TAG.equals(sbn.getTag())) {
                    NotificationManagerCompat.from(this).cancel(NOTIFICATION_TAG, sbn.getId());
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not clear relayed notifications", e);
        }
    }

    // ---- building the car-compatible copy -----------------------------------------------

    static int idFor(String sourceKey) {
        return sourceKey.hashCode();
    }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        // High importance so Android Auto shows a popup; silent so the phone does not ring twice.
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                context.getString(R.string.relay_channel), NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        channel.enableVibration(false);
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private static Notification buildRelayed(Context context, StatusBarNotification sbn) {
        Notification source = sbn.getNotification();
        Bundle extras = source.extras;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);

        Person me = new Person.Builder().setName(context.getString(R.string.relay_me)).build();
        NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(me);

        NotificationCompat.MessagingStyle original =
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(source);
        boolean copied = false;
        if (original != null) {
            style.setConversationTitle(original.getConversationTitle());
            style.setGroupConversation(original.isGroupConversation());
            List<NotificationCompat.MessagingStyle.Message> messages = original.getMessages();
            for (NotificationCompat.MessagingStyle.Message m : messages) {
                if (m.getText() == null) continue;
                Person sender = m.getPerson();
                if (sender == null && title != null) sender = new Person.Builder().setName(title).build();
                style.addMessage(m.getText(), m.getTimestamp(), sender);
                copied = true;
            }
        }
        if (!copied) {
            if (text == null) return null; // nothing readable to relay
            Person sender = new Person.Builder()
                    .setName(title != null ? title : sbn.getPackageName()).build();
            style.addMessage(text, sbn.getPostTime(), sender);
        }

        return decorate(context, style, sbn.getKey(), sbn.getPostTime(), findReplyAction(source) != null);
    }

    /** Adds the reply and mark-as-read actions Android Auto requires. */
    static Notification decorate(Context context, NotificationCompat.MessagingStyle style,
                                 String sourceKey, long when, boolean canReply) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_relay_message)
                .setStyle(style)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setWhen(when)
                .setShowWhen(true)
                .setOnlyAlertOnce(false);

        int id = idFor(sourceKey);
        if (canReply) {
            Intent reply = new Intent(context, RelayReceiver.class)
                    .setAction(ACTION_REPLY).putExtra(EXTRA_SOURCE_KEY, sourceKey);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
            builder.addAction(new NotificationCompat.Action.Builder(0, context.getString(R.string.relay_reply),
                    PendingIntent.getBroadcast(context, id, reply, flags))
                    .addRemoteInput(new RemoteInput.Builder(KEY_REPLY)
                            .setLabel(context.getString(R.string.relay_reply)).build())
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .build());
        }
        Intent read = new Intent(context, RelayReceiver.class)
                .setAction(ACTION_READ).putExtra(EXTRA_SOURCE_KEY, sourceKey);
        builder.addInvisibleAction(new NotificationCompat.Action.Builder(0, context.getString(R.string.relay_mark_read),
                PendingIntent.getBroadcast(context, id, read,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build());
        return builder.build();
    }

    /** The source app's own inline-reply action, if it has one. */
    static Notification.Action findReplyAction(Notification source) {
        if (source.actions == null) return null;
        for (Notification.Action action : source.actions) {
            android.app.RemoteInput[] inputs = action.getRemoteInputs();
            if (inputs == null) continue;
            for (android.app.RemoteInput input : inputs) {
                if (input.getAllowFreeFormInput()) return action;
            }
        }
        return null;
    }

    // ---- called by RelayReceiver ----------------------------------------------------------

    /** Sends the text through the source notification's reply action. */
    boolean sendReply(String sourceKey, CharSequence text) {
        StatusBarNotification[] found = getActiveNotifications(new String[]{sourceKey});
        if (found == null || found.length == 0) return false;
        Notification.Action action = findReplyAction(found[0].getNotification());
        if (action == null) return false;
        Intent fill = new Intent();
        Bundle results = new Bundle();
        for (android.app.RemoteInput input : action.getRemoteInputs()) {
            results.putCharSequence(input.getResultKey(), text);
        }
        android.app.RemoteInput.addResultsToIntent(action.getRemoteInputs(), fill, results);
        try {
            action.actionIntent.send(this, 0, fill);
            return true;
        } catch (PendingIntent.CanceledException e) {
            Log.w(TAG, "Reply action was cancelled", e);
            return false;
        }
    }

    /** Dismisses the source notification; the relayed copy goes away with it. */
    void dismissSource(String sourceKey) {
        try {
            cancelNotification(sourceKey);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not dismiss source notification", e);
        }
    }
}

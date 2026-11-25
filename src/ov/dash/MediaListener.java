package ov.dash;

import android.app.Notification;
import android.content.ComponentName;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.List;

/**
 * 媒体信息数据源。
 *
 * 两条路都走：
 *  1) MediaSessionManager.getActiveSessions() —— 主力。蓝牙音乐、酷我这类
 *     只有 MediaSession 没有通知，只能从这里拿。
 *  2) 通知里的 EXTRA_MEDIA_SESSION —— 补充，有些播放器只靠通知暴露。
 *
 * 只提供数据，不做任何视觉决策。
 */
public class MediaListener extends NotificationListenerService {

    private static final String TAG = "OVDASH";

    static volatile String title;
    static volatile String artist;
    static volatile String album;
    /** PlaybackState.STATE_*，0 表示没有会话。 */
    static volatile int playing;

    private MediaSessionManager msm;
    private MediaSessionManager.OnActiveSessionsChangedListener cb;
    private ComponentName me;
    private AudioManager am;
    private AudioManager.AudioPlaybackCallback audioCb;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        me = new ComponentName(this, MediaListener.class);
        msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        if (msm == null) {
            Log.e(TAG, "media: MediaSessionManager null");
            return;
        }
        cb = new MediaSessionManager.OnActiveSessionsChangedListener() {
            @Override
            public void onActiveSessionsChanged(List<MediaController> list) {
                pick(list);
                MediaCenterReader.refresh();
            }
        };
        try {
            msm.addOnActiveSessionsChangedListener(cb, me);
            pick(msm.getActiveSessions(me));
            Log.e(TAG, "media: session listener ready");
        } catch (Throwable t) {
            Log.e(TAG, "media: init fail " + t);
        }
        // 蓝牙的 MediaSession 是 active=false，上面那个回调不会响。
        // 播放/暂停/切歌会让 AudioPlaybackCallback 响，用它当触发器去拉车机媒体中心。
        try {
            am = (AudioManager) getSystemService(AUDIO_SERVICE);
            audioCb = new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    MediaCenterReader.refresh();
                }
            };
            if (am != null) {
                am.registerAudioPlaybackCallback(audioCb, null);
            }
            MediaCenterReader.init(this);
            Log.e(TAG, "media: audio callback ready");
        } catch (Throwable t) {
            Log.e(TAG, "media: audio cb fail " + t);
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (msm != null && cb != null) {
            try {
                msm.removeOnActiveSessionsChangedListener(cb);
            } catch (Throwable ignore) {
                // 已移除
            }
        }
        if (am != null && audioCb != null) {
            try {
                am.unregisterAudioPlaybackCallback(audioCb);
            } catch (Throwable ignore) {
                // 已注销
            }
        }
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // 有些播放器只通过通知暴露会话，补一条路
        try {
            Notification n = sbn.getNotification();
            if (n == null || n.extras == null) {
                return;
            }
            Parcelable p = n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
            if (p instanceof MediaSession.Token && msm != null && me != null) {
                pick(msm.getActiveSessions(me));
            }
        } catch (Throwable t) {
            Log.e(TAG, "media notify fail: " + t);
        }
    }

    private void pick(List<MediaController> list) {
        if (list == null || list.isEmpty()) {
            Log.e(TAG, "media: sessions empty");
            return;
        }
        // 排查用：看得到哪些会话
        StringBuilder sb = new StringBuilder("media: sessions=" + list.size());
        for (MediaController mc : list) {
            PlaybackState ps = mc.getPlaybackState();
            sb.append(" [").append(mc.getPackageName())
                    .append(" state=").append(ps == null ? -1 : ps.getState())
                    .append(" meta=").append(mc.getMetadata() != null).append(']');
        }
        Log.e(TAG, sb.toString());
        MediaController best = null;
        for (MediaController mc : list) {
            PlaybackState ps = mc.getPlaybackState();
            if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                best = mc;
                break;
            }
        }
        if (best == null) {
            best = list.get(0);
        }
        try {
            MediaMetadata md = best.getMetadata();
            if (md != null) {
                String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                String al = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
                // 空值不清掉旧的，避免播放器抖动导致名字闪没
                if (t != null) {
                    title = t;
                }
                if (a != null) {
                    artist = a;
                }
                if (al != null) {
                    album = al;
                }
            }
            PlaybackState ps = best.getPlaybackState();
            if (ps != null) {
                playing = ps.getState();
            }
            Log.e(TAG, "media -> " + title + " / " + artist + " state=" + playing);
        } catch (Throwable t) {
            Log.e(TAG, "media pick fail: " + t);
        }
    }
}

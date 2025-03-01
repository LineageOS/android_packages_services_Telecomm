/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.telecom;

import static android.media.AudioPlaybackConfiguration.PLAYER_STATE_STARTED;

import android.annotation.IntDef;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioManager.AudioPlaybackCallback;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Process;
import android.telecom.Log;
import android.telecom.Logging.EventManager;
import android.telecom.PhoneAccountHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LocalLog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.telecom.metrics.TelecomMetricsController;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Monitors {@link AudioRecord}, {@link AudioTrack}, and {@link AudioManager#getMode()} to determine
 * the reliability of audio operations for a call.  Augments the Telecom dumpsys with Telecom calls
 * with information about calls.
 */
public class CallAudioWatchdog extends CallsManagerListenerBase {
    /**
     * Bit flag set on a {@link CommunicationSession#sessionAttr} to indicate that the session has
     * audio recording resources.
     */
    public static final int SESSION_ATTR_HAS_AUDIO_RECORD = 1 << 0;

    /**
     * Bit flag set on a {@link CommunicationSession#sessionAttr} to indicate that the session has
     * audio playback resources.
     */
    public static final int SESSION_ATTR_HAS_AUDIO_PLAYBACK = 1 << 1;

    /**
     * Bit flag set on a {@link CommunicationSession#sessionAttr} to indicate that the uid for the
     * session has a phone account allocated.  This helps us track cases where an app is telecom
     * capable but chooses not to use the telecom integration.
     */
    public static final int SESSION_ATTR_HAS_PHONE_ACCOUNT = 1 << 2;

    @IntDef(prefix = { "SESSION_ATTR_" },
            value = {SESSION_ATTR_HAS_AUDIO_RECORD, SESSION_ATTR_HAS_AUDIO_PLAYBACK,
                    SESSION_ATTR_HAS_PHONE_ACCOUNT},
            flag = true)
    @Retention(RetentionPolicy.SOURCE)
    public @interface SessionAttribute {}

    /**
     * Proxy for operations related to phone accounts.
     */
    public interface PhoneAccountRegistrarProxy {
        /**
         * Determines if a specified {@code uid} has an associated phone account registered.
         * @param uid the uid.
         * @return {@code true} if there is a phone account registered, {@code false} otherwise
         */
        boolean hasPhoneAccountForUid(int uid);

        /**
         * Given a {@link PhoneAccountHandle} determines the uid for the app owning the account.
         * @param handle The phone account; the phone account handle's package and userhandle are
         *               ultimately used to find the associated uid.
         * @return the uid for the phone account.
         */
        int getUidForPhoneAccountHandle(PhoneAccountHandle handle);
    }

    /**
     * Keyed on uid, tracks a communication session and whether there are audio record and playback
     * resources for that session.
     */
    public class CommunicationSession {
        private int uid;
        @SessionAttribute
        private int sessionAttr;
        private ArrayMap<Integer, Set<Integer>> audioResourcesByType = new ArrayMap<>();
        private EventManager.Loggable telecomCall;
        private long sessionStartMillis;
        private long sessionStartClockMillis;

        /**
         * @return {@code true} if audio record or playback is held for the session, {@code false}
         * otherwise.
         */
        public boolean hasMediaResources() {
            return (getSessionAttr()
                    & (SESSION_ATTR_HAS_AUDIO_RECORD | SESSION_ATTR_HAS_AUDIO_PLAYBACK)) != 0;
        }

        /**
         * Sets a bit enabled for the session.
         * @param bit the bit
         */
        public void setBit(@SessionAttribute int bit) {
            setSessionAttr(getSessionAttr() | bit);
        }

        /**
         * Clears the specified bit for the session.
         * @param bit the bit
         */
        public void clearBit(@SessionAttribute int bit) {
            setSessionAttr(getSessionAttr() & ~bit);
        }

        /**
         * Determines if a bit is set in the given bitmask.
         * @param mask the bitmask.
         * @param bit The bit
         * @return {@code true} if set, {@code false} otherwise.
         */
        public static boolean isBitSet(@SessionAttribute int mask, @SessionAttribute int bit) {
            return (mask & bit) == bit;
        }

        /**
         * Determines if a bit is set for the current session.
         * @param bit The bit
         * @return {@code true} if set, {@code false} otherwise.
         */
        public boolean isBitSet(@SessionAttribute int bit) {
            return isBitSet(getSessionAttr(), bit);
        }

        /**
         * Generate a string representing the session attributes bitmask, suitable for logging.
         * @param attr The session attributes.
         * @return String of bits!
         */
        public static String sessionAttrToString(@SessionAttribute int attr) {
            return (isBitSet(attr, SESSION_ATTR_HAS_PHONE_ACCOUNT) ? "phac, " : "") +
                    (isBitSet(attr, SESSION_ATTR_HAS_AUDIO_PLAYBACK) ? "ap, " : "") +
                    (isBitSet(attr, SESSION_ATTR_HAS_AUDIO_RECORD) ? "ar, " : "");
        }

        @Override
        public String toString() {
            return "CommSess{" +
                    "uid=" + getUid() +
                    ", created=" + SimpleDateFormat.getDateTimeInstance().format(
                    new Date(getSessionStartClockMillis())) +
                    ", attr=" + sessionAttrToString(getSessionAttr()) +
                    ", callId=" + (getTelecomCall() != null ? getTelecomCall().getId() : "none") +
                    ", duration=" + (mClockProxy.elapsedRealtime() - getSessionStartMillis())/1000 +
                    '}';
        }

        /**
         * The uid for the session.
         */
        public int getUid() {
            return uid;
        }

        public void setUid(int uid) {
            this.uid = uid;
        }

        /**
         * The attributes for the session.
         */
        public int getSessionAttr() {
            return sessionAttr;
        }

        public void setSessionAttr(int sessionAttr) {
            this.sessionAttr = sessionAttr;
        }

        /**
         * ArrayMap, keyed by {@link #SESSION_ATTR_HAS_AUDIO_PLAYBACK} and
         * {@link #SESSION_ATTR_HAS_AUDIO_RECORD}. For each, contains a set of the
         * {@link AudioManager} ids associated with active playback and recording sessions for a
         * uid.
         *
         * {@link AudioPlaybackConfiguration#getPlayerInterfaceId()} is used for audio playback;
         * per docs, this is an identifier unique for the lifetime of the player.
         *
         * {@link AudioRecordingConfiguration#getClientAudioSessionId()} is used for audio record
         * tracking; this is unique similar to the audio playback config.
         */
        public ArrayMap<Integer, Set<Integer>> getAudioResourcesByType() {
            return audioResourcesByType;
        }

        public void setAudioResourcesByType(
                ArrayMap<Integer, Set<Integer>> audioResourcesByType) {
            this.audioResourcesByType = audioResourcesByType;
        }

        /**
         * The Telecom call this session is associated with; set if the call takes place during a
         * telecom call.
         */
        public EventManager.Loggable getTelecomCall() {
            return telecomCall;
        }

        public void setTelecomCall(EventManager.Loggable telecomCall) {
            this.telecomCall = telecomCall;
        }

        /**
         * The time in {@link android.os.SystemClock#elapsedRealtime()} timebase when the session
         * started.  Used only to determine duration.
         */
        public long getSessionStartMillis() {
            return sessionStartMillis;
        }

        public void setSessionStartMillis(long sessionStartMillis) {
            this.sessionStartMillis = sessionStartMillis;
        }

        /**
         * The time in {@link System#currentTimeMillis()} timebase when the session started; used
         * to indicate the wall block time when the session started.
         */
        public long getSessionStartClockMillis() {
            return sessionStartClockMillis;
        }

        public void setSessionStartClockMillis(long sessionStartClockMillis) {
            this.sessionStartClockMillis = sessionStartClockMillis;
        }
    }

    /**
     * Listener for AudioManager audio playback changes.  Finds audio playback tagged for voice
     * communication.  Updates the {@link #mCommunicationSessions} based on this data to track if
     * audio playback it taking place.
     *
     * Note: {@link AudioPlaybackCallback} reports information about audio playback for an app; if
     * an app releases audio playback resources, the list of audio playback configurations no longer
     * includes a {@link AudioPlaybackConfiguration} for that specific audio playback session.  This
     * API semantic is why the code below is a bit confusing; in the listener we need to track all
     * the ids we've seen and then correlate that back to what we knew about it from the last
     * callback.
     *
     * An app may have MULTIPLE {@link AudioPlaybackConfiguration} for voip use-cases and switch
     * between them for a single call -- this was observed in live app testing.
     */
    public class WatchdogAudioPlaybackCallback extends AudioPlaybackCallback {
        @Override
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            Map<Integer,Set<Integer>> sessionIdentifiersByUid = new ArrayMap<>();
            for (AudioPlaybackConfiguration config : configs) {
                Log.d(this, "onPlaybackConfigChanged: config=%s", config);
                // only track USAGE_VOICE_COMMUNICATION as this is for VOIP calls.
                if (config.getAudioAttributes() != null
                        && config.getAudioAttributes().getUsage()
                        == AudioAttributes.USAGE_VOICE_COMMUNICATION) {

                    // Skip if the client's pid is same as myself
                    if (config.getClientPid() == Process.myPid()) {
                        continue;
                    }

                    // If an audio session is idle, we don't count it as playing.  It must be in a
                    // started state.
                    boolean isPlaying = config.getPlayerState() == PLAYER_STATE_STARTED;

                    maybeTrackAudioPlayback(config.getClientUid(), config.getPlayerInterfaceId(),
                            isPlaying);
                    if (isPlaying) {
                        // Track the list of player id active for each uid; we use it later for
                        // cleanup of stale sessions.
                        putOrDefault(sessionIdentifiersByUid,config.getClientUid(),
                                new ArraySet<>()).add(config.getPlayerInterfaceId());
                    }
                }
            }

            // The listener will drop uid/playerInterfaceIds no longer active, so we need to go back
            // and see if any sessions need to be removed now.
            cleanupAttributeForSessions(SESSION_ATTR_HAS_AUDIO_PLAYBACK,
                    sessionIdentifiersByUid);
        }
    }

    /**
     * Similar to {@link WatchdogAudioPlaybackCallback}, tracks audio recording an app performs.
     * This code is handling the onRecordingConfigChanged event from the AudioManager. The event
     * is fired when the list of active recording configurations changes. In this case, the code
     * is only interested in recording configurations that are using the VOICE_COMMUNICATION
     * audio source. For these configurations, the code tracks the session identifiers and
     * potentially adds them to the SESSION_ATTR_HAS_AUDIO_RECORD attribute. The code also cleans
     * up the attribute for any sessions that are no longer active.
     * The same caveat/note applies here; a single app can have many audio recording sessions that
     * the app swaps between during a call.
     */
    public class WatchdogAudioRecordCallback extends AudioManager.AudioRecordingCallback {
        @Override
        public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
            List<AudioRecordingConfiguration> theConfigs =
                    mAudioManager.getActiveRecordingConfigurations();
            Map<Integer,Set<Integer>> sessionIdentifiersByUid = new ArrayMap<>();
            for (AudioRecordingConfiguration config : theConfigs) {
                if (config.getClientAudioSource()
                        == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {

                    putOrDefault(sessionIdentifiersByUid, config.getClientUid(),
                            new ArraySet<>()).add(config.getClientAudioSessionId());
                    maybeTrackAudioRecord(config.getClientUid(), config.getClientAudioSessionId(),
                            true);
                }
            }
            // The listener stops reporting audio sessions that go away, so we need to clean up the
            // session potentially.
            cleanupAttributeForSessions(
                    SESSION_ATTR_HAS_AUDIO_RECORD,
                    sessionIdentifiersByUid);
        }
    }

    // Proxies to make testing possible-ish.
    private final ClockProxy mClockProxy;
    private final PhoneAccountRegistrarProxy mPhoneAccountRegistrarProxy;

    private final WatchdogAudioPlaybackCallback mWatchdogAudioPlayback =
            new WatchdogAudioPlaybackCallback();
    private final WatchdogAudioRecordCallback
            mWatchdogAudioRecordCallack = new WatchdogAudioRecordCallback();
    private final AudioManager mAudioManager;
    private final Handler mHandler;

    // Guards access to mCommunicationSessions.
    private final Object mCommunicationSessionsLock = new Object();

    /**
     * Key - UID of communication app.
     * Value - an instance of {@link CommunicationSession} tracking data for that uid.
     */
    private final Map<Integer, CommunicationSession> mCommunicationSessions = new ArrayMap<>();

    // Local logs for tracking non-telecom calls.
    private final LocalLog mLocalLog = new LocalLog(30);

    private final TelecomMetricsController mMetricsController;

    public CallAudioWatchdog(AudioManager audioManager,
            PhoneAccountRegistrarProxy phoneAccountRegistrarProxy, ClockProxy clockProxy,
            Handler handler, TelecomMetricsController metricsController) {
        mPhoneAccountRegistrarProxy = phoneAccountRegistrarProxy;
        mClockProxy = clockProxy;
        mAudioManager = audioManager;
        mHandler = handler;
        mAudioManager.registerAudioPlaybackCallback(mWatchdogAudioPlayback, mHandler);
        mAudioManager.registerAudioRecordingCallback(mWatchdogAudioRecordCallack, mHandler);
        mMetricsController = metricsController;
    }

    /**
     * Tracks Telecom adding a call; we use this to associate a uid's sessions with a call.
     * Note: this is not 100% accurate if there are multiple calls -- we just associate with the
     * first call and leave it at that.  It's not possible to know which audio sessions belong to
     * which Telecom calls.
     * @param call the Telecom call being added.
     */
    @Override
    public void onCallAdded(Call call) {
        // Only track for voip calls.
        if (call.isSelfManaged() || call.isTransactionalCall()) {
            maybeTrackTelecomCall(call);
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        // Only track for voip calls.
        if (call.isSelfManaged() || call.isTransactionalCall()) {
            maybeRemoveCall(call);
        }
    }

    @VisibleForTesting
    public WatchdogAudioPlaybackCallback getWatchdogAudioPlayback() {
        return mWatchdogAudioPlayback;
    }

    @VisibleForTesting
    public WatchdogAudioRecordCallback getWatchdogAudioRecordCallack() {
        return mWatchdogAudioRecordCallack;
    }

    @VisibleForTesting
    public Map<Integer, CommunicationSession> getCommunicationSessions() {
        return mCommunicationSessions;
    }

    /**
     * Include info on audio stuff in the telecom dumpsys.
     * @param pw
     */
    void dump(IndentingPrintWriter pw) {
        pw.println("CallAudioWatchdog:");
        pw.increaseIndent();
        pw.println("Active Sessions:");
        pw.increaseIndent();
        Collection<CommunicationSession> sessions;
        synchronized (mCommunicationSessionsLock) {
            sessions = mCommunicationSessions.values();
        }
        sessions.forEach(pw::println);
        pw.decreaseIndent();
        pw.println("Audio sessions Sessions:");
        pw.increaseIndent();
        mLocalLog.dump(pw);
        pw.decreaseIndent();
        pw.decreaseIndent();
    }

    /**
     * Tracks audio playback for a uid.
     * @param uid the uid of the app having audio back change.
     * @param playerInterfaceId From {@link AudioPlaybackConfiguration#getPlayerInterfaceId()} (see
     * {@link CommunicationSession#audioResourcesByType} for keying info).
     * @param isPlaying {@code true} if audio is starting for the client.
     */
    private void maybeTrackAudioPlayback(int uid, int playerInterfaceId, boolean isPlaying) {
        CommunicationSession session;
        synchronized (mCommunicationSessionsLock) {
            if (!isPlaying) {
                // A session can start in an idle state and never go active; in this case we will
                // not proactively add a new session; we'll just get one if it's already there.
                // When the session goes active we can add it then.
                session = getSession(uid);
            } else {
                // The playback is active, so we need to get or add a new communication session.
                session = getOrAddSession(uid);
            }
        }
        if (session == null) {
            return;
        }

        // First track individual player interface id playing status.
        if (isPlaying) {
            putOrDefault(session.getAudioResourcesByType(), SESSION_ATTR_HAS_AUDIO_PLAYBACK,
                    new ArraySet<>()).add(playerInterfaceId);
        } else {
            putOrDefault(session.getAudioResourcesByType(), SESSION_ATTR_HAS_AUDIO_PLAYBACK,
                    new ArraySet<>()).remove(playerInterfaceId);
        }

        // Keep the bitmask up to date so that we have quicker access to the audio playback state.
        int originalAttrs = session.getSessionAttr();
        // If there are active audio playback clients, then the session has playback.
        if (!session.getAudioResourcesByType().get(SESSION_ATTR_HAS_AUDIO_PLAYBACK).isEmpty()) {
            session.setBit(SESSION_ATTR_HAS_AUDIO_PLAYBACK);
        } else {
            session.clearBit(SESSION_ATTR_HAS_AUDIO_PLAYBACK);
        }

        // If there was a change, log to a call if set.
        if (originalAttrs != session.getSessionAttr() && session.getTelecomCall() != null) {
            Log.addEvent(session.getTelecomCall(), LogUtils.Events.AUDIO_ATTR,
                    CommunicationSession.sessionAttrToString(originalAttrs)
                            + " -> " + CommunicationSession.sessionAttrToString(
                            session.getSessionAttr()));
        }
        Log.d(this, "maybeTrackAudioPlayback: %s", session);
    }

    /**
     * Similar to {@link #maybeTrackAudioPlayback(int, int, boolean)}, except tracks audio records
     * for an app.
     * @param uid the app uid.
     * @param recordSessionID The recording session (per
     * @param isRecording {@code true} if recording, {@code false} otherwise.
     */
    private void maybeTrackAudioRecord(int uid, int recordSessionID, boolean isRecording) {
        synchronized (mCommunicationSessionsLock) {
            CommunicationSession session = getOrAddSession(uid);

            // First track individual recording status.
            if (isRecording) {
                putOrDefault(session.getAudioResourcesByType(), SESSION_ATTR_HAS_AUDIO_RECORD,
                        new ArraySet<>()).add(recordSessionID);
            } else {
                putOrDefault(session.getAudioResourcesByType(), SESSION_ATTR_HAS_AUDIO_RECORD,
                        new ArraySet<>()).remove(recordSessionID);
            }

            int originalAttrs = session.getSessionAttr();
            if (!session.getAudioResourcesByType().get(SESSION_ATTR_HAS_AUDIO_RECORD).isEmpty()) {
                session.setBit(SESSION_ATTR_HAS_AUDIO_RECORD);
            } else {
                session.clearBit(SESSION_ATTR_HAS_AUDIO_RECORD);
            }

            if (originalAttrs != session.getSessionAttr() && session.getTelecomCall() != null) {
                Log.addEvent(session.getTelecomCall(), LogUtils.Events.AUDIO_ATTR,
                        CommunicationSession.sessionAttrToString(originalAttrs)
                        + " -> " + CommunicationSession.sessionAttrToString(
                                session.getSessionAttr()));
            }

            Log.d(this, "maybeTrackAudioRecord: %s", session);
        }
    }

    /**
     * Given a new Telecom call, start a new session or annotate an existing one with this call.
     * Helps to associated resources with a telecom call.
     * @param call the call!
     */
    private void maybeTrackTelecomCall(Call call) {
        int uid = mPhoneAccountRegistrarProxy.getUidForPhoneAccountHandle(
                call.getTargetPhoneAccount());
        CommunicationSession session;
        synchronized (mCommunicationSessionsLock) {
            session = getOrAddSession(uid);
        }
        session.setTelecomCall(call);
        Log.d(this, "maybeTrackTelecomCall: %s", session);
        Log.addEvent(session.getTelecomCall(), LogUtils.Events.AUDIO_ATTR,
                CommunicationSession.sessionAttrToString(session.getSessionAttr()));
    }

    /**
     * Given a telecom call, cleanup the session if there are no audio resources remaining for that
     * session.
     * @param call The call.
     */
    private void maybeRemoveCall(Call call) {
        int uid = mPhoneAccountRegistrarProxy.getUidForPhoneAccountHandle(
                call.getTargetPhoneAccount());
        CommunicationSession session;
        synchronized (mCommunicationSessionsLock) {
            session = getSession(uid);
            if (session == null) {
                return;
            }
            if (!session.hasMediaResources()) {
                mLocalLog.log(session.toString());
                maybeLogMetrics(session);
                mCommunicationSessions.remove(uid);
            }
        }
    }

    /**
     * Returns an existing session for a uid, or {@code null} if none exists.
     * @param uid the uid,
     * @return The session found, or {@code null}.
     */
    private CommunicationSession getSession(int uid) {
        return mCommunicationSessions.get(uid);
    }

    /**
     * Locates an existing session for the specified uid or creates a new one.
     * @param uid the uid
     * @return The session.
     */
    private CommunicationSession getOrAddSession(int uid) {
        CommunicationSession session = mCommunicationSessions.get(uid);
        if (session != null) {
            Log.i(this, "getOrAddSession: uid=%d, ex, %s", uid, session);
            return session;
        } else {
            CommunicationSession newSession = new CommunicationSession();
            newSession.setSessionStartMillis(mClockProxy.elapsedRealtime());
            newSession.setSessionStartClockMillis(mClockProxy.currentTimeMillis());
            newSession.setUid(uid);
            if (mPhoneAccountRegistrarProxy.hasPhoneAccountForUid(uid)) {
                newSession.setBit(SESSION_ATTR_HAS_PHONE_ACCOUNT);
            }
            mCommunicationSessions.put(uid, newSession);
            Log.i(this, "getOrAddSession: uid=%d, new, %s", uid, newSession);
            return newSession;
        }
    }

    /**
     * This method is used to cleanup any playback or recording sessions that may have went away
     * after the {@link AudioPlaybackConfiguration} or {@link AudioRecordingConfiguration} updates.
     *
     * {@link CommunicationSession#audioResourcesByType} is keyed by
     * {@link #SESSION_ATTR_HAS_AUDIO_RECORD} and {@link #SESSION_ATTR_HAS_AUDIO_PLAYBACK} and
     * contains a list of each of the record or playback sessions we've been tracking.
     *
     * @param bit the type of resources to cleanup.
     * @param sessionsByUid A map, keyed on uid of the set of play or record ids that were provided
     *                      in the most recent {@link AudioPlaybackConfiguration} or
     *                      {@link AudioRecordingConfiguration} update.
     */
    private void cleanupAttributeForSessions(int bit, Map<Integer, Set<Integer>> sessionsByUid) {
        synchronized (mCommunicationSessionsLock) {
            // Use an iterator so we can do in-place removal.
            Iterator<Map.Entry<Integer, CommunicationSession>> iterator =
                    mCommunicationSessions.entrySet().iterator();

            // Lets loop through all the uids we're tracking and see that they still have an audio
            // resource of type {@code bit} in {@code sessionsByUid}.
            while (iterator.hasNext()) {
                Map.Entry<Integer, CommunicationSession> next = iterator.next();
                int existingUid = next.getKey();
                CommunicationSession session = next.getValue();

                // Get the set of sessions for this type, or emptyset if none present.
                Set<Integer> sessionsForThisUid = sessionsByUid.getOrDefault(existingUid,
                        Collections.emptySet());

                // Update the known sessions of this resource type in the CommunicationSession.
                Set<Integer> trackedSessions = putOrDefault(session.getAudioResourcesByType(), bit,
                        new ArraySet<>());
                trackedSessions.clear();
                trackedSessions.addAll(sessionsForThisUid);

                // Set or unset the bit in the bitmask for quicker access.
                if (!trackedSessions.isEmpty()) {
                    session.setBit(bit);
                } else {
                    session.clearBit(bit);
                }

                // If audio resources are no longer held for a uid, then we'll clean up its
                // media session.
                if (!session.hasMediaResources() && session.getTelecomCall() == null) {
                    Log.i(this, "cleanupAttributeForSessions: removing session %s", session);
                    mLocalLog.log(session.toString());
                    maybeLogMetrics(session);
                    iterator.remove();
                }
            }
        }
    }

    /**
     * Generic method to put a key value to a map and set to a default it not found, in both cases
     * returning the value.
     *
     * This is a concession due to the fact that {@link Map#putIfAbsent(Object, Object)} returns
     * null if the default is set. 🙄
     *
     * @param map The map.
     * @param key The key to find.
     * @param theDefault The default value for the key to use and return if nothing found.
     * @return The existing key value or the default after adding.
     * @param <K> The map key
     * @param <V> The map value
     */
    private <K,V> V putOrDefault(Map<K,V> map, K key, V theDefault) {
        if (map.containsKey(key)) {
            return map.get(key);
        }

        map.put(key, theDefault);
        return theDefault;
    }

    /**
     * If this call has no associated Telecom {@link Call} and metrics are enabled, log this as a
     * non-telecom call.
     * @param session the session to log.
     */
    private void maybeLogMetrics(CommunicationSession session) {
        if (mMetricsController != null && session.getTelecomCall() == null) {
            mMetricsController.getCallStats().onNonTelecomCallEnd(
                    session.isBitSet(SESSION_ATTR_HAS_PHONE_ACCOUNT),
                    session.getUid(),
                    mClockProxy.elapsedRealtime() - session.getSessionStartMillis());
        }
    }
}

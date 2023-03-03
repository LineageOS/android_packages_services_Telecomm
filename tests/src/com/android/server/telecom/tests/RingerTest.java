/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.telecom.tests;

import static android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.VolumeShaper;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.accessibility.AccessibilityManager;

import com.android.server.telecom.AsyncRingtonePlayer;
import com.android.server.telecom.Call;
import com.android.server.telecom.CallState;
import com.android.server.telecom.InCallController;
import com.android.server.telecom.InCallTonePlayer;
import com.android.server.telecom.Ringer;
import com.android.server.telecom.RingtoneFactory;
import com.android.server.telecom.SystemSettingsUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Spy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@RunWith(JUnit4.class)
public class RingerTest extends TelecomTestCase {
    private static final Uri FAKE_RINGTONE_URI = Uri.parse("content://media/fake/audio/1729");
    private static class UriVibrationEffect extends VibrationEffect {
        final Uri mUri;

        private UriVibrationEffect(Uri uri) {
            mUri = uri;
        }

        @Override
        public VibrationEffect resolve(int defaultAmplitude) {
            return this;
        }

        @Override
        public VibrationEffect scale(float scaleFactor) {
            return this;
        }

        @Override
        public long[] computeCreateWaveformOffOnTimingsOrNull() {
            return null; // not needed
        }

        @Override
        public boolean areVibrationFeaturesSupported(Vibrator vibrator) {
            return true; // not needed
        }

        @Override
        public void validate() {
            // not needed
        }

        @Override
        public long getDuration() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            // not needed
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            UriVibrationEffect that = (UriVibrationEffect) o;
            return Objects.equals(mUri, that.mUri);
        }
    }

    @Mock InCallTonePlayer.Factory mockPlayerFactory;
    @Mock SystemSettingsUtil mockSystemSettingsUtil;
    @Mock AsyncRingtonePlayer mockRingtonePlayer;
    @Mock RingtoneFactory mockRingtoneFactory;
    @Mock Vibrator mockVibrator;
    @Mock InCallController mockInCallController;
    @Mock NotificationManager mockNotificationManager;
    @Mock Ringer.AccessibilityManagerAdapter mockAccessibilityManagerAdapter;

    @Spy Ringer.VibrationEffectProxy spyVibrationEffectProxy;

    @Mock InCallTonePlayer mockTonePlayer;
    @Mock Call mockCall1;
    @Mock Call mockCall2;

    private static final PhoneAccountHandle PA_HANDLE =
            new PhoneAccountHandle(new ComponentName("pa_pkg", "pa_cls"),
                    "pa_id");

    Ringer mRingerUnderTest;
    AudioManager mockAudioManager;
    CompletableFuture<Void> mRingCompletionFuture = new CompletableFuture<>();

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mContext = mComponentContextFixture.getTestDouble().getApplicationContext();
        doAnswer(invocation -> {
            Uri ringtoneUriForEffect = invocation.getArgument(0);
            return new UriVibrationEffect(ringtoneUriForEffect);
        }).when(spyVibrationEffectProxy).get(any(), any());
        when(mockPlayerFactory.createPlayer(anyInt())).thenReturn(mockTonePlayer);
        mockAudioManager = mContext.getSystemService(AudioManager.class);
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        when(mockSystemSettingsUtil.isHapticPlaybackSupported(any(Context.class))).thenReturn(true);
        mockNotificationManager =mContext.getSystemService(NotificationManager.class);
        when(mockTonePlayer.startTone()).thenReturn(true);
        when(mockNotificationManager.matchesCallFilter(any(Bundle.class))).thenReturn(true);
        when(mockRingtoneFactory.hasHapticChannels(any(Ringtone.class))).thenReturn(false);
        mRingerUnderTest = new Ringer(mockPlayerFactory, mContext, mockSystemSettingsUtil,
                mockRingtonePlayer, mockRingtoneFactory, mockVibrator, spyVibrationEffectProxy,
                mockInCallController, mockNotificationManager, mockAccessibilityManagerAdapter);
        when(mockCall1.getState()).thenReturn(CallState.RINGING);
        when(mockCall2.getState()).thenReturn(CallState.RINGING);
        when(mockCall1.getUserHandleFromTargetPhoneAccount()).thenReturn(PA_HANDLE.getUserHandle());
        when(mockCall2.getUserHandleFromTargetPhoneAccount()).thenReturn(PA_HANDLE.getUserHandle());
        mRingerUnderTest.setBlockOnRingingFuture(mRingCompletionFuture);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @SmallTest
    @Test
    public void testNoActionInTheaterMode() {
        // Start call waiting to make sure that it doesn't stop when we start ringing
        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockSystemSettingsUtil.isTheaterModeOn(any(Context.class))).thenReturn(true);
        assertFalse(mRingerUnderTest.startRinging(mockCall2, false));
        verify(mockRingtoneFactory, never())
            .getRingtone(any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean());
        verify(mockTonePlayer, never()).stopTone();
        verify(mockRingtonePlayer, never()).play(any(Ringtone.class));
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testNoActionWithExternalRinger() {
        Bundle externalRingerExtra = new Bundle();
        externalRingerExtra.putBoolean(TelecomManager.EXTRA_CALL_HAS_IN_BAND_RINGTONE, true);
        when(mockCall1.getIntentExtras()).thenReturn(externalRingerExtra);
        when(mockCall2.getIntentExtras()).thenReturn(externalRingerExtra);
        // Start call waiting to make sure that it doesn't stop when we start ringing
        mRingerUnderTest.startCallWaiting(mockCall1);
        assertFalse(mRingerUnderTest.startRinging(mockCall2, false));
        verify(mockRingtoneFactory, never())
            .getRingtone(any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean());
        verify(mockTonePlayer, never()).stopTone();
        verify(mockRingtonePlayer, never()).play(any(Ringtone.class));
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testNoActionWhenDialerRings() throws Exception {
        ensureRingtoneMocked();

        // Start call waiting to make sure that it doesn't stop when we start ringing
        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockInCallController.doesConnectedDialerSupportRinging(
                any(UserHandle.class))).thenReturn(true);
        ensureRingerIsNotAudible();
        assertFalse(mRingerUnderTest.startRinging(mockCall2, false));
        verify(mockRingtoneFactory, never())
            .getRingtone(any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean());
        mRingCompletionFuture.get();
        verify(mockTonePlayer, never()).stopTone();
        verify(mockRingtonePlayer, never()).play(any(Ringtone.class));
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(AudioAttributes.class));
    }

    @SmallTest
    @Test
    public void testAudioFocusStillAcquiredWhenDialerRings() throws Exception {
        ensureRingtoneMocked();

        // Start call waiting to make sure that it doesn't stop when we start ringing
        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockInCallController.doesConnectedDialerSupportRinging(
                any(UserHandle.class))).thenReturn(true);
        ensureRingerIsAudible();
        assertTrue(mRingerUnderTest.startRinging(mockCall2, false));
        verify(mockRingtoneFactory, never())
            .getRingtone(any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean());
        mRingCompletionFuture.get();
        verify(mockTonePlayer, never()).stopTone();
        verify(mockRingtonePlayer, never()).play(any(Ringtone.class));
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testNoActionWhenCallIsSelfManaged() {
        // Start call waiting to make sure that it doesn't stop when we start ringing
        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockCall2.isSelfManaged()).thenReturn(true);
        // We do want to acquire audio focus when self-managed
        assertTrue(mRingerUnderTest.startRinging(mockCall2, true));
        verify(mockTonePlayer, never()).stopTone();
        verify(mockRingtonePlayer, never()).play(any(Ringtone.class));
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testCallWaitingButNoRingForSpecificContacts() {
        when(mockNotificationManager.matchesCallFilter(any(Bundle.class))).thenReturn(false);
        // Start call waiting to make sure that it does stop when we start ringing
        mRingerUnderTest.startCallWaiting(mockCall1);
        verify(mockTonePlayer).startTone();

        assertFalse(mRingerUnderTest.startRinging(mockCall2, false));
        verify(mockTonePlayer).stopTone();
        verify(mockRingtonePlayer, never()).play(any(Ringtone.class));
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testNoVibrateDueToAudioCoupledHaptics() throws Exception {
        Ringtone mockRingtone = ensureRingtoneMocked();

        mRingerUnderTest.startCallWaiting(mockCall1);
        ensureRingerIsAudible();
        enableVibrationWhenRinging();
        // Pretend we're using audio coupled haptics.
        setIsUsingHaptics(mockRingtone, true);
        assertTrue(mRingerUnderTest.startRinging(mockCall1, false));
        mRingCompletionFuture.get();
        verify(mockRingtoneFactory, times(1))
            .getRingtone(any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean());
        verify(mockTonePlayer).stopTone();
        verify(mockRingtonePlayer).play(any(Ringtone.class));
        verify(mockVibrator, never()).vibrate(any(VibrationEffect.class),
                any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testVibrateButNoRingForNullRingtone() throws Exception {
        when(mockRingtoneFactory.getRingtone(
                 any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean()))
            .thenReturn(null);

        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        enableVibrationWhenRinging();
        assertFalse(mRingerUnderTest.startRinging(mockCall2, false));
        mRingCompletionFuture.get();
        verify(mockTonePlayer).stopTone();
        // Ringtone does not exist, make sure it does not try to play it
        verify(mockRingtonePlayer, never()).play(any(Ringtone.class));

        // Play default vibration when future completes with no audio coupled haptics
        verify(mockVibrator).vibrate(eq(mRingerUnderTest.mDefaultVibrationEffect),
                any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testVibrateButNoRingForSilentRingtone() throws Exception {
        Ringtone mockRingtone = ensureRingtoneMocked();

        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockRingtoneFactory.getRingtone(any(Call.class), eq(null), anyBoolean()))
            .thenReturn(mockRingtone);
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);
        when(mockAudioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(0);
        enableVibrationWhenRinging();
        assertFalse(mRingerUnderTest.startRinging(mockCall2, false));
        mRingCompletionFuture.get();
        verify(mockTonePlayer).stopTone();
        // Try to play a silent haptics ringtone
        verify(mockRingtonePlayer).play(any(Ringtone.class));
        verify(mockRingtoneFactory, times(1)).getHapticOnlyRingtone();
        verify(mockRingtoneFactory, never())
            .getRingtone(any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean());

        // Play default vibration when future completes with no audio coupled haptics
        verify(mockVibrator).vibrate(eq(mRingerUnderTest.mDefaultVibrationEffect),
                any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testAudioCoupledHapticsForSilentRingtone() throws Exception {
        Ringtone mockRingtone = ensureRingtoneMocked();

        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);
        when(mockAudioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(0);
        setIsUsingHaptics(mockRingtone, true);
        enableVibrationWhenRinging();
        assertFalse(mRingerUnderTest.startRinging(mockCall2, false));
        verify(mockRingtoneFactory, times(1)).getHapticOnlyRingtone();
        verify(mockRingtoneFactory, never())
            .getRingtone(any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean());
        mRingCompletionFuture.get();
        verify(mockTonePlayer).stopTone();
        // Try to play a silent haptics ringtone
        verify(mockRingtonePlayer).play(any(Ringtone.class));
        // Skip vibration for audio coupled haptics
        verify(mockVibrator, never()).vibrate(any(VibrationEffect.class),
                any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testCustomVibrationForRingtone() throws Exception {
        mRingerUnderTest.startCallWaiting(mockCall1);
        Ringtone mockRingtone = mock(Ringtone.class);
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        when(mockRingtoneFactory.getRingtone(any(Call.class), eq(null), anyBoolean()))
            .thenReturn(mockRingtone);
        when(mockRingtone.getUri()).thenReturn(FAKE_RINGTONE_URI);
        enableVibrationWhenRinging();
        assertTrue(mRingerUnderTest.startRinging(mockCall2, false));
        mRingCompletionFuture.get();
        verify(mockTonePlayer).stopTone();
        verify(mockRingtonePlayer).play(any(Ringtone.class));
        verify(mockRingtoneFactory, times(1))
            .getRingtone(any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean());
        verify(mockVibrator).vibrate(eq(spyVibrationEffectProxy.get(FAKE_RINGTONE_URI, mContext)),
                any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testRingAndNoVibrate() throws Exception {
        ensureRingtoneMocked();

        mRingerUnderTest.startCallWaiting(mockCall1);
        ensureRingerIsAudible();
        enableVibrationOnlyWhenNotRinging();
        assertTrue(mRingerUnderTest.startRinging(mockCall2, false));
        mRingCompletionFuture.get();
        verify(mockRingtoneFactory, times(1))
            .getRingtone(any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean());
        verify(mockTonePlayer).stopTone();
        verify(mockRingtonePlayer).play(any(Ringtone.class));
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testRingWithRampingRinger() throws Exception {
        ensureRingtoneMocked();

        mRingerUnderTest.startCallWaiting(mockCall1);
        ensureRingerIsAudible();
        enableRampingRinger();
        enableVibrationWhenRinging();
        assertTrue(mRingerUnderTest.startRinging(mockCall2, false));
        verify(mockRingtoneFactory, times(1))
            .getRingtone(any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean());
        mRingCompletionFuture.get();
        verify(mockTonePlayer).stopTone();
        verify(mockRingtonePlayer).play(any(Ringtone.class));
    }

    @SmallTest
    @Test
    public void testSilentRingWithHfpStillAcquiresFocus1() throws Exception {
        mRingerUnderTest.startCallWaiting(mockCall1);
        ensureRingtoneMocked();
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        when(mockAudioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(0);
        enableVibrationOnlyWhenNotRinging();
        assertTrue(mRingerUnderTest.startRinging(mockCall2, true));
        mRingCompletionFuture.get();
        verify(mockTonePlayer).stopTone();
        verify(mockRingtonePlayer, never()).play(any(Ringtone.class));
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testSilentRingWithHfpStillAcquiresFocus2() throws Exception {
        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockRingtoneFactory.getRingtone(
                 any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean()))
            .thenReturn(null);
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        when(mockAudioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(0);
        enableVibrationOnlyWhenNotRinging();
        assertTrue(mRingerUnderTest.startRinging(mockCall2, true));
        mRingCompletionFuture.get();
        verify(mockTonePlayer).stopTone();
        verify(mockRingtonePlayer, never()).play(any(Ringtone.class));
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    @SmallTest
    @Test
    public void testRingAndVibrateForAllowedCallInDndMode() throws Exception {
        mRingerUnderTest.startCallWaiting(mockCall1);
        Ringtone mockRingtone = mock(Ringtone.class);
        when(mockNotificationManager.getZenMode()).thenReturn(ZEN_MODE_IMPORTANT_INTERRUPTIONS);
        when(mockRingtoneFactory.getRingtone(any(Call.class), eq(null), anyBoolean()))
                .thenReturn(mockRingtone);
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        when(mockAudioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(100);
        enableVibrationWhenRinging();
        assertTrue(mRingerUnderTest.startRinging(mockCall2, true));
        verify(mockRingtoneFactory, times(1))
            .getRingtone(any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean());
        mRingCompletionFuture.get();
        verify(mockTonePlayer).stopTone();
        verify(mockRingtonePlayer).play(any(Ringtone.class));
    }

    private Ringtone ensureRingtoneMocked() {
        Ringtone mockRingtone = mock(Ringtone.class);
        when(mockRingtoneFactory.getRingtone(
                 any(Call.class), nullable(VolumeShaper.Configuration.class), anyBoolean()))
            .thenReturn(mockRingtone);
        when(mockRingtoneFactory.getHapticOnlyRingtone()).thenReturn(mockRingtone);
        return mockRingtone;
    }

    /**
     * assert {@link Ringer#shouldRingForContact(Call, Context) } sets the Call object with suppress
     * caller
     *
     * @throws Exception; should not throw exception.
     */
    @Test
    public void testShouldRingForContact_CallSuppressed() throws Exception {
        // WHEN
        when(mockCall1.wasDndCheckComputedForCall()).thenReturn(false);
        when(mockCall1.getHandle()).thenReturn(Uri.parse(""));

        when(mContext.getSystemService(NotificationManager.class)).thenReturn(
                mockNotificationManager);
        when(mockNotificationManager.matchesCallFilter(any(Bundle.class))).thenReturn(false);

        // THEN
        assertFalse(mRingerUnderTest.shouldRingForContact(mockCall1));
        verify(mockCall1, atLeastOnce()).setCallIsSuppressedByDoNotDisturb(true);
    }

    /**
     * assert {@link Ringer#shouldRingForContact(Call, Context) } sets the Call object with ring
     * caller
     *
     * @throws Exception; should not throw exception.
     */
    @Test
    public void testShouldRingForContact_CallShouldRing() throws Exception {
        // WHEN
        when(mockCall1.wasDndCheckComputedForCall()).thenReturn(false);
        when(mockCall1.getHandle()).thenReturn(Uri.parse(""));
        when(mockNotificationManager.matchesCallFilter(any(Bundle.class))).thenReturn(true);

        // THEN
        assertTrue(mRingerUnderTest.shouldRingForContact(mockCall1));
        verify(mockCall1, atLeastOnce()).setCallIsSuppressedByDoNotDisturb(false);
    }

    @Test
    public void testNoFlashNotificationWhenCallSuppressed() {
        ensureRingtoneMocked();
        // Start call waiting to make sure that it doesn't stop when we start ringing
        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockCall2.wasDndCheckComputedForCall()).thenReturn(false);
        when(mockCall2.getHandle()).thenReturn(Uri.parse(""));
        when(mockNotificationManager.matchesCallFilter(any(Bundle.class))).thenReturn(false);

        assertFalse(mRingerUnderTest.shouldRingForContact(mockCall2));
        assertFalse(mRingerUnderTest.startRinging(mockCall2, false));
        verify(mockAccessibilityManagerAdapter, never())
                .startFlashNotificationSequence(any(Context.class), anyInt());
    }

    @Test
    public void testStartFlashNotificationWhenRingStarts()  {
        ensureRingtoneMocked();
        // Start call waiting to make sure that it doesn't stop when we start ringing
        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockCall2.wasDndCheckComputedForCall()).thenReturn(false);
        when(mockCall2.getHandle()).thenReturn(Uri.parse(""));
        when(mockNotificationManager.matchesCallFilter(any(Bundle.class))).thenReturn(true);

        assertTrue(mRingerUnderTest.shouldRingForContact(mockCall2));
        assertTrue(mRingerUnderTest.startRinging(mockCall2, false));
        verify(mockAccessibilityManagerAdapter, atLeastOnce())
                .startFlashNotificationSequence(any(Context.class), anyInt());
    }

    @Test
    public void testStopFlashNotificationWhenRingStops() {
        ensureRingtoneMocked();
        // Start call waiting to make sure that it doesn't stop when we start ringing
        mRingerUnderTest.startCallWaiting(mockCall1);
        when(mockCall2.wasDndCheckComputedForCall()).thenReturn(false);
        when(mockCall2.getHandle()).thenReturn(Uri.parse(""));
        when(mockNotificationManager.matchesCallFilter(any(Bundle.class))).thenReturn(true);

        assertTrue(mRingerUnderTest.shouldRingForContact(mockCall2));
        assertTrue(mRingerUnderTest.startRinging(mockCall2, false));
        mRingerUnderTest.stopRinging();
        verify(mockAccessibilityManagerAdapter, atLeastOnce())
                .stopFlashNotificationSequence(any(Context.class));

    }

    @SmallTest
    @Test
    public void testNoRingingForQuietProfile() {
        UserManager um = mContext.getSystemService(UserManager.class);
        when(um.isManagedProfile(PA_HANDLE.getUserHandle().getIdentifier())).thenReturn(true);
        when(um.isQuietModeEnabled(PA_HANDLE.getUserHandle())).thenReturn(true);
        // We don't want to acquire audio focus when self-managed
        assertFalse(mRingerUnderTest.startRinging(mockCall2, true));
        verify(mockTonePlayer, never()).stopTone();
        verify(mockRingtonePlayer, never()).play(any(Ringtone.class));
        verify(mockVibrator, never())
                .vibrate(any(VibrationEffect.class), any(VibrationAttributes.class));
    }

    private void ensureRingerIsAudible() {
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        when(mockAudioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(100);
    }

    private void ensureRingerIsNotAudible() {
        when(mockAudioManager.getRingerMode()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        when(mockAudioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(0);
    }

    private void enableVibrationWhenRinging() {
        when(mockVibrator.hasVibrator()).thenReturn(true);
        when(mockSystemSettingsUtil.isRingVibrationEnabled(any(Context.class))).thenReturn(true);
    }

    private void enableVibrationOnlyWhenNotRinging() {
        when(mockVibrator.hasVibrator()).thenReturn(true);
        when(mockSystemSettingsUtil.isRingVibrationEnabled(any(Context.class))).thenReturn(false);
    }

    private void enableRampingRinger() {
        when(mockSystemSettingsUtil.isRampingRingerEnabled(any(Context.class))).thenReturn(true);
    }

    private void setIsUsingHaptics(Ringtone mockRingtone, boolean useHaptics) {
        when(mockSystemSettingsUtil.isHapticPlaybackSupported(any(Context.class)))
            .thenReturn(useHaptics);
        when(mockSystemSettingsUtil.isAudioCoupledVibrationForRampingRingerEnabled())
            .thenReturn(useHaptics);
        when(mockRingtone.hasHapticChannels()).thenReturn(useHaptics);
    }
}

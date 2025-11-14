/*
 * Copyright (C) 2025 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.server.telecom.tests;

import static com.android.server.telecom.callfiltering.CallFilteringResult.DND_NOT_DETERMINED;
import static com.android.server.telecom.callfiltering.CallFilteringResult.DND_NOT_SUPPRESSED;
import static com.android.server.telecom.callfiltering.CallFilteringResult.DND_SUPPRESSED;
import static org.junit.Assert.assertEquals;

import com.android.server.telecom.callfiltering.CallFilteringResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CallFilteringResultTest {
    @Test
    public void testGetCombinedDndSuppressionStatus_notDetermined() {
        assertEquals(DND_NOT_DETERMINED,
                CallFilteringResult.getCombinedDndSuppressionStatus(
                        DND_NOT_DETERMINED, DND_NOT_DETERMINED));
    }

    @Test
    public void testGetCombinedDndSuppressionStatus_suppressed() {
        assertEquals(DND_SUPPRESSED,
                CallFilteringResult.getCombinedDndSuppressionStatus(
                        DND_SUPPRESSED, DND_NOT_DETERMINED));
        assertEquals(DND_SUPPRESSED,
                CallFilteringResult.getCombinedDndSuppressionStatus(
                        DND_SUPPRESSED, DND_NOT_SUPPRESSED));
        assertEquals(DND_SUPPRESSED,
                CallFilteringResult.getCombinedDndSuppressionStatus(
                        DND_SUPPRESSED, DND_SUPPRESSED));
        assertEquals(DND_SUPPRESSED,
                CallFilteringResult.getCombinedDndSuppressionStatus(
                        DND_NOT_DETERMINED, DND_SUPPRESSED));
        assertEquals(DND_SUPPRESSED,
                CallFilteringResult.getCombinedDndSuppressionStatus(
                        DND_NOT_SUPPRESSED, DND_SUPPRESSED));
    }

    @Test
    public void testGetCombinedDndSuppressionStatus_notSuppressed() {
        assertEquals(DND_NOT_SUPPRESSED,
                CallFilteringResult.getCombinedDndSuppressionStatus(
                        DND_NOT_DETERMINED, DND_NOT_SUPPRESSED));
        assertEquals(DND_NOT_SUPPRESSED,
                CallFilteringResult.getCombinedDndSuppressionStatus(
                        DND_NOT_SUPPRESSED, DND_NOT_DETERMINED));
        assertEquals(DND_NOT_SUPPRESSED,
                CallFilteringResult.getCombinedDndSuppressionStatus(
                        DND_NOT_SUPPRESSED, DND_NOT_SUPPRESSED));
    }
}
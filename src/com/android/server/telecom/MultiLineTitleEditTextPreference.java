/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.telecom;

import android.content.Context;
import android.preference.EditTextPreference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

/**
 * This class is used in respond_via_sms_settings.xml
 *
 * Ultra-simple subclass of EditTextPreference that allows the "title" to wrap
 * onto multiple lines.
 *
 * (By default, the title of an EditTextPreference is singleLine="true"; see
 * preference_holo.xml under frameworks/base.  But in the "Respond via SMS"
 * settings UI we want titles to be multi-line, since the customized messages
 * might be fairly long, and should be able to wrap.)
 *
 *
 * TODO: Also, it would reduce clutter if this could be an inner class in
 * RespondViaSmsManager.java, but then there would be no way to reference the
 * class from XML.  That's because
 *    <com.android.server.telecom.MultiLineTitleEditTextPreference ... />
 * isn't valid XML syntax due to the "$" character.  And Preference
 * elements don't have a "class" attribute, so you can't do something like
 * <view class="com.android.server.telecom.Foo$Bar"> as you can with regular views.
 */
public class MultiLineTitleEditTextPreference extends EditTextPreference {
    public MultiLineTitleEditTextPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public MultiLineTitleEditTextPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MultiLineTitleEditTextPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setLayoutResource(com.android.server.telecom.R.layout.preference_multiline_title_edittext);
    }
}

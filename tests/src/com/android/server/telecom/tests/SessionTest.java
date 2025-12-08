/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static junit.framework.Assert.fail;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.assertEquals;

import android.telecom.Log;
import android.telecom.Logging.Session;

import androidx.test.filters.SmallTest;

import com.android.server.telecom.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for android.telecom.Logging.Session
 */

@RunWith(JUnit4.class)
public class SessionTest extends TelecomTestCase {

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Ensure creating two sessions that are parent/child of each other does not lead to a crash
     * or infinite recursion when using Session#printFullSessionTree.
     */
    @SmallTest
    @Test
    public void testRecursion_printFullSessionTree() {
        Log.startSession("testParent");
        // Running in the same thread, so mark as invisible subsession
        Session childSession = Log.getSessionManager()
                .createSubsession(true /*isStartedFromActiveSession*/);
        Log.continueSession(childSession, "child");
        Session parentSession = childSession.getParentSession();
        // Create a circular dependency and ensure we do not crash
        parentSession.setParentSession(childSession);
        childSession.addChild(parentSession);

        // Make sure calling these methods does not result in a crash
        try {
            parentSession.printFullSessionTree();
            childSession.printFullSessionTree();
        } catch (Exception e) {
            fail("Exception: " + e.getMessage());
        } finally {
            // End child
            Log.endSession();
            // End parent
            Log.endSession();
        }
    }

    /**
     * Ensure creating two sessions that are parent/child of each other does not lead to a crash
     * or infinite recursion when using Session#getFullMethodPath.
     */
    @SmallTest
    @Test
    public void testRecursion_getFullMethodPath() {
        Log.startSession("testParent");
        // Running in the same thread, so mark as invisible subsession
        Session childSession = Log.getSessionManager()
                .createSubsession(true /*isStartedFromActiveSession*/);
        Log.continueSession(childSession, "child");
        Session parentSession = childSession.getParentSession();
        // Create a circular dependency and ensure we do not crash
        parentSession.setParentSession(childSession);
        childSession.addChild(parentSession);

        // Make sure calling these methods does not result in a crash
        try {
            parentSession.getFullMethodPath(false /*truncatePath*/);
            childSession.getFullMethodPath(false /*truncatePath*/);
        } catch (Exception e) {
            fail("Exception: " + e.getMessage());
        } finally {
            // End child
            Log.endSession();
            // End parent
            Log.endSession();
        }
    }

    /**
     * Ensure creating two sessions that are parent/child of each other does not lead to a crash
     * or infinite recursion when using Session#getFullMethodPath.
     */
    @SmallTest
    @Test
    public void testRecursion_getFullMethodPathTruncated() {
        Log.startSession("testParent");
        // Running in the same thread, so mark as invisible subsession
        Session childSession = Log.getSessionManager()
                .createSubsession(true /*isStartedFromActiveSession*/);
        Log.continueSession(childSession, "child");
        Session parentSession = childSession.getParentSession();
        // Create a circular dependency and ensure we do not crash
        parentSession.setParentSession(childSession);
        childSession.addChild(parentSession);

        // Make sure calling these methods does not result in a crash
        try {
            parentSession.getFullMethodPath(true /*truncatePath*/);
            childSession.getFullMethodPath(true /*truncatePath*/);
        } catch (Exception e) {
            fail("Exception: " + e.getMessage());
        } finally {
            // End child
            Log.endSession();
            // End parent
            Log.endSession();
        }
    }

    /**
     * Ensure creating two sessions that are parent/child of each other does not lead to a crash
     * or infinite recursion when using Session#toString.
     */
    @SuppressWarnings("ReturnValueIgnored")
    @SmallTest
    @Test
    public void testRecursion_toString() {
        Log.startSession("testParent");
        // Running in the same thread, so mark as invisible subsession
        Session childSession = Log.getSessionManager()
                .createSubsession(true /*isStartedFromActiveSession*/);
        Log.continueSession(childSession, "child");
        Session parentSession = childSession.getParentSession();
        // Create a circular dependency and ensure we do not crash
        parentSession.setParentSession(childSession);
        childSession.addChild(parentSession);

        // Make sure calling these methods does not result in a crash
        try {
            parentSession.toString();
            childSession.toString();
        } catch (Exception e) {
            fail("Exception: " + e.getMessage());
        } finally {
            // End child
            Log.endSession();
            // End parent
            Log.endSession();
        }
    }

    /**
     * Ensure creating two sessions and setting the child as the parent to itself doesn't cause a
     * crash due to infinite recursion.
     */
    @SuppressWarnings("ReturnValueIgnored")
    @SmallTest
    @Test
    public void testRecursion_toString_childCircDep() {
        Log.startSession("testParent");
        // Running in the same thread, so mark as invisible subsession
        Session childSession = Log.getSessionManager()
                .createSubsession(true /*isStartedFromActiveSession*/);
        Log.continueSession(childSession, "child");
        Session parentSession = childSession.getParentSession();
        // Create a circular dependency and ensure we do not crash
        childSession.setParentSession(childSession);

        // Make sure calling these methods does not result in a crash
        try {
            parentSession.toString();
            childSession.toString();
        } catch (Exception e) {
            fail("Exception: " + e.getMessage());
        } finally {
            // End child
            Log.endSession();
            // End parent
            Log.endSession();
        }
    }

    /**
     * Ensure creating two sessions that are parent/child of each other does not lead to a crash
     * or infinite recursion when using Session#getInfo.
     */
    @SmallTest
    @Test
    public void testRecursion_getInfo() {
        Log.startSession("testParent");
        // Running in the same thread, so mark as invisible subsession
        Session childSession = Log.getSessionManager()
                .createSubsession(true /*isStartedFromActiveSession*/);
        Log.continueSession(childSession, "child");
        Session parentSession = childSession.getParentSession();
        // Create a circular dependency and ensure we do not crash
        parentSession.setParentSession(childSession);
        childSession.addChild(parentSession);

        // Make sure calling these methods does not result in a crash
        try {
            Session.Info.getInfo(parentSession);
            Session.Info.getInfo(childSession);
        } catch (Exception e) {
            fail("Exception: " + e.getMessage());
        } finally {
            // End child
            Log.endSession();
            // End parent
            Log.endSession();
        }
    }

    /**
     * Ensure creating two sessions that are parent/child of each other does not lead to a crash
     * or infinite recursion in the general case.
     */
    @SuppressWarnings("ReturnValueIgnored")
    @SmallTest
    @Test
    public void testRecursion() {
        Session parentSession =  createTestSession("parent", "p");
        Session childSession =  createTestSession("child", "c");
        // Create a circular dependency
        parentSession.addChild(childSession);
        childSession.addChild(parentSession);
        parentSession.setParentSession(childSession);
        childSession.setParentSession(parentSession);

        // Make sure calling these methods does not result in a crash
        try {
            parentSession.printFullSessionTree();
            childSession.printFullSessionTree();
            parentSession.getFullMethodPath(false /*truncatePath*/);
            childSession.getFullMethodPath(false /*truncatePath*/);
            parentSession.getFullMethodPath(true /*truncatePath*/);
            childSession.getFullMethodPath(true /*truncatePath*/);
            parentSession.toString();
            childSession.toString();
            Session.Info.getInfo(parentSession);
            Session.Info.getInfo(childSession);
        } catch (Exception e) {
            fail("Exception: " + e.getMessage());
        }
    }

    /**
     * Creates a session tree with one parent and three leaf-node children.
     * This structure caused a negative depth in the legacy tree traversal which caused a crash.
     * In the new recursive traversal, it is not possible to have a negative depth so this
     * structure is ok.
     */
    @SmallTest
    @Test
    public void testPrintTree_triggersNegativeDepthCrash() {
        if(!Flags.fixSessionTreeLogging()){
            return;
        }
        // 1. Setup the tree structure that causes the crash:
        //      parent
        //      / | \
        //   c1  c2  c3
        Session parent = createTestSession("parent", "p");
        Session child1 = createTestSession("child1", "c1");
        Session child2 = createTestSession("child2", "c2");
        Session child3 = createTestSession("child3", "c3");

        parent.addChild(child1);
        child1.setParentSession(parent);
        parent.addChild(child2);
        child2.setParentSession(parent);
        parent.addChild(child3);
        child3.setParentSession(parent);

        // 2. print the session tree
        String result = parent.printFullSessionTree();

        // 3. Assert the output is correct
        assertTrue(result.contains("p@parent"));
        assertTrue(result.contains("c1@parent_child1"));
        assertTrue(result.contains("c2@parent_child2"));
        assertTrue(result.contains("c3@parent_child3"));
    }

    private Session createTestSession(String name, String methodName) {
        return new Session(name, methodName, 0, false, false ,null);
    }
}

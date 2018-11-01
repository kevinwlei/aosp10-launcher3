/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.quickstep;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Process;
import android.util.SparseBooleanArray;
import com.android.launcher3.MainThreadExecutor;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.BackgroundExecutor;
import com.android.systemui.shared.system.KeyguardManagerCompat;
import com.android.systemui.shared.system.RecentTaskInfoCompat;
import com.android.systemui.shared.system.TaskDescriptionCompat;
import com.android.systemui.shared.system.TaskStackChangeListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Manages the recent task list from the system, caching it as necessary.
 */
public class RecentTasksList extends TaskStackChangeListener {

    private final KeyguardManagerCompat mKeyguardManager;
    private final MainThreadExecutor mMainThreadExecutor;
    private final BackgroundExecutor mBgThreadExecutor;

    // The list change id, increments as the task list changes in the system
    private int mChangeId;
    // The last change id when the list was last loaded completely, must be <= the list change id
    private int mLastLoadedId;

    ArrayList<Task> mTasks = new ArrayList<>();

    public RecentTasksList(Context context) {
        mMainThreadExecutor = new MainThreadExecutor();
        mBgThreadExecutor = BackgroundExecutor.get();
        mKeyguardManager = new KeyguardManagerCompat(context);
        mChangeId = 1;
    }

    /**
     * Asynchronously fetches the list of recent tasks.
     *
     * @param numTasks The maximum number of tasks to fetch
     * @param loadKeysOnly Whether to load other associated task data, or just the key
     * @param callback The callback to receive the list of recent tasks
     * @return The change id of the current task list
     */
    public synchronized int getTasks(int numTasks, boolean loadKeysOnly,
            Consumer<ArrayList<Task>> callback) {
        final int requestLoadId = mChangeId;
        final int numLoadTasks = numTasks > 0
                ? numTasks
                : Integer.MAX_VALUE;

        if (mLastLoadedId == mChangeId) {
            // The list is up to date, callback with the same list
            mMainThreadExecutor.execute(() -> {
                if (callback != null) {
                    callback.accept(mTasks);
                }
            });
        }

        // Kick off task loading in the background
        mBgThreadExecutor.submit(() -> {
            ArrayList<Task> tasks = loadTasksInBackground(numLoadTasks,
                    loadKeysOnly);

            mMainThreadExecutor.execute(() -> {
                mTasks = tasks;
                mLastLoadedId = requestLoadId;

                if (callback != null) {
                    callback.accept(tasks);
                }
            });
        });

        return requestLoadId;
    }

    /**
     * @return Whether the provided {@param changeId} is the latest recent tasks list id.
     */
    public synchronized boolean isTaskListValid(int changeId) {
        return mChangeId == changeId;
    }

    @Override
    public synchronized void onTaskStackChanged() {
        mChangeId++;
    }

    @Override
    public synchronized void onActivityPinned(String packageName, int userId, int taskId,
            int stackId) {
        mChangeId++;
    }

    @Override
    public synchronized void onActivityUnpinned() {
        mChangeId++;
    }

    /**
     * Loads and creates a list of all the recent tasks.
     */
    private ArrayList<Task> loadTasksInBackground(int numTasks,
            boolean loadKeysOnly) {
        int currentUserId = Process.myUserHandle().getIdentifier();
        ArrayList<Task> allTasks = new ArrayList<>();
        List<ActivityManager.RecentTaskInfo> rawTasks =
                ActivityManagerWrapper.getInstance().getRecentTasks(numTasks, currentUserId);
        // The raw tasks are given in most-recent to least-recent order, we need to reverse it
        Collections.reverse(rawTasks);

        SparseBooleanArray tmpLockedUsers = new SparseBooleanArray() {
            @Override
            public boolean get(int key) {
                if (indexOfKey(key) < 0) {
                    // Fill the cached locked state as we fetch
                    put(key, mKeyguardManager.isDeviceLocked(key));
                }
                return super.get(key);
            }
        };

        int taskCount = rawTasks.size();
        for (int i = 0; i < taskCount; i++) {
            ActivityManager.RecentTaskInfo rawTask = rawTasks.get(i);
            RecentTaskInfoCompat t = new RecentTaskInfoCompat(rawTask);
            Task.TaskKey taskKey = new Task.TaskKey(rawTask);
            Task task;
            if (!loadKeysOnly) {
                ActivityManager.TaskDescription rawTd = t.getTaskDescription();
                TaskDescriptionCompat td = new TaskDescriptionCompat(rawTd);
                boolean isLocked = tmpLockedUsers.get(t.getUserId());
                task = new Task(taskKey, td.getPrimaryColor(), td.getBackgroundColor(),
                        t.supportsSplitScreenMultiWindow(), isLocked, rawTd, t.getTopActivity());
            } else {
                task = new Task(taskKey);
            }
            allTasks.add(task);
        }

        return allTasks;
    }
}
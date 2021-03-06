/*
 * Tencent is pleased to support the open source community by making Tinker available.
 *
 * Copyright (C) 2016 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dx168.patchsdk.sample.tinker;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.widget.Toast;

import com.tencent.tinker.lib.tinker.TinkerApplicationHelper;
import com.tencent.tinker.lib.util.TinkerLog;
import com.tencent.tinker.loader.app.ApplicationLike;
import com.tencent.tinker.loader.shareutil.ShareConstants;
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals;

/**
 * optional, use dynamic configuration is better way
 * for native crash,
 * <p/>
 * Created by zhangshaowen on 16/7/3.
 * com.dx168.patchsdk.sample.tinker's crash is caught by {@code LoadReporter.onLoadException}
 * use {@code TinkerApplicationHelper} api, no need to install com.dx168.patchsdk.sample.tinker!
 */
public class SampleUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "Tinker.SampleUncaughtExHandler";

    private final Thread.UncaughtExceptionHandler ueh;
    private static final long   QUICK_CRASH_ELAPSE  = 10 * 1000;
    public static final  int    MAX_CRASH_COUNT     = 3;
    private static final String DALVIK_XPOSED_CRASH = "Class ref in pre-verified class resolved to unexpected implementation";

    public SampleUncaughtExceptionHandler() {
        ueh = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        TinkerLog.e(TAG, "uncaughtException:" + ex.getMessage());
        tinkerFastCrashProtect();
        tinkerPreVerifiedCrashHandler(ex);
        ueh.uncaughtException(thread, ex);
    }

    /**
     * Such as Xposed, if it try to load some class before we load from patch files.
     * With dalvik, it will crash with "Class ref in pre-verified class resolved to unexpected implementation".
     * With art, it may crash at some times. But we can't know the actual crash type.
     * If it use Xposed, we can just clean patch or mention user to uninstall it.
     */
    private void tinkerPreVerifiedCrashHandler(Throwable ex) {
        if (SampleUtils.isXposedExists(ex)) {
            //method 1
            ApplicationLike applicationLike = SampleTinkerManager.getTinkerApplicationLike();
            if (applicationLike == null || applicationLike.getApplication() == null) {
                return;
            }

            if (!TinkerApplicationHelper.isTinkerLoadSuccess(applicationLike)) {
                return;
            }
            boolean isCausedByXposed = false;
            //for art, we can't know the actually crash type
            //art's xposed has not much people
            if (ShareTinkerInternals.isVmArt()) {
                isCausedByXposed = true;
            } else if (ex instanceof IllegalAccessError && ex.getMessage().contains(DALVIK_XPOSED_CRASH)) {
                //for dalvik, we know the actual crash type
                isCausedByXposed = true;
            }

            if (isCausedByXposed) {
                SampleTinkerReport.onXposedCrash();
                TinkerLog.e(TAG, "have xposed: just clean com.dx168.patchsdk.sample.tinker");
                //kill all other process to ensure that all process's code is the same.
                ShareTinkerInternals.killAllOtherProcess(applicationLike.getApplication());

                TinkerApplicationHelper.cleanPatch(applicationLike);
                ShareTinkerInternals.setTinkerDisableWithSharedPreferences(applicationLike.getApplication());
                //method 2
                //or you can mention user to uninstall Xposed!
                Toast.makeText(applicationLike.getApplication(), "please uninstall Xposed, illegal modify the app", Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * if com.dx168.patchsdk.sample.tinker is load, and it crash more than MAX_CRASH_COUNT, then we just clean patch.
     */
    private boolean tinkerFastCrashProtect() {
        ApplicationLike applicationLike = SampleTinkerManager.getTinkerApplicationLike();

        if (applicationLike == null || applicationLike.getApplication() == null) {
            return false;
        }
        if (!TinkerApplicationHelper.isTinkerLoadSuccess(applicationLike)) {
            return false;
        }

        final long elapsedTime = SystemClock.elapsedRealtime() - applicationLike.getApplicationStartElapsedTime();
        //this process may not install com.dx168.patchsdk.sample.tinker, so we use TinkerApplicationHelper api
        if (elapsedTime < QUICK_CRASH_ELAPSE) {
            String currentVersion = TinkerApplicationHelper.getCurrentVersion(applicationLike);
            if (ShareTinkerInternals.isNullOrNil(currentVersion)) {
                return false;
            }

            SharedPreferences sp = applicationLike.getApplication().getSharedPreferences(ShareConstants.TINKER_SHARE_PREFERENCE_CONFIG, Context.MODE_MULTI_PROCESS);
            int fastCrashCount = sp.getInt(currentVersion, 0);
            if (fastCrashCount >= MAX_CRASH_COUNT) {
                SampleTinkerReport.onFastCrashProtect();
                TinkerApplicationHelper.cleanPatch(applicationLike);
                TinkerLog.e(TAG, "com.dx168.patchsdk.sample.tinker has fast crash more than %d, we just clean patch!", fastCrashCount);
                return true;
            } else {
                sp.edit().putInt(currentVersion, ++fastCrashCount).commit();
                TinkerLog.e(TAG, "com.dx168.patchsdk.sample.tinker has fast crash %d times", fastCrashCount);
            }
        }

        return false;
    }
}

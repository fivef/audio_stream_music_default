/*
 * Copyright (C) 2014  FiveF
 *
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.fivef.audiostreammusicdefault;

import android.content.Context;
import android.content.res.XResources;
import android.media.AudioManager;
import android.os.Build;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Changes the default stream to the music stream.
 * This class contains the code to be executed by Xposed.
 *
 * Based on the Volumesteps Xposed package by P1ngu1n
 */
public class DefaultStream implements IXposedHookZygoteInit {
    private static final String LOG_TAG = "AudioStreamMusicDefault";


    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        // Load the user's preferences
        final XSharedPreferences prefs = new XSharedPreferences(BuildConfig.APPLICATION_ID);
        final boolean debugging = prefs.getBoolean("pref_debug", false);
        final boolean compatibilityModeLG = prefs.getBoolean("pref_compatibility_mode_lg", false);

        if (debugging) {
            XposedBridge.log(LOG_TAG + "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");

            Map<String, ?> sortedKeys = new TreeMap<String, Object>(prefs.getAll());
            for (Map.Entry<String, ?> entry : sortedKeys.entrySet()) {
                XposedBridge.log(LOG_TAG + entry.getKey() + "=" + entry.getValue().toString());
            }

            if (compatibilityModeLG) {
                XposedBridge.log(LOG_TAG + "Using LG compatibility mode");
            }
        }

        final Class<?> audioServiceClass = XposedHelpers.findClass(compatibilityModeLG ? "android.media.AudioServiceEx" : "android.media.AudioService", null);
        final Class<?> audioSystemClass = XposedHelpers.findClass("android.media.AudioSystem", null);
        final String maxStreamVolumeField = (compatibilityModeLG ? "MAX_STREAM_VOLUME_Ex" : "MAX_STREAM_VOLUME");


        // Whether the volume keys control the music stream or the ringer volume
        boolean volumeKeysControlMusic = prefs.getBoolean("pref_volume_keys_control_music", true);
        if (debugging) XposedBridge.log(LOG_TAG + "Volume keys control " + (volumeKeysControlMusic ? "music" : "ringer"));

        if (volumeKeysControlMusic) {
            XposedHelpers.findAndHookMethod(audioServiceClass, "getActiveStreamType", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    //mVoiceCapable is no longer available in Lollipop
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) {
                        boolean voiceCapable = XposedHelpers.getBooleanField(param.thisObject, "mVoiceCapable");
                        if (!voiceCapable) return;
                    }


                    boolean isInCommunication = (Boolean) XposedHelpers.callMethod(param.thisObject, "isInCommunication");
                    if (isInCommunication) return;

                    boolean isVolumeFixed = Context.getSystemService(AudioManager.class).isVolumeFixed();
                    if (isVolumeFixed) return;

                    int suggestedStreamType = (Integer) param.args[0];
                    if (suggestedStreamType != AudioManager.USE_DEFAULT_STREAM_TYPE) return;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        boolean isAfMusicActiveRecently = (Boolean) XposedHelpers.callMethod(param.thisObject, "isAfMusicActiveRecently", 5000);
                        if (isAfMusicActiveRecently) return;
                    } else {
                        boolean musicStreamActive = (Boolean) XposedHelpers.callStaticMethod(audioSystemClass, "isStreamActive", AudioManager.STREAM_MUSIC, 5000);
                        if (musicStreamActive) return;
                    }

                    // 4.4 and higher call checkUpdateRemoteStateIfActive at the MediaFocusControl class instead of AudioService
                    Object objContainingRemoteStreamMethod = param.thisObject;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        objContainingRemoteStreamMethod = XposedHelpers.getObjectField(param.thisObject, "mMediaFocusControl");
                    }
                    boolean activeRemoteStream = (Boolean) XposedHelpers.callMethod(objContainingRemoteStreamMethod, "checkUpdateRemoteStateIfActive", AudioManager.STREAM_MUSIC);
                    if (activeRemoteStream) return;

                    param.setResult(AudioManager.STREAM_MUSIC);
                    if (debugging) XposedBridge.log(LOG_TAG + "Event: intercepted getActiveStreamType call; returned STREAM_MUSIC");
                }
            });
        }
    }
}

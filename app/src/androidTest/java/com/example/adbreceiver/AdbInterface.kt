package com.example.adbreceiver

/*
 * Test that runs with a broadcast receiver that accepts commands and Unicode encoded text.
 *
 * To start the test:
 * adb shell nohup am instrument -w com.example.adbreceiver.test/androidx.test.runner.AndroidJUnitRunner
 *
 * On Windows, the code page may need to be changed to UTF-8 by using the following command:
 *      chcp 65001
 *
 */
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Base64
import android.view.KeyCharacterMap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 18)
class AdbInterface {
    private var mDevice: UiDevice? = null
    private var mStop = false

    private val ACTION_MESSAGE = "ADB_INPUT_TEXT"
    private val ACTION_MESSAGE_B64 = "ADB_INPUT_B64"
    private val ACTION_CLEAR_TEXT = "ADB_CLEAR_TEXT"
    private val ACTION_STOP = "ADB_STOP"

    private var mReceiver: BroadcastReceiver? = null
    private val mInstrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun adbListener() {
        mDevice = UiDevice.getInstance(mInstrumentation)
        mReceiver = getReceiver()
        runUntilAskedToStop()
    }

    private fun getReceiver(): AdbReceiver {
        val filter = IntentFilter(ACTION_MESSAGE).apply {
            addAction(ACTION_MESSAGE_B64)
            addAction(ACTION_CLEAR_TEXT)
            addAction(ACTION_STOP)
        }
        return AdbReceiver().apply {
            ApplicationProvider.getApplicationContext<Context>().registerReceiver(this, filter)
        }
    }

    // Enter text into the currently focussed field. If not field has focus, ignore the message.
    private fun inputMsg(s: String?) {
        s ?: return

        val keyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = keyCharacterMap.getEvents(s.toCharArray())
        if (events == null) {
            // Unicode characters? Can't inject these until we determine how to do it.
            mDevice?.findObject(By.focused(true))?.text = s
        } else {
            for (e in events) {
                mInstrumentation.uiAutomation.injectInputEvent(e, true)
            }
        }
    }

    private fun runUntilAskedToStop() {
        try {
            // Keep us running to receive commands.
            // Really not a good way to go, but it works for the proof of concept.
            while (!mStop) {
                Thread.sleep(10000)
            }
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    internal inner class AdbReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {

            when (intent.action) {

                // Text message
                ACTION_MESSAGE -> {
                    val msg = intent.getStringExtra("msg")
                    inputMsg(msg)
                }

                // Text message encode as base-64. Use if certain characters can't cross through
                // the ADB interface.
                ACTION_MESSAGE_B64 -> {
                    intent.getStringExtra("msg")?.apply {
                        val b64 = Base64.decode(this, Base64.DEFAULT)
                        try {
                            inputMsg(String(b64, Charsets.UTF_8))
                        } catch (e: Exception) {
                        }
                    }
                }

                // Just clear the text field.
                ACTION_CLEAR_TEXT -> inputMsg("")

                // Stop running
                ACTION_STOP -> {
                    mStop = true
                    ApplicationProvider.getApplicationContext<Context>()
                        .unregisterReceiver(mReceiver)
                }
            }
        }
    }
}
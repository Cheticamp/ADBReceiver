package com.example.adbreceiver

/*
 * A stand-alone automation test that runs with a broadcast receiver that accepts Unicode text
 * from adb for insertion into the key event stream.. This project runs as an instrumented test on
 * Android to gain access to the functionality of UI Automator
 * (https://developer.android.com/training/testing/ui-automator)
 *
 * Reason for this project: A common way to send character input into Android through adb for
 * testing is to use the "adb shell input [text]" command. This works well if the text is
 * ASCII but fails if the text is non-ASCII. This project is an attempt to overcome this
 * restriction by permitting non-ASCII Unicode characters to be delivered to the Android
 * system through the adb bridge.
 *
 * Prerequisites: The targeted Android system must have a physical keyboard enabled and active that
 * is capable of creating the Unicode characters that the interface will receive to display. This
 * is a requirement because this interface will receive Unicode characters, translate them into
 * key codes/scan codes and deliver these codes to Android where the framework will translate them
 * back to the Unicode characters for display/action. Without the physical keyboard software
 * in place, Android will not be able to make a good translation back to the Unicode characters
 * from the key codes/scan codes. Physical keyboard can be enabled in "Settings."
 *
 * To start the test from the command line (recommended):
 *
 *      adb shell "nohup am instrument -w com.example.adbreceiver.test/androidx.test.runner.AndroidJUnitRunner &"
 *
 * If you are presented with a prompt, something like "com.example.adbreceiver.AdbInterface:",
 * you can abort out of it with ctrl-c (Windows) to return to the local terminal prompt. The test
 * should continue to run in the background even if adb and Android Studio are shut down and should
 * continue to run until it receives a stop command (adb shell am broadcast - a ADB_STOP).
 *
 * On Windows, the code page may need to be changed to UTF-8 by using the following command:
 *      chcp 65001
 *
 * Commands are delivered through adb and the Android shell as follows:
 *
 *      adb shell am broadcast -a [command] [--es msg 'text']
 *
 * where [command] [-ed msg 'text'] is one of the following.
 *
 * ADB_CLEAR_TEXT - clears the text from the currently focussed field.
 *
 * ADB_INPUT_TEXT --es msg '[text]' where [text] is any text that can be produced by the
 *      currently active physical keyboard. Go to "Settings" to change the physical keyboard.
 *
 * ADB_INPUT_B64 -es msg '[base64Text]' where '[base64Text]' is the base-64 representation
 *      of the text to be entered.
 *
 * For the input commands, an attempt is made to inject the key events for the specified
 * text using the UI Automator for APIs 29 and below. For these APIs, the text will be presented
 * by Android as if the text came from the physical keyboard that is currently active. The key
 * events will be interpreted against the key character maps of the active physical keyboard for
 * display. The interface will not display any characters that cannot be produced by the physical
 * keyboard.
 *
 * For API 30 and up (currently just 31), the generated key events always produce an
 * ASCII result, so, for API 30+, the interface sends the text directly to the view system through
 * UI Automator. The result is that, for an EditText, the entire contents of the view
 * will be replaced. The inability to create KeyEvents that register correctly with the display
 * seems to be a change in the behavior of the Android framework. As a result, text sent for
 * display may have no effect if there is not an EditText (or similar) view that has focus.
 *
 * For instance, to send the Hebrew characters `שלום', do the following:
 *
 * 1. Make sure the current physical keyboard has "Hebrew" defined as the language.
 * 2. Start AdbInterface
 *          adb shell "nohup am instrument -w com.example.adbreceiver.test/androidx.test.runner.AndroidJUnitRunner &"
 * 3. Start any app and set the focus on a field that can accept the characters.
 * 4. Execute the following command from a terminal session:
 *          adb shell am broadcast -a ADB_INPUT_TEXT --es msg 'שלום'
 * 5. The characters will appear in the field either one-by-one (API 29-) or immediately as
 *    replacement text (for API 30+)
 *
 * Important! Make sure that the target that will receive the generated key events is not
 * part of this app since AdbInterface will fail.
 *
 * If the software keyboard is enabled at the same time as the hardware keyboard, then a Unicode
 * character followed immediately by a space will select an entry from a candidate list shown. This
 * behavior is the same if the characters are entered directly from the hardware keyboard. Feature
 * or bug? One work-around is to turn off auto-correction for the software keyboard.
 *
 * Credit goes to the GitHub project ADBKeyboard (https://github.com/senzhk/ADBKeyBoard) for the
 * idea of a background receiver. In the author's opinion, ADBKeyboard is a better way to
 * implement the key event functionality for all APIs, although it has the downside of requiring
 * a keyboard change.
 *
 * Also, see
 * https://stackoverflow.com/questions/70160582/can-accessibilityservice-dispatch-key-events-including-even-unicode-characters
 *
 */
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
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

    private var mReceiver: BroadcastReceiver? = null
    private val mInstrumentation = InstrumentationRegistry.getInstrumentation()

    private val mVirtualKCMap by lazy {
        KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
    }

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

    private fun runUntilAskedToStop() {
        Log.d(TAG, "AdbInterface started")
        try {
            // Keep us running to receive commands.
            // Really not a good way to go, but it works for the proof of concept.
            while (!mStop) {
                Thread.sleep(10000)
            }
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        Log.d(TAG, "Stopped")
    }

    private fun inputMsg(s: String?) {

        if (s.isNullOrEmpty()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The logic to map the characters fails to work for API 30+, so we can just replace
            // the contents of the currently focussed text field.
            setFocussedText(s)
        } else {
            //  Create a KeyEvent that uses the current active keyboard's KeyCharacterMap. If that
            //  doesn't work, try with the virtual character map (ASCII only); otherwise, just
            //  replace the text in the focussed field.

            // Build a dummy KeyEvent to extract the KeyCharacterMap.
            val keyEvent = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, 29, 0, 0, 0, 0)
            val charArray = s.toCharArray()
            val events =
                keyEvent.keyCharacterMap?.getEvents(charArray) ?: mVirtualKCMap.getEvents(charArray)
            if (events != null) {
                injectEvents(events)
            } else {
                setFocussedText(s)
            }
        }
    }

    private fun injectEvents(events: Array<out KeyEvent?>) {
        for (e in events) {
            mInstrumentation.uiAutomation.injectInputEvent(e, true)
        }
    }

    private fun clearText() {
        setFocussedText(null)
    }

    private fun setFocussedText(s: String?) {
        mDevice?.findObject(By.focused(true))?.text = s
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
                ACTION_CLEAR_TEXT -> clearText()

                // Stop running
                ACTION_STOP -> {
                    mStop = true
                    ApplicationProvider.getApplicationContext<Context>()
                        .unregisterReceiver(mReceiver)
                }
            }
        }
    }

    companion object {
        const val TAG = "AdbInterface"

        // Accept text input. The characters received should be characters that the current active
        // physical keyboard can produce.
        private const val ACTION_MESSAGE = "ADB_INPUT_TEXT"

        // Accept text input that is encoded in base-64.
        private const val ACTION_MESSAGE_B64 = "ADB_INPUT_B64"

        // Clear the text field that has the current focus.
        private const val ACTION_CLEAR_TEXT = "ADB_CLEAR_TEXT"

        // Stop this test and exit.
        private const val ACTION_STOP = "ADB_STOP"
    }
}
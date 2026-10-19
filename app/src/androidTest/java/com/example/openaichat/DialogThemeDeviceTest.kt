package com.example.openaichat

import android.app.AlertDialog
import android.content.DialogInterface
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Native installation/attachment timing cannot be established by Robolectric alone. */
@RunWith(AndroidJUnit4::class)
class DialogThemeDeviceTest {
    @Test fun showAndCreateStyleInstalledControlsWithoutReplacingTheCallersOnShowListener() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (id in AppThemePalette.themes) for (useCreate in listOf(false, true)) {
                val theme = AppThemePalette.byId(id)
                val shown = AtomicBoolean(false)
                val dialog = AtomicReference<AlertDialog>()
                val spinner = AtomicReference<Spinner>()
                val editor = AtomicReference<EditText>()
                scenario.onActivity { activity ->
                    val options = Spinner(activity).apply {
                        adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                            listOf("First", "Second", "Third"))
                        setSelection(1)
                    }
                    spinner.set(options)
                    val input = EditText(activity).apply { setText("Preserved draft") }
                    editor.set(input)
                    val content = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(options)
                        addView(input)
                    }
                    val builder = AppThemeViews.dialog(activity, theme).setTitle("Theme controls")
                        .setView(content).setPositiveButton("Accept", null).setNegativeButton("Cancel", null)
                    dialog.set(if (useCreate) builder.create().also {
                        it.setOnShowListener { shown.set(true) }
                        it.show()
                    } else builder.show())
                }
                try {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity {
                        val installed = dialog.get()
                        assertTrue(installed.window!!.decorView.isAttachedToWindow)
                        if (useCreate) assertTrue("The caller's OnShow listener must survive theming", shown.get())
                        val positive = installed.getButton(DialogInterface.BUTTON_POSITIVE)
                        assertEquals(theme.textPrimary, positive.currentTextColor)
                        positive.isEnabled = false
                        assertEquals(theme.textSecondary, positive.currentTextColor)
                        assertEquals(theme.textPrimary,
                            installed.getButton(DialogInterface.BUTTON_NEGATIVE).currentTextColor)
                        assertEquals(1, spinner.get().selectedItemPosition)
                        val dropDown = spinner.get().adapter.getDropDownView(1, null, spinner.get()) as TextView
                        assertEquals(theme.textPrimary, dropDown.currentTextColor)
                        assertEquals(theme.textPrimary, editor.get().currentTextColor)
                        assertEquals("Preserved draft", editor.get().text.toString())
                    }
                } finally {
                    scenario.onActivity { dialog.get().dismiss() }
                }
            }
        }
    }
}

package com.bontecou.syncmd

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.ui.components.ConflictViewer
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import org.junit.Rule
import org.junit.Test

/** The same bounded dialog/body nesting used by the production Git conflict editor. */
class ConflictViewerLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun boundedConflictDialogCanRenderAndEditWholeFileContents() {
        val conflict = Conflict(filePath = "notes.md", currentContent = "Local contents\n", incomingContent = "Incoming contents\n", baseContent = "Base contents\n")
        val editable = mutableStateOf(true)
        compose.setContent {
            var draft by remember { mutableStateOf(conflict.currentContent) }
            SyncMdTheme {
                AlertDialog(onDismissRequest = {}, title = { Text("Resolve notes.md") }, confirmButton = {},
                    text = { Column {
                        ConflictViewer(conflict, draft, { draft = it }, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), enabled = editable.value)
                    } })
            }
        }
        compose.onNode(hasSetTextAction() and hasText("Resolved content")).performTextReplacement("Combined contents\n")
        compose.onNode(hasSetTextAction() and hasText("Resolved content")).assertTextContains("Combined contents\n")
        compose.runOnIdle { editable.value = false }
        compose.onNodeWithText("Resolved content").assertIsNotEnabled()
        compose.runOnIdle { editable.value = true }
        compose.onNode(hasSetTextAction() and hasText("Resolved content")).assertTextContains("Combined contents\n")
    }
    @Test fun renamedFilenameSelectionWrapsAtLargeTextInBothThemesAndKeepsTheDraft() {
        val ours="long-folder-name/Local notes with a long filename 日本語.md"
        val theirs="incoming-folder/Incoming notes with a long filename Español.md"
        val conflict=Conflict("original.md","Ours\n","Theirs\n",ours=com.bontecou.syncmd.data.models.ConflictSide(ours,"a".repeat(40),33188),
            theirs=com.bontecou.syncmd.data.models.ConflictSide(theirs,"b".repeat(40),33188))
        val dark=mutableStateOf(false)
        compose.setContent {
            val density=androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,1.6f)) {
                SyncMdTheme(darkTheme=dark.value) {
                    var chosen by remember { mutableStateOf(ours) };var draft by remember { mutableStateOf("Retained draft\n") }
                    AlertDialog(onDismissRequest={},title={Text("Resolve renamed file")},confirmButton={},text={
                        ConflictViewer(conflict,draft,{draft=it},Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),
                            resolutionPath=chosen,onResolutionPathChange={chosen=it})
                    })
                }
            }
        }
        for(theme in listOf(false,true)) {
            compose.runOnIdle { dark.value=theme }
            compose.onNodeWithTag("conflict-filename-ours").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("conflict-filename-theirs").performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
            compose.onNode(hasSetTextAction() and hasText("Resolved content")).performScrollTo().assertTextContains("Retained draft\n")
            compose.onNodeWithTag("conflict-filename-ours").performScrollTo()
            val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val image=java.io.File(context.filesDir,"parity-evidence/rename-chooser-${if(theme) "dark" else "light"}-large.png").apply { parentFile!!.mkdirs() }
            image.outputStream().use { compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }

        }
    }

}

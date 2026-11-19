package com.arena.tv.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.arena.tv.ui.theme.focusOutline

/**
 * Text box that doesn't pop the keyboard up just because the TV remote moved over it:
 * on TVs it's only highlighted, and OK opens the keyboard. On phones it behaves normally.
 */
@Composable
fun TvTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    if (!LocalWide.current) {
        OutlinedTextField(value, onValueChange, modifier, label = label, placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
            singleLine = singleLine, maxLines = maxLines, shape = shape, visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions)
        return
    }
    var editing by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val cover = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(editing) { if (editing) { runCatching { field.requestFocus() }; keyboard?.show() } }
    /** Done typing: hide the keyboard and let the remote move on (down to the results, by default). */
    fun finish(dir: FocusDirection?) {
        keyboard?.hide()
        val moved = dir != null && focus.moveFocus(dir)
        editing = false
        if (!moved) scope.launch { kotlinx.coroutines.delay(50); runCatching { cover.requestFocus() } }
    }
    val actions = if (keyboardActions != KeyboardActions.Default) keyboardActions
        else KeyboardActions(onDone = { finish(FocusDirection.Down) }, onSearch = { finish(FocusDirection.Down) }, onGo = { finish(FocusDirection.Down) })
    Box(modifier) {
        OutlinedTextField(value, onValueChange,
            Modifier.fillMaxWidth().focusRequester(field).focusProperties { canFocus = editing }
                .onFocusChanged { if (!it.isFocused && editing) editing = false }
                // Keyboard closed with Back but still in the box: the arrows leave it (they used to only move the cursor,
                // so the results below couldn't be reached), and OK opens the keyboard again.
                .onPreviewKeyEvent { e ->
                    if (!editing || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> { finish(FocusDirection.Down); true }
                        Key.DirectionUp -> { finish(FocusDirection.Up); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { keyboard?.show(); true }
                        else -> false
                    }
                },
            label = label, placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
            singleLine = singleLine, maxLines = maxLines, shape = shape, visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions, keyboardActions = actions)
        // Until OK is pressed, the remote lands on this cover instead of the text box.
        if (!editing) Box(Modifier.matchParentSize().focusRequester(cover).focusOutline(if (shape is RoundedCornerShape) shape else RoundedCornerShape(12.dp))
            .clickable { editing = true })
    }
}

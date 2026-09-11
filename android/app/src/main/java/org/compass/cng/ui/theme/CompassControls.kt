package org.compass.cng.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

val CompassControlMinimumHeight = 56.dp
val CompassCompactControlMinimumHeight = 48.dp

@Composable
fun CompassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = CompassControlMinimumHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.extraLarge,
        colors = colors,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
        content = content,
    )
}

@Composable
fun CompassOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = CompassControlMinimumHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.extraLarge,
        colors = colors,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        content = content,
    )
}

@Composable
fun CompassTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = CompassCompactControlMinimumHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.extraLarge,
        colors = colors,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        content = content,
    )
}

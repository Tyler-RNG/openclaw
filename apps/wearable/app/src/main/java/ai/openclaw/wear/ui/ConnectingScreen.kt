package ai.openclaw.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import ai.openclaw.wear.WearViewModel

@Composable
fun ConnectingScreen(viewModel: WearViewModel) {
    val logs by viewModel.logs.collectAsState()
    val listState = rememberScalingLazyListState()
    val hasError = logs.any { it.startsWith("ERROR") }

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.Start,
        state = listState,
    ) {
        item {
            Text(
                text = "OPENCLAW",
                color = OmnitrixGreen,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
            )
        }
        items(logs) { line ->
            val color = when {
                line.startsWith("ERROR") -> Color.Red
                line.startsWith("Ready") -> OmnitrixGreen
                else -> Color(0xFFAABBAA)
            }
            Text(
                text = "> $line",
                color = color,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 1.dp),
            )
        }
        if (hasError) {
            item {
                Button(
                    onClick = { viewModel.retry() },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = OmnitrixGreen,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Retry", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

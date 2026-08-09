package vn.edu.haui.hvs.safedrive.feature.simulator.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.edu.haui.hvs.safedrive.core.model.VehicleState
import vn.edu.haui.hvs.safedrive.feature.simulator.SimulatorUiState

@Composable
fun SignalDashboard(
    uiState: SimulatorUiState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Telemetry & Signals",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Speed Graph
        SpeedGraph(
            speedHistory = uiState.speedHistory,
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
        )

        // LED Indicators
        val snapshot = uiState.cockpitSnapshot
        val isCrash = snapshot?.vehicleState?.crashDetected == true
        val hasDtc = snapshot?.vehicleState?.activeDtcs?.isNotEmpty() == true
        val seatOccupied = snapshot?.vehicleState?.driverSeatOccupied == true
        val wearableConnected = snapshot?.vehicleState?.wearableConnected == true

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            LedIndicator(label = "Crash", active = isCrash, activeColor = MaterialTheme.colorScheme.error)
            LedIndicator(label = "DTC", active = hasDtc, activeColor = Color(0xFFFFA000)) // Amber
            LedIndicator(label = "Seat", active = seatOccupied, activeColor = MaterialTheme.colorScheme.primary)
            LedIndicator(label = "Wearable", active = wearableConnected, activeColor = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun SpeedGraph(
    speedHistory: List<Float>,
    modifier: Modifier = Modifier
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val maxSpeed = 160f // Giới hạn đồ thị lên 160km/h
            
            // Draw horizontal grid lines
            for (i in 1..4) {
                val y = height * i / 4
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1f
                )
            }

            if (speedHistory.isEmpty()) return@Canvas

            val path = Path()
            val stepX = width / (60 - 1).coerceAtLeast(1) // 60 điểm

            speedHistory.forEachIndexed { index, speed ->
                // Normalize X (right aligned if history < 60)
                val offsetX = width - (speedHistory.size - 1 - index) * stepX
                val normalizedY = height - (speed.coerceIn(0f, maxSpeed) / maxSpeed) * height

                if (index == 0) {
                    path.moveTo(offsetX, normalizedY)
                } else {
                    path.lineTo(offsetX, normalizedY)
                }
            }

            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(
                    width = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )
            )
        }
        
        // Nhãn
        Text(
            text = "Speed (km/h)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(4.dp).align(Alignment.TopStart)
        )
        
        val currentSpeed = speedHistory.lastOrNull() ?: 0f
        Text(
            text = "${currentSpeed.toInt()}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(4.dp).align(Alignment.TopEnd)
        )
    }
}

@Composable
fun LedIndicator(
    label: String,
    active: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    val color = if (active) activeColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

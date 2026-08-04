package vn.edu.haui.hvs.safedrive.feature.assistant.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import vn.edu.haui.hvs.safedrive.core.model.SafeDriveAction

/**
 * Only opens for actions with `requiresConfirmation = true` (docs/android-mvp-plan/04-screen-specs.md,
 * "Confirmation dialog"). Confirm is disabled while a request is in flight so it can only send once.
 */
@Composable
fun ConfirmActionDialog(
    action: SafeDriveAction,
    isConfirming: Boolean,
    errorMessage: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Xác nhận hành động an toàn") },
        text = {
            Text(
                buildString {
                    append(action.title)
                    append("\n\nBạn có chắc chắn muốn thực thi hành động này khi đang vận hành xe không?")
                    if (errorMessage != null) {
                        append("\n\n")
                        append(errorMessage)
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isConfirming) {
                Text(if (isConfirming) "Đang gửi..." else "Xác nhận")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !isConfirming) { Text("Hủy bỏ") }
        },
    )
}

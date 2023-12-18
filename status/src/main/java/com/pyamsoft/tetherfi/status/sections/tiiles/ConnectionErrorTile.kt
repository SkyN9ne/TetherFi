package com.pyamsoft.tetherfi.status.sections.tiiles

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.ContentAlpha
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pyamsoft.pydroid.core.cast
import com.pyamsoft.tetherfi.server.broadcast.BroadcastNetworkStatus
import com.pyamsoft.tetherfi.ui.ServerErrorTile

@Composable
internal fun ConnectionErrorTile(
    modifier: Modifier = Modifier,
    connection: BroadcastNetworkStatus.ConnectionInfo,
    onShowConnectionError: () -> Unit,
) {
  connection.cast<BroadcastNetworkStatus.ConnectionInfo.Error>()?.also {
    StatusTile(
        modifier = modifier,
        color = MaterialTheme.colors.error,
    ) {
      ServerErrorTile(
          onShowError = onShowConnectionError,
      ) { modifier, iconButton ->
        Row(
            modifier = Modifier.fillMaxWidth().then(modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          val color = LocalContentColor.current

          iconButton()

          Text(
              text = "Network Error",
              style =
                  MaterialTheme.typography.caption.copy(
                      color =
                          color.copy(
                              alpha = ContentAlpha.medium,
                          ),
                  ),
          )
        }
      }
    }
  }
}